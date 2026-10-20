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
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1",
        "CEDAR_OPENSEARCH_REST_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String authHeaderUser1;
  private static String authHeaderUser2;
  private static String rootCategoryId;
  private static CedarRequestContext user1Context;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    authHeaderUser2 = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));
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
