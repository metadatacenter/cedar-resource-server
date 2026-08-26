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

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19027",
        "CEDAR_RESOURCE_ADMIN_PORT", "19127",
        "CEDAR_RESOURCE_STOP_PORT", "19227",
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
  private static CedarTemplateId templateId;
  private static ObjectNode storedTemplate;
  private static volatile boolean artifactServerReturnsContent;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", ARTIFACT_PORT), 0);
    artifactServer.createContext("/", CommandVersionResourceTest::handleArtifactRequest);
    artifactServer.start();

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
  }

  @BeforeEach
  public void resetArtifactServer() {
    artifactServerReturnsContent = true;
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    if (artifactServer != null) {
      artifactServer.stop(0);
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
    exchange.getRequestBody().readAllBytes();
    if (!artifactServerReturnsContent) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }

    byte[] response = storedTemplate.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
