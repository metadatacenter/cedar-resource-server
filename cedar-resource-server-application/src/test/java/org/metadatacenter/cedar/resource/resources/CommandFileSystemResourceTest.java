package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Endpoint tests for commands that copy artifacts through the artifact service. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CommandFileSystemResourceTest {

  private static final int ARTIFACT_PORT = 19317;
  private static final String SOURCE_NAME = "Named source artifact";
  private static final String COPIED_NAME = "Copy of " + SOURCE_NAME;

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19017",
        "CEDAR_RESOURCE_ADMIN_PORT", "19117",
        "CEDAR_RESOURCE_STOP_PORT", "19217",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(ARTIFACT_PORT)));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static HttpServer artifactServer;
  private static String authHeader;
  private static CedarFolderId homeFolderId;
  private static FolderServerArtifact sourceArtifact;
  private static String copiedArtifactId;
  private static String missingArtifactId;
  private static JsonNode postedArtifact;
  private static int sourceGetStatus = 200;
  private static boolean omitSourceGetBody;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", ARTIFACT_PORT), 0);
    artifactServer.createContext("/", CommandFileSystemResourceTest::handleArtifactRequest);
    artifactServer.start();

    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
    copiedArtifactId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    missingArtifactId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    CedarRequestContext userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    FolderServiceSession folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    homeFolderId = folderSession.findHomeFolderOf().getResourceId();

    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName(SOURCE_NAME);
    template.setDescription("Copy command regression fixture");
    template.setVersion("1.0.0");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    sourceArtifact = folderSession.createResourceAsChildOfId(template, homeFolderId);
    Assertions.assertNotNull(sourceArtifact);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    if (artifactServer != null) {
      artifactServer.stop(0);
    }
  }

  @Test
  @Order(0)
  public void artifactRepresentationsKeepDistinctStrongEtagsThroughTheResourceServer() throws Exception {
    String encodedId = java.net.URLEncoder.encode(sourceArtifact.getId(), StandardCharsets.UTF_8);
    URI uri = URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/" + encodedId);

    HttpResponse<String> json = CLIENT.send(HttpRequest.newBuilder(uri)
        .header("Authorization", authHeader)
        .header("Accept", "application/json")
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> yaml = CLIENT.send(HttpRequest.newBuilder(uri)
        .header("Authorization", authHeader)
        .header("Accept", "application/yaml")
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> compact = CLIENT.send(HttpRequest.newBuilder(
            URI.create(uri + "?compact=true"))
        .header("Authorization", authHeader)
        .header("Accept", "application/yaml")
        .GET().build(), HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, json.statusCode(), json.body());
    Assertions.assertEquals("\"1\"", json.headers().firstValue("ETag").orElse(null));
    Assertions.assertEquals("\"1-yaml\"", yaml.headers().firstValue("ETag").orElse(null));
    Assertions.assertEquals("\"1-yaml-compact\"", compact.headers().firstValue("ETag").orElse(null));
    Assertions.assertTrue(yaml.headers().firstValue("Vary").orElse("").contains("Accept"));
  }

  @Test
  @Order(1)
  public void copyInterpolatesTheSourceArtifactName() throws Exception {
    String body = "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\","
        + "\"nameTemplate\":\"Copy of {{name}}\"}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/copy-artifact-to-folder"))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(201, response.statusCode(), response.body());
    Assertions.assertNotNull(postedArtifact, "the copy should be posted to the artifact service");
    Assertions.assertEquals(COPIED_NAME, postedArtifact.get("schema:name").asText());
    Assertions.assertEquals(COPIED_NAME,
        JsonMapper.MAPPER.readTree(response.body()).get("schema:name").asText());
  }

  @Test
  @Order(2)
  public void sourceFetchErrorsAreReturnedWithoutPostingACopy() throws Exception {
    try {
      for (int status : new int[]{404, 500}) {
        sourceGetStatus = status;
        postedArtifact = null;

        HttpResponse<String> response = postCommand("copy-artifact-to-folder", copyBody());

        Assertions.assertEquals(status, response.statusCode(), response.body());
        Assertions.assertEquals("{\"status\":" + status + "}", response.body());
        Assertions.assertNull(postedArtifact,
            "a source fetch error must not be posted to the artifact service");
      }
    } finally {
      sourceGetStatus = 200;
    }
  }

  @Test
  @Order(3)
  public void emptySourceFetchReturnsBadGatewayWithoutPostingACopy() throws Exception {
    try {
      omitSourceGetBody = true;
      postedArtifact = null;

      HttpResponse<String> response = postCommand("copy-artifact-to-folder", copyBody());

      Assertions.assertEquals(502, response.statusCode(), response.body());
      JsonNode error = JsonMapper.MAPPER.readTree(response.body());
      Assertions.assertEquals("BAD_GATEWAY", error.path("status").asText(), response.body());
      Assertions.assertEquals("Artifact service returned an empty source artifact",
          error.path("errorMessage").asText(), response.body());
      Assertions.assertNull(postedArtifact,
          "an empty source response must not be posted to the artifact service");
    } finally {
      omitSourceGetBody = false;
    }
  }

  @Test
  @Order(4)
  public void moveMissingResourceReturnsNotFound() throws Exception {
    String body = "{\"@id\":\"" + missingArtifactId + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\"}";

    HttpResponse<String> response = postCommand("move-resource-to-folder", body);

    Assertions.assertEquals(404, response.statusCode(), response.body());
  }

  @Test
  @Order(5)
  public void renameMissingResourceReturnsNotFound() throws Exception {
    String body = "{\"@id\":\"" + missingArtifactId + "\","
        + "\"schema:name\":\"Renamed artifact\"}";

    HttpResponse<String> response = postCommand("rename-resource", body);

    Assertions.assertEquals(404, response.statusCode(), response.body());
  }

  @Test
  @Order(6)
  public void unavailableArtifactServerRemainsServiceUnavailableAcrossCopyAndDelete() throws Exception {
    artifactServer.stop(0);
    artifactServer = null;

    String copyBody = "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\","
        + "\"nameTemplate\":\"Copy of {{name}}\"}";
    HttpResponse<String> copyResponse = postCommand("copy-artifact-to-folder", copyBody);
    assertServiceUnavailable(copyResponse);

    HttpRequest deleteRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/"
            + java.net.URLEncoder.encode(sourceArtifact.getId(), StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .header("If-Match", "*")
        .DELETE()
        .build();
    HttpResponse<String> deleteResponse = CLIENT.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
    assertServiceUnavailable(deleteResponse);
  }

  private static void assertServiceUnavailable(HttpResponse<String> response) throws IOException {
    Assertions.assertEquals(503, response.statusCode(), response.body());
    JsonNode error = JsonMapper.MAPPER.readTree(response.body());
    Assertions.assertEquals("SERVICE_UNAVAILABLE", error.path("status").asText(), response.body());
    Assertions.assertEquals("Downstream service is unavailable", error.path("message").asText(), response.body());
  }

  private static HttpResponse<String> postCommand(String command, String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/" + command))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String copyBody() {
    return "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\","
        + "\"nameTemplate\":\"Copy of {{name}}\"}";
  }

  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] response;
    int status;
    if ("GET".equals(exchange.getRequestMethod())) {
      status = sourceGetStatus;
      if (omitSourceGetBody) {
        response = null;
      } else if (status == 200) {
        response = sourceDocument().toString().getBytes(StandardCharsets.UTF_8);
      } else {
        response = ("{\"status\":" + status + "}").getBytes(StandardCharsets.UTF_8);
      }
    } else if ("POST".equals(exchange.getRequestMethod())) {
      postedArtifact = JsonMapper.MAPPER.readTree(exchange.getRequestBody());
      ObjectNode created = ((ObjectNode) postedArtifact).deepCopy();
      created.put("@id", copiedArtifactId);
      status = 201;
      response = created.toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Location", copiedArtifactId);
    } else {
      exchange.getRequestBody().readAllBytes();
      status = 405;
      response = new byte[0];
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if ("GET".equals(exchange.getRequestMethod()) && status == 200) {
      exchange.getResponseHeaders().set("ETag", "\"1\"");
      exchange.getResponseHeaders().set("Vary", "Accept");
    }
    if (response == null) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
    }
    exchange.close();
  }

  private static ObjectNode sourceDocument() {
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(
        TemplateSchemaArtifact.builder()
            .withName(SOURCE_NAME)
            .withJsonLdId(URI.create(sourceArtifact.getId()))
            .build());
  }
}
