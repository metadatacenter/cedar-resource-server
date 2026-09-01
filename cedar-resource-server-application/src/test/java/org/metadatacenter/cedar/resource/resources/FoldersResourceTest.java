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
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.SiblingNameConflictException;
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
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;

/**
 * Endpoint tests for the folder resource against an in-process Neo4j. Authentication is served
 * by the in-memory user service; the graph is seeded with the global objects and test users, so
 * the folder and permission checks below run against the real Cypher layer. Indexing is a no-op,
 * since these tests run without OpenSearch.
 */
public class FoldersResourceTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars.
    // OS-assigned server ports, so the test instance never collides with a running dev server.
    // Redis is redirected to a dead port as well: queue writes are best-effort, and this
    // enforces that no endpoint under test ever depends on a live Redis
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String authHeaderUser1;
  private static String authHeaderUser2;
  private static String homeFolderId;
  private static String homeFolderPath;
  private static String user1Id;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    authHeaderUser2 = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);
    user1Id = TestAuthUtil.getTestUser1(cedarConfig).getId();

    EmbeddedCedarNeo4j.seed(cedarConfig);

    CedarRequestContext adminContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getAdminUser(cedarConfig));
    var adminSession = CedarDataServices.getInstance().getAdminServiceSession(adminContext);
    adminSession.backfillFolderParentIds();
    adminSession.createUniqueConstraint(NodeLabel.FOLDER,
        List.of(NodeProperty.PARENT_FOLDER_ID, NodeProperty.NAME_LOWER));
    adminSession.createUniqueConstraint(NodeLabel.CATEGORY,
        List.of(NodeProperty.PARENT_CATEGORY_ID, NodeProperty.NAME_LOWER));

    // These tests run without OpenSearch: indexing becomes a no-op, the queue-backed services
    // stay real (Redis), and the searching service is never exercised by the folder endpoints
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    CedarRequestContext user1Context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    var folderSession = CedarDataServices.getInstance().getFolderServiceSession(user1Context);
    var homeFolder = folderSession.findHomeFolderOf();
    folderSession.addPathAndParentId(homeFolder);
    homeFolderId = homeFolder.getId();
    homeFolderPath = homeFolder.getPath();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  private HttpResponse<String> request(String method, String path, String body, String authHeader) throws Exception {
    return request(method, path, body, authHeader, null);
  }

  private HttpResponse<String> request(String method, String path, String body, String authHeader,
                                       String ifMatch) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json");
    if (authHeader != null) {
      builder.header("Authorization", authHeader);
    }
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
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
  public void concurrentCaseVariantsCannotCreateDuplicateFolderOrCategorySiblings() throws Exception {
    CedarConfig cedarConfig = CedarConfig.getInstance(
        CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    CedarRequestContext userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));

    String folderStem = "Sibling Constraint Folder " + UUID.randomUUID();
    var folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    assertOneSuccessAndOneSiblingConflict(
        () -> folderSession.createFolderAsChildOfId(folder(folderStem),
            org.metadatacenter.id.CedarFolderId.build(homeFolderId), newFolderId()),
        () -> folderSession.createFolderAsChildOfId(folder(folderStem.toUpperCase()),
            org.metadatacenter.id.CedarFolderId.build(homeFolderId), newFolderId()));

    var categorySession = CedarDataServices.getInstance().getCategoryServiceSession(userContext);
    FolderServerCategory root = categorySession.getRootCategory();
    String categoryStem = "Sibling Constraint Category " + UUID.randomUUID();
    assertOneSuccessAndOneSiblingConflict(
        () -> categorySession.createCategory(root.getResourceId(), categoryStem, "first", ""),
        () -> categorySession.createCategory(root.getResourceId(), categoryStem.toUpperCase(), "second", ""));
  }

  private static FolderServerFolder folder(String name) {
    FolderServerFolder folder = new FolderServerFolder();
    folder.setName(name);
    folder.setDescription("concurrent sibling-name constraint test");
    return folder;
  }

  private static org.metadatacenter.id.CedarFolderId newFolderId() {
    return org.metadatacenter.id.CedarFolderId.build(
        "https://repo.metadatacenter.org/folders/" + UUID.randomUUID());
  }

  private static void assertOneSuccessAndOneSiblingConflict(Callable<?> first, Callable<?> second)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<?> a = executor.submit(gated(first, ready, start));
      Future<?> b = executor.submit(gated(second, ready, start));
      Assertions.assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();

      int successes = 0;
      int conflicts = 0;
      for (Future<?> future : List.of(a, b)) {
        try {
          Assertions.assertNotNull(future.get());
          successes++;
        } catch (ExecutionException e) {
          if (e.getCause() instanceof SiblingNameConflictException) {
            conflicts++;
          } else {
            throw e;
          }
        }
      }
      Assertions.assertEquals(1, successes);
      Assertions.assertEquals(1, conflicts);
    } finally {
      executor.shutdownNow();
    }
  }

  private static Callable<Object> gated(Callable<?> operation, CountDownLatch ready,
                                        CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await();
      return operation.call();
    };
  }

  @Test
  public void folderLifecycleCreateReadUpdateDelete() throws Exception {
    HttpResponse<String> created = request("POST", "/folders",
        "{\"folderId\": \"" + homeFolderId + "\", \"name\": \"Integration Test Folder\", "
            + "\"description\": \"Created by the folder integration test\"}",
        authHeaderUser1);
    Assertions.assertEquals(201, created.statusCode());
    Assertions.assertEquals("\"1\"", created.headers().firstValue("ETag").orElse(null));
    String folderId = JsonMapper.MAPPER.readTree(created.body()).get("@id").asText();

    HttpResponse<String> found = request("GET", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assertions.assertEquals(200, found.statusCode());
    Assertions.assertEquals("\"1\"", found.headers().firstValue("ETag").orElse(null));
    Assertions.assertEquals("Integration Test Folder",
        JsonMapper.MAPPER.readTree(found.body()).get("schema:name").asText());

    // Unlike create, the update endpoint reads the schema:name / schema:description keys
    HttpResponse<String> missingPrecondition = request("PUT", "/folders/" + encode(folderId),
        "{\"schema:name\": \"Renamed Test Folder\", \"schema:description\": \"Updated description\"}",
        authHeaderUser1);
    Assertions.assertEquals(428, missingPrecondition.statusCode(), missingPrecondition.body());

    HttpResponse<String> updated = request("PUT", "/folders/" + encode(folderId),
        "{\"schema:name\": \"Renamed Test Folder\", \"schema:description\": \"Updated description\"}",
        authHeaderUser1, "\"1\"");
    Assertions.assertEquals(200, updated.statusCode());
    Assertions.assertEquals("\"2\"", updated.headers().firstValue("ETag").orElse(null));

    HttpResponse<String> staleUpdate = request("PUT", "/folders/" + encode(folderId),
        "{\"schema:name\": \"Stale Folder Name\", \"schema:description\": \"stale\"}",
        authHeaderUser1, "\"1\"");
    Assertions.assertEquals(412, staleUpdate.statusCode(), staleUpdate.body());

    HttpResponse<String> staleDelete = request("DELETE", "/folders/" + encode(folderId), null,
        authHeaderUser1, "\"1\"");
    Assertions.assertEquals(412, staleDelete.statusCode(), staleDelete.body());

    HttpResponse<String> deleted = request("DELETE", "/folders/" + encode(folderId), null,
        authHeaderUser1, "\"2\"");
    Assertions.assertEquals(204, deleted.statusCode());

    HttpResponse<String> gone = request("GET", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assertions.assertEquals(404, gone.statusCode());

    String createWithIdBody = "{\"@id\":\"" + folderId
        + "\",\"schema:name\":\"Stale resurrection\",\"schema:description\":\"must fail\"}";
    for (String ifMatch : List.of("\"2\"", "*")) {
      HttpResponse<String> staleRecreate = request("PUT", "/folders/" + encode(folderId),
          createWithIdBody, authHeaderUser1, ifMatch);
      Assertions.assertEquals(412, staleRecreate.statusCode(), staleRecreate.body());
      HttpResponse<String> stillGone = request("GET", "/folders/" + encode(folderId), null, authHeaderUser1);
      Assertions.assertEquals(404, stillGone.statusCode());
    }
  }

  @Test
  public void concurrentFolderDeletesConvergeWithoutServerErrors() throws Exception {
    String name = "Concurrent Delete Folder " + UUID.randomUUID();
    HttpResponse<String> created = request("POST", "/folders",
        "{\"folderId\": \"" + homeFolderId + "\", \"name\": \"" + name
            + "\", \"description\": \"A sacrificial folder for repeated DELETE\"}",
        authHeaderUser1);
    Assertions.assertEquals(201, created.statusCode(), created.body());
    String folderId = JsonMapper.MAPPER.readTree(created.body()).get("@id").asText();
    String path = "/folders/" + encode(folderId);
    String etag = created.headers().firstValue("ETag").orElseThrow();

    int count = 20;
    ExecutorService executor = Executors.newFixedThreadPool(count);
    CountDownLatch ready = new CountDownLatch(count);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>(count);
    try {
      for (int i = 0; i < count; i++) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          start.await();
          return request("DELETE", path, null, authHeaderUser1, etag).statusCode();
        }));
      }
      Assertions.assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      List<Integer> statuses = new ArrayList<>(count);
      for (Future<Integer> future : futures) {
        statuses.add(future.get());
      }
      Assertions.assertEquals(1, statuses.stream().filter(status -> status == 204).count(), statuses::toString);
      Assertions.assertTrue(statuses.stream().allMatch(status -> status == 204 || status == 404 || status == 412),
          () -> "concurrent DELETE returned a non-convergent status: " + statuses);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void folderCanBeCreatedByParentPathWithoutFolderId() throws Exception {
    HttpResponse<String> created = request("POST", "/folders",
        "{\"path\": \"" + homeFolderPath + "\", \"name\": \"Path Parent Test Folder\", "
            + "\"description\": \"Created by parent path\"}",
        authHeaderUser1);
    Assertions.assertEquals(201, created.statusCode(), created.body());

    String folderId = JsonMapper.MAPPER.readTree(created.body()).get("@id").asText();
    HttpResponse<String> deleted = request("DELETE", "/folders/" + encode(folderId), null,
        authHeaderUser1, "\"1\"");
    Assertions.assertEquals(204, deleted.statusCode(), deleted.body());
  }

  @Test
  public void renameCommandUsesTheCallersFolderEtag() throws Exception {
    HttpResponse<String> created = request("POST", "/folders",
        "{\"folderId\": \"" + homeFolderId + "\", \"name\": \"Command Rename Folder\", "
            + "\"description\": \"ETag command test\"}", authHeaderUser1);
    Assertions.assertEquals(201, created.statusCode(), created.body());
    String folderId = JsonMapper.MAPPER.readTree(created.body()).get("@id").asText();
    String commandBody = "{\"@id\":\"" + folderId
        + "\",\"schema:name\":\"Command Renamed Folder\"}";

    HttpResponse<String> missing = request("POST", "/command/rename-resource", commandBody, authHeaderUser1);
    Assertions.assertEquals(428, missing.statusCode(), missing.body());

    HttpResponse<String> renamed = request("POST", "/command/rename-resource", commandBody,
        authHeaderUser1, "\"1\"");
    Assertions.assertEquals(200, renamed.statusCode(), renamed.body());

    HttpResponse<String> stale = request("POST", "/command/rename-resource",
        commandBody.replace("Command Renamed Folder", "Stale Command Name"), authHeaderUser1, "\"1\"");
    Assertions.assertEquals(412, stale.statusCode(), stale.body());

    HttpResponse<String> after = request("GET", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assertions.assertEquals("Command Renamed Folder",
        JsonMapper.MAPPER.readTree(after.body()).get("schema:name").asText());
    Assertions.assertEquals(204, request("DELETE", "/folders/" + encode(folderId), null,
        authHeaderUser1, "\"2\"").statusCode());
  }

  @Test
  public void folderPermissionsAreServedToTheOwner() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId) + "/permissions", null,
        authHeaderUser1);
    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertTrue(response.body().contains("owner"));
  }

  @Test
  public void folderPermissionReplacementUsesETags() throws Exception {
    HttpResponse<String> created = request("POST", "/folders",
        "{\"path\": \"" + homeFolderPath + "\", \"name\": \"Versioned ACL REST Folder\", "
            + "\"description\": \"ETag test\"}", authHeaderUser1);
    Assertions.assertEquals(201, created.statusCode(), created.body());
    String folderId = JsonMapper.MAPPER.readTree(created.body()).get("@id").asText();
    String permissionsPath = "/folders/" + encode(folderId) + "/permissions";
    String body = "{\"owner\":{\"@id\":\"" + user1Id
        + "\"},\"userPermissions\":[],\"groupPermissions\":[]}";

    HttpResponse<String> initial = request("GET", permissionsPath, null, authHeaderUser1);
    Assertions.assertEquals(200, initial.statusCode(), initial.body());
    Assertions.assertEquals("\"1\"", initial.headers().firstValue("ETag").orElse(null));

    HttpResponse<String> missing = request("PUT", permissionsPath, body, authHeaderUser1);
    Assertions.assertEquals(428, missing.statusCode(), missing.body());

    HttpResponse<String> updated = request("PUT", permissionsPath, body, authHeaderUser1, "\"1\"");
    Assertions.assertEquals(200, updated.statusCode(), updated.body());
    Assertions.assertEquals("\"2\"", updated.headers().firstValue("ETag").orElse(null));

    HttpResponse<String> stale = request("PUT", permissionsPath, body, authHeaderUser1, "\"1\"");
    Assertions.assertEquals(412, stale.statusCode(), stale.body());

    HttpResponse<String> wildcard = request("PUT", permissionsPath, body, authHeaderUser1, "*");
    Assertions.assertEquals(200, wildcard.statusCode(), wildcard.body());
    Assertions.assertEquals("\"3\"", wildcard.headers().firstValue("ETag").orElse(null));
  }

}
