package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.EmbeddedCedarNeo4j;
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
    // Must run before the application rule boots the server, which reads the Neo4j env vars
    EmbeddedCedarNeo4j.startAndRedirectEnvironment();
  }

  @ClassRule
  public static final DropwizardAppRule<ResourceServerConfiguration> SERVER =
      new DropwizardAppRule<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String authHeaderUser1;
  private static String authHeaderUser2;
  private static String homeFolderId;

  @BeforeClass
  public static void oneTimeSetUp() throws Exception {
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
    homeFolderId = CedarDataServices.getFolderServiceSession(user1Context).findHomeFolderOf().getId();
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

  @Test
  public void homeFolderIsServedToItsOwner() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId), null, authHeaderUser1);
    Assert.assertEquals(200, response.statusCode());
    JsonNode folder = JsonMapper.MAPPER.readTree(response.body());
    Assert.assertTrue(folder.get("isUserHome").asBoolean());
  }

  @Test
  public void homeFolderIsHiddenFromOtherUsers() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId), null, authHeaderUser2);
    // Node-level access denial is reported as unauthorized in CEDAR, not forbidden
    Assert.assertEquals(401, response.statusCode());
    Assert.assertTrue(response.body().contains("You do not have read access to the folder"));
  }

  @Test
  public void unauthenticatedRequestIsRejected() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId), null, null);
    Assert.assertEquals(401, response.statusCode());
  }

  @Test
  public void folderLifecycleCreateReadUpdateDelete() throws Exception {
    HttpResponse<String> created = request("POST", "/folders",
        "{\"folderId\": \"" + homeFolderId + "\", \"name\": \"Integration Test Folder\", "
            + "\"description\": \"Created by the folder integration test\"}",
        authHeaderUser1);
    Assert.assertEquals(201, created.statusCode());
    String folderId = JsonMapper.MAPPER.readTree(created.body()).get("@id").asText();

    HttpResponse<String> found = request("GET", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assert.assertEquals(200, found.statusCode());
    Assert.assertEquals("Integration Test Folder",
        JsonMapper.MAPPER.readTree(found.body()).get("schema:name").asText());

    // Unlike create, the update endpoint reads the schema:name / schema:description keys
    HttpResponse<String> updated = request("PUT", "/folders/" + encode(folderId),
        "{\"schema:name\": \"Renamed Test Folder\", \"schema:description\": \"Updated description\"}",
        authHeaderUser1);
    Assert.assertEquals(200, updated.statusCode());

    HttpResponse<String> deleted = request("DELETE", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assert.assertEquals(204, deleted.statusCode());

    // The permission check runs before the existence check, so a deleted folder reads as
    // "no access" (unauthorized), not as not-found
    HttpResponse<String> gone = request("GET", "/folders/" + encode(folderId), null, authHeaderUser1);
    Assert.assertEquals(401, gone.statusCode());
  }

  @Test
  public void folderPermissionsAreServedToTheOwner() throws Exception {
    HttpResponse<String> response = request("GET", "/folders/" + encode(homeFolderId) + "/permissions", null,
        authHeaderUser1);
    Assert.assertEquals(200, response.statusCode());
    Assert.assertTrue(response.body().contains("owner"));
  }

}
