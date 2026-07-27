package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.PermissionMatrix;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.metadatacenter.util.test.PermissionMatrix.Actor.ANONYMOUS;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OTHER_USER;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OWNER;

/**
 * The authorization grid for a user's own folder — the cells that matter most in CEDAR, because this
 * is where real metadata lives and where a mistake exposes one user's work to another.
 *
 * <p>Every row targets test user 1's home folder. The table states, for each operation, that an
 * unauthenticated caller is refused (401) and that a second authenticated user — who has no grant on
 * this folder — is refused (403). Those denials are the security contract; a regression in any one of
 * them is a data-exposure bug, and until now only a single endpoint (GET on the folder itself) was
 * covered.
 *
 * <p>The owner's own access is asserted for the read operations, which is what makes the denials
 * meaningful: it shows the endpoint works and is genuinely discriminating by identity rather than
 * failing for everyone. Owner rows are omitted for the mutating operations, because a home folder is
 * special-cased (it cannot be renamed or deleted) and that behaviour belongs in its own test rather
 * than being asserted incidentally here. ADMIN is omitted throughout: whether an administrator may
 * read another user's home folder is a policy question this test should not silently answer.
 */
public class FoldersAuthorizationMatrixTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // distinct from the dev server and from every other booting test class.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19027",
        "CEDAR_RESOURCE_ADMIN_PORT", "19127",
        "CEDAR_RESOURCE_STOP_PORT", "19227",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static Map<PermissionMatrix.Actor, String> actors;
  private static String homeFolderId;
  private static String folderPath;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    actors = Map.of(
        OWNER, TestAuthUtil.getTestUser1AuthHeader(cedarConfig),
        OTHER_USER, TestAuthUtil.getTestUser2AuthHeader(cedarConfig));

    EmbeddedCedarNeo4j.seed(cedarConfig);

    // No OpenSearch here: indexing is a no-op and the folder endpoints never search.
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    CedarRequestContext user1Context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    homeFolderId = CedarDataServices.getFolderServiceSession(user1Context).findHomeFolderOf().getId();
    folderPath = "/folders/" + URLEncoder.encode(homeFolderId, StandardCharsets.UTF_8);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @Test
  public void aSecondUserCannotReachAnotherUsersFolder() throws Exception {
    String renameBody = "{\"schema:name\": \"Renamed By An Intruder\", \"schema:description\": \"nope\"}";
    String permissionsBody = "{\"userPermissions\": [], \"groupPermissions\": []}";
    PermissionMatrix matrix = new PermissionMatrix("http://localhost:" + SERVER.getLocalPort(), actors);

    // Reads: the owner may, a stranger may not. Asserting the owner's 200 is what proves the 403 is
    // a decision about identity rather than the endpoint being broken for everybody.
    matrix.when("GET", folderPath)
        .expect(ANONYMOUS, 401)
        .expect(OWNER, 200)
        .expect(OTHER_USER, 403);

    matrix.when("GET", folderPath + "/details")
        .expect(ANONYMOUS, 401)
        .expect(OWNER, 200)
        .expect(OTHER_USER, 403);

    matrix.when("GET", folderPath + "/contents")
        .expect(ANONYMOUS, 401)
        .expect(OWNER, 200)
        .expect(OTHER_USER, 403);

    // The ACL itself must not leak: who a folder is shared with is as sensitive as its contents.
    matrix.when("GET", folderPath + "/permissions")
        .expect(ANONYMOUS, 401)
        .expect(OWNER, 200)
        .expect(OTHER_USER, 403);

    // Writes by a stranger must be refused. The owner is not asserted here: a home folder is
    // special-cased against rename and delete, which is a separate concern.
    matrix.when("PUT", folderPath, renameBody)
        .expect(ANONYMOUS, 401)
        .expect(OTHER_USER, 403);

    matrix.when("DELETE", folderPath)
        .expect(ANONYMOUS, 401)
        .expect(OTHER_USER, 403);

    // NOTE: 401 here is CEDAR's current behaviour and it is inconsistent with every other row —
    // this actor is authenticated, so the refusal should be 403 Forbidden. The cause is that the
    // permission denial travels as a BackendCallResult error of type CedarErrorType.AUTHORIZATION,
    // and that type's default status is UNAUTHORIZED (401). The exception-based denials get it right
    // because they set the status explicitly, which is why the GET rows above are 403.
    //
    // The enum cannot simply be remapped: the same AUTHORIZATION type also carries genuine
    // authentication failures (CedarAccessException, including the missing-header case), which must
    // stay 401. Fixing it means distinguishing the two — a separate error type for permission
    // denials, or an explicit status at the validator sites.
    //
    // Pinned as-is so this suite is green and the anomaly is recorded rather than hidden. When it is
    // fixed, this row fails and should become 403.
    matrix.when("PUT", folderPath + "/permissions", permissionsBody)
        .expect(ANONYMOUS, 401)
        .expect(OTHER_USER, 401);

    matrix.verify();

    // Statuses alone would not prove the refusals had no effect. Re-read as the owner and confirm the
    // folder is intact: still present, still the user's home, still not renamed.
    HttpResponse<String> after = request("GET", folderPath, null, actors.get(OWNER));
    Assertions.assertEquals(200, after.statusCode(), "the owner's folder should have survived the denied requests");
    JsonNode folder = JsonMapper.MAPPER.readTree(after.body());
    Assertions.assertTrue(folder.get("isUserHome").asBoolean(), "the folder is no longer the user's home");
    Assertions.assertNotEquals("Renamed By An Intruder", folder.path("schema:name").asText(),
        "a denied request renamed the folder: " + after.body());
  }

  private HttpResponse<String> request(String method, String path, String body, String authHeader) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json");
    if (authHeader != null) {
      builder.header("Authorization", authHeader);
    }
    builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

}
