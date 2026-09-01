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
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.util.IndexUtils;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The authenticated write path succeeding, end to end through the resource server.
 *
 * <p>Every other write test in this suite asserts a refusal: a bad body, a stale precondition, a
 * downstream server that is not there. Nothing asserted that an ordinary authenticated create or
 * update works, so the path that matters most — validate, forward to the artifact server, create the
 * graph node, index it, answer 201 with a Location and an ETag — was covered only by the
 * out-of-build e2e smoke, and any break in it would have passed this suite.
 *
 * <p>The artifact server is a stub on an OS-assigned port, the graph is embedded, and indexing is a
 * no-op, so this needs no live backend. What it pins is the resource server's own half of the
 * exchange: that it forwards, records what came back, and reports the result the way its OpenAPI
 * says it does.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TemplatesResourceWriteSuccessTest {

  private static final AtomicInteger ARTIFACT_POSTS = new AtomicInteger();
  private static final AtomicInteger ARTIFACT_PUTS = new AtomicInteger();
  private static HttpServer artifactServer;
  private static volatile ObjectNode storedArtifact;

  static {
    // The stub binds first so its port can be handed to the application, rather than both sides
    // agreeing on a number written in two places.
    try {
      artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub artifact server", e);
    }
    artifactServer.createContext("/", TemplatesResourceWriteSuccessTest::handleArtifactRequest);
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

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static String authHeader;
  private static CedarFolderId homeFolderId;
  private static String createdId;
  private static String createdEtag;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
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
    homeFolderId = folderSession.findHomeFolderOf().getResourceId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    artifactServer.stop(0);
  }

  /** The stub stores what it is given and answers as the artifact server does. */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();

    if ("POST".equals(method)) {
      ARTIFACT_POSTS.incrementAndGet();
      ObjectNode created = (ObjectNode) JsonMapper.MAPPER.readTree(requestBody);
      created.put("@id", "https://repo.metadatacenter.orgx/templates/" + java.util.UUID.randomUUID());
      storedArtifact = created;
      send(exchange, 201, created, created.get("@id").asText());
      return;
    }
    if ("PUT".equals(method)) {
      ARTIFACT_PUTS.incrementAndGet();
      storedArtifact = (ObjectNode) JsonMapper.MAPPER.readTree(requestBody);
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

  private static void send(HttpExchange exchange, int status, ObjectNode body, String location) throws IOException {
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    // The artifact server is the reference resource for this exchange and answers every read and
    // write with an ETag; the resource server carries it through. A stub that omitted it would be
    // testing a downstream server CEDAR does not have.
    exchange.getResponseHeaders().set("ETag", "\"stub-artifact-etag-" + ARTIFACT_PUTS.get() + "\"");
    if (location != null) {
      exchange.getResponseHeaders().set("Location", location);
    }
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  private static String templateBody(String name) {
    return "{"
        + "\"@id\":null,"
        + "\"@type\":\"https://schema.metadatacenter.org/core/Template\","
        + "\"schema:name\":\"" + name + "\","
        + "\"schema:description\":\"Written by the authenticated write-success test\","
        + "\"pav:version\":\"0.0.1\","
        + "\"bibo:status\":\"bibo:draft\""
        + "}";
  }

  @Test
  @Order(1)
  public void anAuthenticatedCreateAnswers201WithALocationAndAnEtag() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates?folder_id="
            + URLEncoder.encode(homeFolderId.getId(), StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(templateBody("Write success fixture")))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(201, response.statusCode(), response.body());
    Assertions.assertEquals(1, ARTIFACT_POSTS.get(), "the create must reach the artifact server exactly once");

    String location = response.headers().firstValue("Location").orElse(null);
    Assertions.assertNotNull(location, "a create must answer with a Location");
    createdEtag = response.headers().firstValue("ETag").orElse(null);
    Assertions.assertNotNull(createdEtag, "a create must answer with an ETag");

    JsonNode body = JsonMapper.MAPPER.readTree(response.body());
    createdId = body.path("@id").asText();
    Assertions.assertFalse(createdId.isEmpty(), response.body());
    Assertions.assertEquals("Write success fixture", body.path("schema:name").asText(), response.body());
  }

  /**
   * The graph node exists, which is the half of the create the artifact server cannot report. A
   * response of 201 with the artifact stored and no node would leave an artifact nothing can reach.
   */
  @Test
  @Order(2)
  public void theCreatedTemplateIsReadableThroughTheResourceServer() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/"
            + URLEncoder.encode(createdId, StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .GET()
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    Assertions.assertEquals(createdId, JsonMapper.MAPPER.readTree(response.body()).path("@id").asText());
  }

  @Test
  @Order(3)
  public void anAuthenticatedUpdateAnswers200AndReachesTheArtifactServer() throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/"
            + URLEncoder.encode(createdId, StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .header("If-Match", createdEtag)
        .PUT(HttpRequest.BodyPublishers.ofString(templateBody("Write success fixture, renamed")))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    Assertions.assertEquals(1, ARTIFACT_PUTS.get(), "the update must reach the artifact server exactly once");
    Assertions.assertEquals("Write success fixture, renamed",
        JsonMapper.MAPPER.readTree(response.body()).path("schema:name").asText(), response.body());
  }
}
