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
import org.metadatacenter.server.neo4j.NodeLabel;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every conditional write the resource server makes to the graph alone, with every kind of If-Match,
 * on a folder or category that exists and on one that has been deleted.
 *
 * <p>The rule the table holds every write to is the one the artifact and group servers answer by.
 * Without If-Match, an existing target answers 428 and a missing one 404. A current tag succeeds, as
 * does {@code *}, a list holding the current tag and the current tag with a representation suffix,
 * and a stale, weak or malformed tag answers 412. A conditional update to a target that has gone
 * answers 412, since what the caller read has gone; a delete, a change of permissions and a change of
 * visibility answer 404. Publishing and drafting go through the artifact server, whose answers
 * {@code ArtifactServerAnswerMatrixTest} holds.
 */
public class ConditionalWriteMatrixTest {

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1",
        "CEDAR_OPENSEARCH_REST_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static String adminAuthHeader;
  private static String userAuthHeader;
  private static String homeFolderId;
  private static String rootCategoryId;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    adminAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
    userAuthHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    CedarRequestContext adminContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getAdminUser(cedarConfig));
    var adminSession = CedarDataServices.getInstance().getAdminServiceSession(adminContext);
    adminSession.backfillFolderParentIds();
    adminSession.createUniqueConstraint(NodeLabel.FOLDER, List.of(NodeProperty.PARENT_FOLDER_ID, NodeProperty.NAME_LOWER));
    adminSession.createUniqueConstraint(NodeLabel.CATEGORY,
        List.of(NodeProperty.PARENT_CATEGORY_ID, NodeProperty.NAME_LOWER));
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    CedarRequestContext userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    homeFolderId = CedarDataServices.getInstance().getFolderServiceSession(userContext).findHomeFolderOf().getId();
    HttpResponse<String> root = send("GET", "/categories/root", null, null, adminAuthHeader);
    Assertions.assertEquals(200, root.statusCode(), root.body());
    rootCategoryId = JsonMapper.STRICT_MAPPER.readTree(root.body()).get("@id").asText();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  /** What a write sends, given its target's identifier while the target still exists. */
  private interface Body {
    String of(String id) throws Exception;
  }

  /**
   * A conditional write: what it targets, where it goes, what it sends, where its current tag is read,
   * its success, and whether a conditional write to a target that has gone is a failed precondition.
   */
  private record Write(String name, boolean category, String method, Function<String, String> path, Body body,
                       Function<String, String> tagPath, int success, boolean updatesInPlace) {}

  private static final List<Write> WRITES = List.of(
      new Write("rename a folder", false, "PUT", id -> "/folders/" + encode(id),
          id -> "{\"schema:name\": \"Renamed " + UUID.randomUUID() + "\", \"schema:description\": \"renamed\"}",
          id -> "/folders/" + encode(id), 200, true),
      new Write("delete a folder", false, "DELETE", id -> "/folders/" + encode(id), id -> null,
          id -> "/folders/" + encode(id), 204, false),
      new Write("replace a folder's permissions", false, "PUT", id -> "/folders/" + encode(id) + "/permissions",
          id -> "{\"userPermissions\": [], \"groupPermissions\": []}",
          id -> "/folders/" + encode(id) + "/permissions", 200, false),
      new Write("open a folder", false, "POST", id -> "/command/make-folder-open", id -> "{\"@id\":\"" + id + "\"}",
          id -> "/folders/" + encode(id), 200, false),
      new Write("close a folder", false, "POST", id -> "/command/make-folder-not-open",
          id -> "{\"@id\":\"" + id + "\"}", id -> "/folders/" + encode(id), 200, false),
      new Write("rename a category", true, "PUT", id -> "/categories/" + encode(id),
          id -> "{\"schema:name\": \"Renamed " + UUID.randomUUID() + "\", \"schema:description\": \"renamed\"}",
          id -> "/categories/" + encode(id), 200, true),
      new Write("delete a category", true, "DELETE", id -> "/categories/" + encode(id), id -> null,
          id -> "/categories/" + encode(id), 204, false));

  /** An If-Match value, from the target's current tag. */
  private record Tag(String name, Function<String, String> value, boolean matches) {}

  private static final List<Tag> TAGS = List.of(
      new Tag("absent", current -> null, false),
      new Tag("blank", current -> " ", false),
      new Tag("current", current -> current, true),
      new Tag("any", current -> "*", true),
      new Tag("a list holding the current tag", current -> "\"999\", " + current, true),
      new Tag("the current tag with a representation suffix",
          current -> current.substring(0, current.length() - 1) + "-yaml\"", true),
      new Tag("stale", current -> "\"999\"", false),
      new Tag("weak", current -> "W/" + current, false),
      new Tag("malformed", current -> "not-a-tag", false),
      new Tag("a list without the current tag", current -> "\"998\", \"999\"", false));

  private static boolean absent(Tag tag) {
    return tag.name().equals("absent") || tag.name().equals("blank");
  }

  private static int expected(Write write, Tag tag, boolean exists) {
    if (exists)
      return absent(tag) ? 428 : tag.matches() ? write.success() : 412;
    // An unconditional PUT to a missing folder creates one with that identifier, as an artifact's
    // does, and a rename body names no identifier to create it with.
    if (absent(tag) && write.name().equals("rename a folder"))
      return 400;
    return absent(tag) || !write.updatesInPlace() ? 404 : 412;
  }

  @Test
  public void everyConditionalWriteAnswersEveryIfMatchByTheSameRule() throws Exception {
    List<String> differences = new ArrayList<>();
    for (Write write : WRITES) {
      for (Tag tag : TAGS) {
        for (boolean exists : List.of(true, false)) {
          // Categories are an administrator's to write, and folders their owner's.
          String auth = write.category() ? adminAuthHeader : userAuthHeader;
          String id = write.category() ? createCategory() : createFolder();
          String current = tagOf(write.tagPath().apply(id), auth);
          String body = write.body().of(id);
          if (!exists) {
            String target = (write.category() ? "/categories/" : "/folders/") + encode(id);
            HttpResponse<String> deleted = send("DELETE", target, null, tagOf(target, auth), auth);
            Assertions.assertEquals(204, deleted.statusCode(), deleted.body());
          }
          HttpResponse<String> answer = send(write.method(), write.path().apply(id), body, tag.value().apply(current),
              auth);
          int expected = expected(write, tag, exists);
          if (answer.statusCode() != expected)
            differences.add(write.name() + " / " + tag.name() + " / " + (exists ? "exists" : "deleted") + ": "
                + answer.statusCode() + " where " + expected);
        }
      }
    }
    Assertions.assertEquals(List.of(), differences);
  }

  private static String createFolder() throws Exception {
    HttpResponse<String> created = send("POST", "/folders", JsonMapper.STRICT_MAPPER.writeValueAsString(Map.of(
        "folderId", homeFolderId, "name", "Conditional " + UUID.randomUUID(), "description", "matrix")), null,
        userAuthHeader);
    Assertions.assertEquals(201, created.statusCode(), created.body());
    return JsonMapper.STRICT_MAPPER.readTree(created.body()).get("@id").asText();
  }

  private static String createCategory() throws Exception {
    HttpResponse<String> created = send("POST", "/categories", JsonMapper.STRICT_MAPPER.writeValueAsString(Map.of(
        "schema:name", "Conditional " + UUID.randomUUID(), "schema:description", "matrix",
        "parentCategoryId", rootCategoryId, "schema:identifier", UUID.randomUUID().toString())), null,
        adminAuthHeader);
    Assertions.assertEquals(201, created.statusCode(), created.body());
    JsonNode category = JsonMapper.STRICT_MAPPER.readTree(created.body());
    return category.get("@id").asText();
  }

  private static String tagOf(String path, String auth) throws Exception {
    HttpResponse<String> read = send("GET", path, null, null, auth);
    Assertions.assertEquals(200, read.statusCode(), read.body());
    return read.headers().firstValue("ETag").orElseThrow();
  }

  private static HttpResponse<String> send(String method, String path, String body, String ifMatch, String auth)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json")
        .header("Authorization", auth);
    if (ifMatch != null)
      builder.header("If-Match", ifMatch);
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String id) {
    return URLEncoder.encode(id, StandardCharsets.UTF_8);
  }
}
