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
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Integration coverage for conditional OpenView visibility changes against embedded Neo4j. */
public class CommandOpenResourceTest {

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19025",
        "CEDAR_RESOURCE_ADMIN_PORT", "19125",
        "CEDAR_RESOURCE_STOP_PORT", "19225",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));
  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String authHeader;
  private static String artifactId;
  private static String folderId;

  @BeforeAll
  static void setUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig = CedarConfig.getInstance(
        CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    CedarRequestContext context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    FolderServiceSession session = CedarDataServices.getInstance().getFolderServiceSession(context);
    CedarFolderId homeId = session.findHomeFolderOf().getResourceId();

    FolderServerFolder folder = new FolderServerFolder();
    folder.setName("Conditional Open Folder");
    folder.setDescription("Open command integration fixture");
    CedarFolderId newFolderId = CedarFolderId.build(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FOLDER));
    folderId = session.createFolderAsChildOfId(folder, homeId, newFolderId).getId();

    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName("Conditional Open Template");
    template.setDescription("Open command integration fixture");
    template.setVersion("1.0.0");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    artifactId = session.createResourceAsChildOfId(template, CedarFolderId.build(folderId)).getId();
  }

  @AfterAll
  static void tearDown() {
    SERVER.after();
  }

  @Test
  void artifactVisibilityRequiresTheDetailsEtagAndAdvancesIt() throws Exception {
    String detailsPath = "/templates/" + enc(artifactId) + "/details";
    HttpResponse<String> details = request("GET", detailsPath, null, null);
    Assertions.assertEquals(200, details.statusCode(), details.body());
    Assertions.assertEquals("\"1\"", etag(details));

    String body = "{\"@id\":\"" + artifactId + "\"}";
    Assertions.assertEquals(428,
        request("POST", "/command/make-artifact-open", body, null).statusCode());

    HttpResponse<String> opened = request("POST", "/command/make-artifact-open", body, "\"1\"");
    Assertions.assertEquals(200, opened.statusCode(), opened.body());
    Assertions.assertEquals("\"2\"", etag(opened));
    Assertions.assertTrue(JsonMapper.MAPPER.readTree(opened.body()).path("isOpen").asBoolean());

    HttpResponse<String> staleClose = request("POST", "/command/make-artifact-not-open", body, "\"1\"");
    Assertions.assertEquals(412, staleClose.statusCode(), staleClose.body());
    Assertions.assertEquals("\"2\"",
        JsonMapper.MAPPER.readTree(staleClose.body()).path("parameters").path("currentETag").asText(),
        staleClose.body());

    HttpResponse<String> closed = request("POST", "/command/make-artifact-not-open", body, "\"2\"");
    Assertions.assertEquals(200, closed.statusCode(), closed.body());
    Assertions.assertEquals("\"3\"", etag(closed));
    Assertions.assertFalse(JsonMapper.MAPPER.readTree(closed.body()).path("isOpen").asBoolean());

    HttpResponse<String> wildcard = request("POST", "/command/make-artifact-open", body, "*");
    Assertions.assertEquals(200, wildcard.statusCode(), wildcard.body());
    Assertions.assertEquals("\"4\"", etag(wildcard));
  }

  @Test
  void folderVisibilityRequiresItsFolderEtagAndRejectsOneOfTwoConcurrentWriters() throws Exception {
    String folderPath = "/folders/" + enc(folderId);
    HttpResponse<String> found = request("GET", folderPath, null, null);
    Assertions.assertEquals(200, found.statusCode(), found.body());
    String initialEtag = etag(found);
    String body = "{\"@id\":\"" + folderId + "\"}";

    Assertions.assertEquals(428,
        request("POST", "/command/make-folder-open", body, null).statusCode());

    CompletableFuture<HttpResponse<String>> open = requestAsync(
        "/command/make-folder-open", body, initialEtag);
    CompletableFuture<HttpResponse<String>> close = requestAsync(
        "/command/make-folder-not-open", body, initialEtag);
    List<Integer> statuses = List.of(open.get().statusCode(), close.get().statusCode()).stream().sorted().toList();
    Assertions.assertEquals(List.of(200, 412), statuses);

    HttpResponse<String> after = request("GET", folderPath, null, null);
    Assertions.assertEquals(200, after.statusCode(), after.body());
    Assertions.assertEquals("\"2\"", etag(after));
  }

  private static CompletableFuture<HttpResponse<String>> requestAsync(String path, String body, String ifMatch) {
    return CLIENT.sendAsync(requestBuilder("POST", path, body, ifMatch).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> request(String method, String path, String body, String ifMatch)
      throws Exception {
    return CLIENT.send(requestBuilder(method, path, body, ifMatch).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder requestBuilder(String method, String path, String body, String ifMatch) {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json");
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    return builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
  }

  private static String enc(String id) {
    return URLEncoder.encode(id, StandardCharsets.UTF_8);
  }

  private static String etag(HttpResponse<?> response) {
    return response.headers().firstValue("ETag").orElse(null);
  }
}
