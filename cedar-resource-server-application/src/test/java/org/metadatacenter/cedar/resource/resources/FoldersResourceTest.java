package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Endpoint tests for the folder resource against an in-process Neo4j. Authentication is served
 * by the in-memory user service; the graph is seeded with the global objects and test users, so
 * the folder and permission checks below run against the real Cypher layer. Indexing is a no-op,
 * since these tests run without OpenSearch.
 */
public class FoldersResourceTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars.
    // Alternate server ports, so the test instance never collides with a running dev server.
    // Redis is redirected to a dead port as well: queue writes are best-effort, and this
    // enforces that no endpoint under test ever depends on a live Redis
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19007",
        "CEDAR_RESOURCE_ADMIN_PORT", "19107",
        "CEDAR_RESOURCE_STOP_PORT", "19207",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String authHeaderUser1;
  private static String authHeaderUser2;
  private static String homeFolderId;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    authHeaderUser2 = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);

    EmbeddedCedarNeo4j.seed(cedarConfig);

    // These tests run without OpenSearch: indexing becomes a no-op, the queue-backed services
    // stay real (Redis), and the searching service is never exercised by the folder endpoints
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    CedarRequestContext user1Context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    homeFolderId = CedarDataServices.getInstance().getFolderServiceSession(user1Context).findHomeFolderOf().getId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  private HttpResponse<String> request(String method, String path, String body, String authHeader) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json");
    if (authHeader != null) {
      builder.header("Authorization", authHeader);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String id) {
    return URLEncoder.encode(id, StandardCharsets.UTF_8);
  }

  /**
   * Also the regression guard for provenance-name degradation. Reading a folder decorates it with
   * the creator's, owner's and last-updater's display names, which UserSummaryCache resolves by
   * calling the user server. No user server runs here, so no summary can be resolved — and this
   * request must still succeed, serving the folder without those names. It previously answered 500:
   * Guava's cache reports "loader returned null" with an unchecked exception, which escaped
   * UserSummaryCache.getUser and reached the generic exception mapper. In production the same fault
   * turned every read of a resource whose owner could not be resolved — a user server blip, a
   * deleted account — into a 500.
   */
  @Test
  public void homeFolderIsServedToItsOwner() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId), null, authHeaderUser1);
    Assertions.assertEquals(200, response.statusCode());
    JsonNode folder = JsonMapper.MAPPER.readTree(response.body());
    Assertions.assertTrue(folder.get("isUserHome").asBoolean());
    // The unresolvable display names are omitted rather than failing the read.
    Assertions.assertTrue(folder.path("ownedByUserName").isMissingNode() || folder.path("ownedByUserName").isNull(),
        "expected no provenance display name when the user summary cannot be resolved: " + response.body());
  }

  @Test
  public void homeFolderIsHiddenFromOtherUsers() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId), null, authHeaderUser2);
    // The requester is authenticated but denied by the folder's ACL: forbidden, not unauthorized
    Assertions.assertEquals(403, response.statusCode());
    Assertions.assertTrue(response.body().contains("You do not have read access to the folder"));
  }

  @Test
  public void unauthenticatedRequestIsRejected() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId), null, null);
    Assertions.assertEquals(401, response.statusCode());
  }

  @Test
  public void folderLifecycleCreateReadUpdateDelete() throws Exception {
    HttpResponse<String> created = request("POST", "/folders",
        "{\"folderId\": \"" + homeFolderId + "\", \"name\": \"Integration Test Folder\", "
            + "\"description\": \"Created by the folder integration test\"}",
        authHeaderUser1);
    Assertions.assertEquals(201, created.statusCode());
    String folderId = JsonMapper.MAPPER.readTree(created.body()).get("@id").asText();

    HttpResponse<String> found = request("GET", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assertions.assertEquals(200, found.statusCode());
    Assertions.assertEquals("Integration Test Folder",
        JsonMapper.MAPPER.readTree(found.body()).get("schema:name").asText());

    // Unlike create, the update endpoint reads the schema:name / schema:description keys
    HttpResponse<String> updated = request("PUT", "/folders/" + encode(folderId),
        "{\"schema:name\": \"Renamed Test Folder\", \"schema:description\": \"Updated description\"}",
        authHeaderUser1);
    Assertions.assertEquals(200, updated.statusCode());

    HttpResponse<String> deleted = request("DELETE", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assertions.assertEquals(204, deleted.statusCode());

    HttpResponse<String> gone = request("GET", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assertions.assertEquals(404, gone.statusCode());
  }

  @Test
  public void folderPermissionsAreServedToTheOwner() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId) + "/permissions", null,
        authHeaderUser1);
    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertTrue(response.body().contains("owner"));
  }

}
