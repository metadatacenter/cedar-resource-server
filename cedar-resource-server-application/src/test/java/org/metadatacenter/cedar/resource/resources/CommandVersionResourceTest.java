package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Failure classification tests for template-version commands. */
public class CommandVersionResourceTest {

  private static final int ARTIFACT_PORT = 19327;
  private static final int TERMINOLOGY_PORT = 19328;
  private static final String TERMINOLOGY_VERSION_ID = "doid-version-hash";

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19027",
        "CEDAR_RESOURCE_ADMIN_PORT", "19127",
        "CEDAR_RESOURCE_STOP_PORT", "19227",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(ARTIFACT_PORT),
        "CEDAR_TERMINOLOGY_SERVER_HOST", "127.0.0.1",
        "CEDAR_TERMINOLOGY_HTTP_PORT", Integer.toString(TERMINOLOGY_PORT)));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static HttpServer artifactServer;
  private static HttpServer terminologyServer;
  private static String authHeader;
  private static CedarTemplateId templateId;
  private static CedarTemplateId publishTemplateId;
  private static ObjectNode storedTemplate;
  private static ObjectNode publishTemplateDocument;
  private static ObjectNode lastPublishedTemplate;
  private static volatile boolean artifactServerReturnsContent;
  private static volatile boolean publishingArtifact;
  private static volatile int terminologyRequests;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", ARTIFACT_PORT), 0);
    artifactServer.createContext("/", CommandVersionResourceTest::handleArtifactRequest);
    artifactServer.start();
    terminologyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", TERMINOLOGY_PORT), 0);
    terminologyServer.createContext("/", CommandVersionResourceTest::handleTerminologyRequest);
    terminologyServer.start();

    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
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
    CedarFolderId homeFolderId = folderSession.findHomeFolderOf().getResourceId();

    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName("Version check fixture");
    template.setDescription("Template with an instance, so update checking compares the models");
    template.setVersion("1.0.0");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    FolderServerArtifact createdTemplate = folderSession.createResourceAsChildOfId(template, homeFolderId);
    Assertions.assertNotNull(createdTemplate);
    templateId = CedarTemplateId.build(createdTemplate.getId());

    FolderServerTemplate publishTemplate = new FolderServerTemplate();
    publishTemplate.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    publishTemplate.setName("Freeze-on-publish fixture");
    publishTemplate.setDescription("Uses the configured terminology service when publishing");
    publishTemplate.setVersion("1.0.0");
    publishTemplate.setPublicationStatus("bibo:draft");
    publishTemplate.setLatestVersion(true);
    publishTemplate.setLatestDraftVersion(true);
    publishTemplate.setLatestPublishedVersion(false);
    FolderServerArtifact createdPublishTemplate =
        folderSession.createResourceAsChildOfId(publishTemplate, homeFolderId);
    Assertions.assertNotNull(createdPublishTemplate);
    publishTemplateId = CedarTemplateId.build(createdPublishTemplate.getId());

    FolderServerInstance instance = new FolderServerInstance();
    instance.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.INSTANCE));
    instance.setName("Version check instance");
    instance.setDescription("Forces check-update-template to compare definitions");
    instance.setIsBasedOn(templateId);
    Assertions.assertNotNull(folderSession.createResourceAsChildOfId(instance, homeFolderId));
    Assertions.assertEquals(1, folderSession.getNumberOfInstances(templateId));

    storedTemplate = new JsonArtifactRenderer().renderTemplateSchemaArtifact(
        TemplateSchemaArtifact.builder()
            .withJsonLdId(URI.create(templateId.getId()))
            .withName("Version check fixture")
            .build());
    publishTemplateDocument = storedTemplate.deepCopy();
    publishTemplateDocument.put("@id", publishTemplateId.getId());
    publishTemplateDocument.put("schema:name", "Freeze-on-publish fixture");
    publishTemplateDocument.put("pav:version", "1.0.0");
    publishTemplateDocument.put("bibo:status", "bibo:draft");
    publishTemplateDocument.putObject("_valueConstraints")
        .putArray("ontologies")
        .addObject()
        .put("acronym", "DOID");
  }

  @BeforeEach
  public void resetArtifactServer() {
    artifactServerReturnsContent = true;
    publishingArtifact = false;
    terminologyRequests = 0;
    lastPublishedTemplate = null;
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    if (artifactServer != null) {
      artifactServer.stop(0);
    }
    if (terminologyServer != null) {
      terminologyServer.stop(0);
    }
  }

  @Test
  public void malformedSubmittedTemplateIsAnInternalFailureNotNotFound() throws Exception {
    HttpResponse<String> response = checkUpdateTemplate("[]");

    Assertions.assertEquals(500, response.statusCode(), response.body());
  }

  @Test
  public void validSubmittedTemplateCanBeChecked() throws Exception {
    HttpResponse<String> response = checkUpdateTemplate(storedTemplate.toString());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    Assertions.assertTrue(JsonMapper.MAPPER.readTree(response.body()).get("canBeUpdated").asBoolean());
  }

  @Test
  public void emptyArtifactServerResponseIsNotFound() throws Exception {
    artifactServerReturnsContent = false;

    HttpResponse<String> response = checkUpdateTemplate(storedTemplate.toString());

    Assertions.assertEquals(404, response.statusCode(), response.body());
  }

  @Test
  public void publishUsesTerminologyServerFromCedarConfig() throws Exception {
    publishingArtifact = true;
    String body = "{\"@id\":\"" + publishTemplateId.getId() + "\",\"newVersion\":\"1.0.1\"}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/publish-artifact"))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    Assertions.assertEquals(1, terminologyRequests);
    Assertions.assertNotNull(lastPublishedTemplate);
    Assertions.assertEquals(TERMINOLOGY_VERSION_ID,
        lastPublishedTemplate.path("_valueConstraints").path("ontologies").path(0)
            .path("version").path("id").asText());
  }

  private static HttpResponse<String> checkUpdateTemplate(String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/check-update-template/"
            + URLEncoder.encode(templateId.getId(), StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    if (!artifactServerReturnsContent) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }

    ObjectNode responseDocument;
    if ("PUT".equals(exchange.getRequestMethod())) {
      lastPublishedTemplate = (ObjectNode) JsonMapper.MAPPER.readTree(requestBody);
      responseDocument = lastPublishedTemplate;
    } else {
      responseDocument = publishingArtifact ? publishTemplateDocument : storedTemplate;
      exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
    }
    byte[] response = responseDocument.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private static void handleTerminologyRequest(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    terminologyRequests++;
    byte[] response = ("{\"id\":\"" + TERMINOLOGY_VERSION_ID + "\"}")
        .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
