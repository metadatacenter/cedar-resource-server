package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.*;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
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
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real resource HTTP and graph, with conditional document-store responses and ordered competing writes. */
class InclusionTransitionTest {
  private record Stored(ObjectNode body, long revision) { String etag() { return "\"" + revision + "\""; } }
  private static final Map<String, Stored> documents = new ConcurrentHashMap<>();
  private static HttpServer artifactServer;
  private static ExecutorService artifactThreads;
  private static CedarConfig config;
  private static FolderServiceSession folders;
  private static CedarFolderId home;
  private static String authorization;
  private static volatile String mode, sourceId, targetId;
  private static final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
  private static final HttpClient client = HttpClient.newHttpClient();
  private static final DropwizardTestSupport<ResourceServerConfiguration> server =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));
  @BeforeAll
  static void start() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    artifactThreads = Executors.newCachedThreadPool();
    artifactServer.setExecutor(artifactThreads);
    artifactServer.createContext("/", InclusionTransitionTest::handleArtifact);
    artifactServer.start();
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0", "CEDAR_RESOURCE_ADMIN_PORT", "0", "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1", "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(artifactServer.getAddress().getPort()),
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1", "CEDAR_OPENSEARCH_REST_PORT", "1"));
    server.before();
    config = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(config);
    EmbeddedCedarNeo4j.seed(config);
    authorization = TestAuthUtil.getTestUser1AuthHeader(config);
    folders = CedarDataServices.getInstance().getFolderServiceSession(
        CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(config)));
    home = folders.findHomeFolderOf().getResourceId();
    AbstractResourceServerResource.injectServices(new NoOpNodeIndexingService(config),
        new IndexUtils(config).getNodeSearchingService(), new SearchPermissionEnqueueService(config),
        new ValuerecommenderReindexQueueService(config.getCacheConfig().getPersistent()));
  }

  @AfterAll
  static void stop() {
    server.after();
    artifactServer.stop(0);
    artifactThreads.shutdownNow();
  }


  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"published", "edited", "instance", "normal"})
  void propagationRespectsChangesAfterItsGraphPreflight(String change) throws Exception {
    mode = change;
    writes.set(0);
    sourceId = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.ELEMENT);
    targetId = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    var oldElement = org.metadatacenter.artifacts.model.core.ElementSchemaArtifact.builder()
        .withName("Included element").withJsonLdId(URI.create(sourceId)).build();
    var newElement = org.metadatacenter.artifacts.model.core.ElementSchemaArtifact.builder()
        .withName("Included element").withJsonLdId(URI.create(sourceId))
        .withFieldSchema(org.metadatacenter.artifacts.model.core.TextField.builder().withName("New field").build()).build();
    var renderer = new org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer();
    var target = renderer.renderTemplateSchemaArtifact(org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact.builder()
        .withName("Target").withJsonLdId(URI.create(targetId)).withElementSchema(oldElement).build());
    documents.put(sourceId, new Stored(renderer.renderElementSchemaArtifact(newElement), 1));
    documents.put(targetId, new Stored(target, 1));
    var elementNode = new org.metadatacenter.model.folderserver.basic.FolderServerElement();
    elementNode.setId(sourceId); elementNode.setName("Source " + sourceId);
    elementNode.setVersion("0.0.1"); elementNode.setPublicationStatus("bibo:draft");
    assertNotNull(folders.createResourceAsChildOfId(elementNode, home));
    var targetNode = new FolderServerTemplate();
    targetNode.setId(targetId); targetNode.setName("Target " + targetId);
    targetNode.setVersion("0.0.1"); targetNode.setPublicationStatus("bibo:draft");
    assertNotNull(folders.createResourceAsChildOfId(targetNode, home));
    assertTrue(CedarDataServices.getInstance().getInclusionSubgraphServiceSession(
        CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(config)))
        .updateInclusionArcs(targetNode.getResourceId(), java.util.List.of(sourceId)));
    String body = "{\"@id\":\"" + sourceId + "\",\"templates\":{\"" + targetId + "\":{\"operation\":\"update\"}}}";
    var preview = post("/command/inclusions-subgraph-preview", body);
    assertEquals(200, preview.statusCode(), preview.body());
    var result = post("/command/inclusions-subgraph-update", body);
    if (change.equals("normal")) {
      assertEquals(200, result.statusCode(), result.body());
      assertEquals(1, writes.get());
      assertTrue(documents.get(targetId).body().toString().contains("New field"));
    } else {
      assertTrue(result.statusCode() >= 400, result.body());
      assertEquals(0, writes.get(), "a later publish, edit or first instance must not be overwritten");
      assertFalse(documents.get(targetId).body().toString().contains("New field"));
      if (change.equals("edited")) assertEquals("Concurrent edit", documents.get(targetId).body().path("schema:name").asText());
      if (change.equals("published")) assertEquals("bibo:published", documents.get(targetId).body().path("bibo:status").asText());
    }
  }

  private static HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.getLocalPort() + path))
        .timeout(Duration.ofSeconds(20)).header("Authorization", authorization).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static void handleArtifact(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String id = config.getLinkedDataUtil().resolveResourceId(
        CedarResourceType.forPrefix(path.split("/")[1]), path.substring(path.indexOf('/', 1) + 1).replaceFirst("/inclusion$", ""));
    Stored stored = documents.get(id);
    if (stored == null) { respond(exchange, 404, null); return; }
    if (exchange.getRequestMethod().equals("GET")) {
      // Publication commits its document after the graph admission check, before the target read.
      if (id.equals(sourceId) && mode.equals("published")) {
        Stored before = documents.get(targetId);
        documents.put(targetId, new Stored(before.body().deepCopy().put("bibo:status", "bibo:published"), before.revision() + 1));
      }
      if (id.equals(targetId) && mode.equals("edited")) {
        documents.put(targetId, new Stored(stored.body().deepCopy().put("schema:name", "Concurrent edit"), stored.revision() + 1));
      }
      respond(exchange, 200, stored);
      return;
    }
    if (exchange.getRequestMethod().equals("PUT")) {
      // A first instance is committed after the resource server's count, before the content write.
      // The protected artifact endpoint must enforce this condition at the storage boundary.
      if (mode.equals("instance") && path.endsWith("/inclusion")) { respond(exchange, 412, null); return; }
      if (!stored.etag().equals(exchange.getRequestHeaders().getFirst("If-Match"))) { respond(exchange, 412, null); return; }
      ObjectNode body = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody());
      Stored replacement = new Stored(body, stored.revision() + 1);
      if (!documents.replace(id, stored, replacement)) { respond(exchange, 412, null); return; }
      writes.incrementAndGet();
      respond(exchange, 200, replacement);
      return;
    }
    respond(exchange, 405, null);
  }
  private static void respond(HttpExchange exchange, int status, Stored stored) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (stored != null) exchange.getResponseHeaders().set("ETag", stored.etag());
    byte[] bytes = (stored == null ? "{}" : stored.body().toString()).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
