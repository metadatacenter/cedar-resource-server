package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ClassScopedSearchPermissionServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.util.IndexUtils;
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

/**
 * Categories classify artifacts in a tree under one root, which every signed-in user may read. The tree can be read
 * whole, a page of categories at a time, or one category at a time. A new category is created under a parent the
 * user may add to, with a name unique among its siblings.
 */
@Tag("integration")
public class CategoriesResourceTest {

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1",
        "CEDAR_OPENSEARCH_REST_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String authHeaderAdmin;
  private static String user2Id;
  private static String authHeaderUser1;
  private static String authHeaderUser2;
  private static String rootCategoryId;
  private static CedarRequestContext user1Context;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeaderAdmin = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
    user2Id = TestAuthUtil.getTestUser2(cedarConfig).getId();
    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    authHeaderUser2 = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        ClassScopedSearchPermissionServices.open(cedarConfig));
    user1Context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    rootCategoryId = CedarDataServices.getInstance().getCategoryServiceSession(user1Context).getRootCategory().getId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @Test
  public void theTreeIsReadWholeOrAPageAtATime() throws Exception {
    String stem = "Category " + UUID.randomUUID();
    String parent = ownCategory(stem);
    String childA = create(stem + " A", parent);
    String childB = create(stem + " B", parent);

    JsonNode tree = read(get("/categories/tree", authHeaderUser2), 200);
    Assertions.assertEquals(rootCategoryId, tree.path("@id").asText(), tree.toString());
    JsonNode created = child(tree, parent);
    Assertions.assertNotNull(created, "the new category is in the tree: " + tree);
    List<String> grandchildren = new ArrayList<>();
    created.path("children").forEach(c -> grandchildren.add(c.path("@id").asText()));
    Assertions.assertEquals(List.of(childA, childB).stream().sorted().toList(), grandchildren.stream().sorted().toList());

    JsonNode first = read(get("/categories?limit=2&offset=0", authHeaderUser1), 200);
    long total = first.path("totalCount").asLong();
    Assertions.assertTrue(total >= 4, "the root and the three new categories: " + first);
    Assertions.assertEquals(2, first.path("categories").size());
    Assertions.assertEquals(2, first.path("request").path("limit").asInt());
    Assertions.assertTrue(first.path("paging").has("next"), first.path("paging").toString());
    JsonNode last = read(get("/categories?limit=2&offset=" + (total - 1), authHeaderUser1), 200);
    Assertions.assertEquals(1, last.path("categories").size(), "the last page holds what is left");
    read(get("/categories?limit=0", authHeaderUser1), 400);

    JsonNode root = read(get("/categories/root", authHeaderUser1), 200);
    Assertions.assertEquals(rootCategoryId, root.path("@id").asText());
    JsonNode one = read(get("/categories/" + encode(childA), authHeaderUser2), 200);
    Assertions.assertEquals(stem + " A", one.path("schema:name").asText(), one.toString());
  }

  @Test
  public void aCategoryPageDefaultsTo100AndAllowsAtMost500() throws Exception {
    JsonNode unlimited = read(get("/categories", authHeaderUser1), 200);
    Assertions.assertEquals(100, unlimited.path("request").path("limit").asInt());

    JsonNode widest = read(get("/categories?limit=500", authHeaderUser1), 200);
    Assertions.assertEquals(500, widest.path("request").path("limit").asInt());

    read(get("/categories?limit=501", authHeaderUser1), 400);
  }

  @Test
  public void aPageNumberParameterIsRefusedNamingIt() throws Exception {
    for (String name : List.of("page", "pageSize", "page_size")) {
      JsonNode error = read(get("/categories?" + name + "=2", authHeaderUser1), 400);
      Assertions.assertEquals("2", error.path("parameters").path(name).asText(null), name + " -> " + error);
    }
  }

  @Test
  public void aCategoryIsCreatedOnlyUnderAParentThatExistsWithANameItsSiblingsDoNotUse() throws Exception {
    String stem = "Category " + UUID.randomUUID();
    String parent = ownCategory(stem);
    create(stem + " child", parent);

    JsonNode conflict = read(post(body(stem + " child", parent)), 409);
    Assertions.assertEquals("uniqueConstraintCollision", conflict.path("errorKey").asText(), conflict.toString());
    Assertions.assertEquals(stem + " child", conflict.path("parameters").path("name").asText(), conflict.toString());
    JsonNode underRoot = read(post(body(stem + " at the root", rootCategoryId)), 403);
    Assertions.assertEquals("noWriteAccessToCategory", underRoot.path("errorKey").asText(), underRoot.toString());
    JsonNode missingParent = read(post(body(stem + " orphan",
        rootCategoryId.substring(0, rootCategoryId.lastIndexOf('/') + 1) + UUID.randomUUID())), 400);
    Assertions.assertEquals("parentCategoryNotFound", missingParent.path("errorKey").asText(), missingParent.toString());
    read(post("{\"schema:name\": \"x\"}"), 400);
  }

  @Test
  public void everyCategoryReadRequiresASignedInUser() throws Exception {
    for (String path : List.of("/categories", "/categories/tree", "/categories/root")) {
      Assertions.assertEquals(401, get(path, null).statusCode(), path);
    }
  }

  @Test
  public void categoryAliasesShareReadsRevisionsAndAuthorization() throws Exception {
    String id = ownCategory("Addressing " + UUID.randomUUID());
    String uuid = id.substring(id.lastIndexOf('/') + 1);
    for (String suffix : List.of("", "/permissions")) {
      HttpResponse<String> full = get("/categories/" + encode(id) + suffix, authHeaderUser1);
      read(full, 200);
      for (String alias : List.of(uuid, "categories/" + uuid)) {
        String path = "/categories/" + encode(alias) + suffix;
        HttpResponse<String> shortRead = get(path, authHeaderUser1);
        Assertions.assertEquals(read(full, 200), read(shortRead, 200), path);
        Assertions.assertEquals(full.headers().firstValue("ETag"), shortRead.headers().firstValue("ETag"), path);
        Assertions.assertEquals(401, get(path, null).statusCode(), path);
        read(get(path, authHeaderUser2), 200); // Categories remain shared vocabulary.
      }
    }
    String rootUuid = rootCategoryId.substring(rootCategoryId.lastIndexOf('/') + 1);
    Assertions.assertEquals(read(get("/categories/" + encode(rootCategoryId), authHeaderUser1), 200),
        read(get("/categories/" + rootUuid, authHeaderUser1), 200));
    Assertions.assertEquals(rootCategoryId, read(get("/categories/root", authHeaderUser1), 200).path("@id").asText());
    Assertions.assertEquals(rootCategoryId, read(get("/categories/tree", authHeaderUser1), 200).path("@id").asText());
    HttpResponse<String> deletion = write("DELETE", "/categories/" + rootUuid, null, authHeaderAdmin, "*");
    Assertions.assertEquals("noWriteAccessToCategory", read(deletion, 403).path("errorKey").asText());
    HttpResponse<String> fullDeletion = write("DELETE", "/categories/" + encode(rootCategoryId), null, authHeaderAdmin, "*");
    Assertions.assertEquals(deletion.statusCode(), fullDeletion.statusCode());
    Assertions.assertEquals(read(deletion, 403).path("errorKey"), read(fullDeletion, 403).path("errorKey"));
  }

  @Test
  public void categoryAliasesShareConditionalRecordWritesAndDeletion() throws Exception {
    String id = ownCategory("Addressed record " + UUID.randomUUID());
    String full = "/categories/" + encode(id);
    String shortPath = "/categories/" + id.substring(id.lastIndexOf('/') + 1);
    String etag = get(full, authHeaderUser1).headers().firstValue("ETag").orElseThrow();
    String update = JsonMapper.STRICT_MAPPER.writeValueAsString(Map.of(
        "schema:name", "Addressed rename " + UUID.randomUUID(), "schema:description", "changed"));
    read(write("PUT", shortPath, update, authHeaderUser1, null), 428);
    read(write("PUT", shortPath, update, authHeaderUser2, etag), 403);
    HttpResponse<String> updated = write("PUT", shortPath, update, authHeaderUser1, etag);
    Assertions.assertEquals(id, read(updated, 200).path("@id").asText());
    Assertions.assertEquals(updated.headers().firstValue("ETag"), get(full, authHeaderUser1).headers().firstValue("ETag"));
    read(write("PUT", full, update, authHeaderUser1, etag), 412);
    read(write("DELETE", shortPath, null, authHeaderUser2, "*"), 403);
    read(write("DELETE", shortPath, null, authHeaderUser1, null), 428);
    read(write("DELETE", shortPath, null, authHeaderUser1, etag), 412);
    Assertions.assertEquals(204, write("DELETE", shortPath, null, authHeaderUser1,
        updated.headers().firstValue("ETag").orElseThrow()).statusCode());
    read(get(full, authHeaderUser1), 404);
    read(write("PUT", full, update, authHeaderUser1, etag), 412);
  }

  @Test
  public void categoryAliasesShareAclRevisionsAndOwnership() throws Exception {
    String id = ownCategory("Addressed permissions " + UUID.randomUUID());
    String full = "/categories/" + encode(id) + "/permissions";
    String shortPath = "/categories/" + id.substring(id.lastIndexOf('/') + 1) + "/permissions";
    String typed = "/categories/" + encode("categories/" + id.substring(id.lastIndexOf('/') + 1)) + "/permissions";
    String etag = get(full, authHeaderUser1).headers().firstValue("ETag").orElseThrow();
    String grants = "{\"userPermissions\":[],\"groupPermissions\":[]}";
    read(write("PUT", shortPath, grants, authHeaderUser1, null), 428);
    read(write("PUT", shortPath, grants, authHeaderUser2, etag), 403);
    HttpResponse<String> replaced = write("PUT", shortPath, grants, authHeaderUser1, etag);
    read(replaced, 200);
    read(write("PUT", full, grants, authHeaderUser1, etag), 412);
    Assertions.assertEquals(read(replaced, 200), read(get(full, authHeaderUser1), 200));
    String transfer = JsonMapper.STRICT_MAPPER.writeValueAsString(Map.of("newOwnerId", user2Id));
    read(write("PATCH", typed, transfer, authHeaderUser1, null), 428);
    read(write("PATCH", shortPath, transfer, authHeaderUser2, "*"), 403);
    read(write("PATCH", typed, transfer, authHeaderUser1, etag), 412);
    HttpResponse<String> transferred = write("PATCH", typed, transfer, authHeaderUser1,
        replaced.headers().firstValue("ETag").orElseThrow());
    Assertions.assertEquals(user2Id, read(transferred, 200).path("owner").path("@id").asText());
    Assertions.assertEquals(read(transferred, 200), read(get(full, authHeaderUser2), 200));
    Assertions.assertEquals(transferred.headers().firstValue("ETag"), get(full, authHeaderUser2).headers().firstValue("ETag"));
    read(write("PUT", shortPath, grants, authHeaderUser1, "*"), 403);
  }

  @Test
  public void categoryPathsRejectWrongKindsAndDoNotRetargetFullIris() throws Exception {
    String uuid = UUID.randomUUID().toString();
    String wrong = "/categories/" + encode("groups/" + uuid);
    for (String suffix : List.of("", "/permissions")) {
      read(get(wrong + suffix, authHeaderUser1), 400);
      read(write("PUT", wrong + suffix, "{}", authHeaderUser1, "*"), 400);
    }
    read(write("DELETE", wrong, null, authHeaderUser1, "*"), 400);
    read(write("PATCH", wrong + "/permissions", "{}", authHeaderUser1, "*"), 400);
    read(get("/categories/" + uuid, authHeaderUser1), 404);
    String id = ownCategory("Opaque category " + UUID.randomUUID());
    read(get("/categories/" + encode(id.replace("/categories/", "/groups/")), authHeaderUser1), 404);
  }

  private static HttpResponse<String> write(String method, String path, String body, String auth, String etag)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", auth).header("Content-Type", "application/json");
    if (etag != null) builder.header("If-Match", etag);
    return CLIENT.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  /** A category directly under the root, owned by the first user, as an administrator would create it. */
  private static String ownCategory(String name) throws Exception {
    return CedarDataServices.getInstance().getCategoryServiceSession(user1Context)
        .createCategory(org.metadatacenter.id.CedarCategoryId.build(rootCategoryId), name, "Created by the category test",
            null).getId();
  }

  private static JsonNode child(JsonNode node, String id) {
    for (JsonNode c : node.path("children")) {
      if (c.path("@id").asText().equals(id)) {
        return c;
      }
    }
    return null;
  }

  private static String create(String name, String parentId) throws Exception {
    return read(post(body(name, parentId)), 201).get("@id").asText();
  }

  private static String body(String name, String parentId) throws Exception {
    return JsonMapper.STRICT_MAPPER.writeValueAsString(Map.of("schema:name", name,
        "schema:description", "Created by the category test", "parentCategoryId", parentId));
  }

  private static HttpResponse<String> post(String body) throws Exception {
    return CLIENT.send(HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/categories"))
        .header("Content-Type", "application/json")
        .header("Authorization", authHeaderUser1)
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(String path, String authHeader) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path));
    if (authHeader != null) {
      builder.header("Authorization", authHeader);
    }
    return CLIENT.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode read(HttpResponse<String> response, int status) throws Exception {
    Assertions.assertEquals(status, response.statusCode(), response.body());
    return JsonMapper.STRICT_MAPPER.readTree(response.body());
  }

  private static String encode(String id) {
    return URLEncoder.encode(id, StandardCharsets.UTF_8);
  }
}
