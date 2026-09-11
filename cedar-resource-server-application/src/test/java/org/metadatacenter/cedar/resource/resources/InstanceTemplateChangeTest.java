package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * An instance moved from one template to another, through the resource server.
 *
 * <p>The template an instance is based on lives in two places: the stored document says it in
 * {@code schema:isBasedOn}, and the graph node carries it as the edge every count, listing and
 * recommender read. An edit that changes the document and leaves the node alone is reported as a
 * success, and afterwards the two stores name different templates.
 *
 * <p>The artifact server is a stub on an OS-assigned port and the graph is embedded, so the graph is
 * the only side under test.
 */
public class InstanceTemplateChangeTest {

  private static HttpServer artifactServer;
  private static volatile ObjectNode storedArtifact;

  static {
    try {
      artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub artifact server", e);
    }
    artifactServer.createContext("/", InstanceTemplateChangeTest::handleArtifactRequest);
    artifactServer.start();

    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(artifactServer.getAddress().getPort()),
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1",
        "CEDAR_OPENSEARCH_REST_PORT", "1"));
  }

  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static String authHeader;
  private static CedarFolderId homeFolderId;
  private static FolderServiceSession folderSession;
  private static URI firstTemplateId;
  private static URI secondTemplateId;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig =
        CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    CedarRequestContext userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    homeFolderId = folderSession.findHomeFolderOf().getResourceId();
    firstTemplateId = URI.create(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    secondTemplateId = URI.create(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    artifactServer.stop(0);
  }

  @Test
  public void changingTheTemplateOfAnInstanceMovesItsGraphRecordToo() throws Exception {
    HttpResponse<String> created = CLIENT.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/template-instances?folder_id="
                + URLEncoder.encode(homeFolderId.getId(), StandardCharsets.UTF_8)))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(instanceBody(firstTemplateId)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(201, created.statusCode(), created.body());

    String instanceId = JsonMapper.STRICT_MAPPER.readTree(created.body()).path("@id").asText();
    Assertions.assertEquals(firstTemplateId.toString(), storedTemplateOf(instanceId),
        "the created instance was not recorded against the template it named");

    HttpResponse<String> updated = CLIENT.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/template-instances/"
                + URLEncoder.encode(instanceId, StandardCharsets.UTF_8)))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .header("If-Match", created.headers().firstValue("ETag").orElseThrow())
            .PUT(HttpRequest.BodyPublishers.ofString(instanceBody(secondTemplateId, instanceId)))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, updated.statusCode(), updated.body());
    Assertions.assertEquals(secondTemplateId.toString(),
        storedArtifact.path("schema:isBasedOn").asText(),
        "the artifact server was not sent the new template");
    Assertions.assertEquals(secondTemplateId.toString(), storedTemplateOf(instanceId),
        "the graph record still names the template the instance was moved away from");
  }

  private static String storedTemplateOf(String instanceId) {
    FolderServerArtifact artifact =
        folderSession.findArtifactById(CedarArtifactId.build(instanceId, CedarResourceType.INSTANCE));
    Assertions.assertInstanceOf(FolderServerInstance.class, artifact, instanceId);
    return ((FolderServerInstance) artifact).getIsBasedOn().getId();
  }

  private static String instanceBody(URI templateId) {
    return instanceBody(templateId, null);
  }

  private static String instanceBody(URI templateId, String instanceId) {
    TemplateInstanceArtifact.Builder builder = TemplateInstanceArtifact.builder()
        .withName("Instance under a template change")
        .withIsBasedOn(templateId);
    if (instanceId != null) {
      builder.withJsonLdId(URI.create(instanceId));
    }
    return new JsonArtifactRenderer().renderTemplateInstanceArtifact(builder.build()).toString();
  }

  /** The stub stores what it is given and answers as the artifact server does. */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();

    if ("POST".equals(method)) {
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      created.put("@id", "https://repo.metadatacenter.orgx/template-instances/" + java.util.UUID.randomUUID());
      storedArtifact = created;
      send(exchange, 201, created, created.get("@id").asText());
      return;
    }
    if ("PUT".equals(method)) {
      storedArtifact = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      send(exchange, 200, storedArtifact, null);
      return;
    }
    if ("GET".equals(method) && storedArtifact != null) {
      send(exchange, 200, storedArtifact, null);
      return;
    }
    exchange.sendResponseHeaders(404, -1);
    exchange.close();
  }

  private static void send(HttpExchange exchange, int status, JsonNode body, String location) throws IOException {
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("ETag", "\"stub-artifact-etag\"");
    if (location != null) {
      exchange.getResponseHeaders().set("Location", location);
    }
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
