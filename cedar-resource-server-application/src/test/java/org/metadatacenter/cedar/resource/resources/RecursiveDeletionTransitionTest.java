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
class RecursiveDeletionTransitionTest {
  private record Stored(ObjectNode body, long revision) { String etag() { return "\"" + revision + "\""; } }
  private static final Map<String, Stored> documents = new ConcurrentHashMap<>();
  private static HttpServer artifactServer;
  private static ExecutorService artifactThreads;
  private static CedarConfig config;
  private static FolderServiceSession folders;
  private static CedarFolderId home;
  private static String authorization;
  private static volatile String refusedId;
  private static volatile boolean refuse;
  private static final java.util.List<String> deleted = new CopyOnWriteArrayList<>();
  private static org.metadatacenter.cedar.resource.deletion.ArtifactDeletionCompletionService completion;
  private static final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
  private static final HttpClient client = HttpClient.newHttpClient();
  private static final DropwizardTestSupport<ResourceServerConfiguration> server =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));
  @BeforeAll
  static void start() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    artifactThreads = Executors.newCachedThreadPool();
    artifactServer.setExecutor(artifactThreads);
    artifactServer.createContext("/", RecursiveDeletionTransitionTest::handleArtifact);
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
    var queue = org.mockito.Mockito.mock(ValuerecommenderReindexQueueService.class);
    org.mockito.Mockito.when(queue.enqueueEventWithResult(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    completion = new org.metadatacenter.cedar.resource.deletion.ArtifactDeletionCompletionService(config,
        CedarDataServices.getInstance().getNeoUserService(), new NoOpNodeIndexingService(config), queue);
    AbstractResourceServerResource.injectArtifactDeletionCompletionService(completion);
    AbstractResourceServerResource.injectServices(new NoOpNodeIndexingService(config),
        new IndexUtils(config).getNodeSearchingService(), new SearchPermissionEnqueueService(config),
        new ValuerecommenderReindexQueueService(config.getCacheConfig().getPersistent()));
  }

  @AfterAll
  static void stop() {
    completion.close();
    server.after();
    artifactServer.stop(0);
    artifactThreads.shutdownNow();
  }

  private static CedarFolderId folder(String name, CedarFolderId parent) {
    var node = new FolderServerFolder(); node.setName(name + java.util.UUID.randomUUID());
    var id = CedarFolderId.build(config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FOLDER));
    return folders.createFolderAsChildOfId(node,parent,id).getResourceId();
  }
  private static String template(String prefix,CedarFolderId parent) {
    String id = "https://repo.metadatacenter.org/templates/" + prefix + java.util.UUID.randomUUID();
    var node = new FolderServerTemplate(); node.setId(id); node.setName(id);
    node.setVersion("0.0.1"); node.setPublicationStatus("bibo:draft");
    assertNotNull(folders.createResourceAsChildOfId(node,parent));
    documents.put(id,new Stored(JsonMapper.STRICT_MAPPER.createObjectNode().put("@id",id),1));
    return id;
  }
  private static String instance(String template,CedarFolderId parent) {
    String id = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.INSTANCE);
    var node = new org.metadatacenter.model.folderserver.basic.FolderServerInstance();
    node.setId(id); node.setName(id); node.setIsBasedOn(org.metadatacenter.id.CedarTemplateId.build(template));
    assertNotNull(folders.createResourceAsChildOfId(node,parent));
    documents.put(id,new Stored(JsonMapper.STRICT_MAPPER.createObjectNode().put("@id",id).put("schema:isBasedOn",template),1));
    return id;
  }
  private static HttpResponse<String> call(CedarFolderId root,String token) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getLocalPort() + "/folders/"
        + URLEncoder.encode(root.getId(),StandardCharsets.UTF_8) + "/deletion"))
        .timeout(Duration.ofSeconds(25)).header("Authorization",authorization).header("Content-Type","application/json");
    if (token == null) builder.GET();
    else builder.POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}"));
    return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
  }
  private static com.fasterxml.jackson.databind.JsonNode plan(CedarFolderId root) throws Exception {
    var reply = call(root,null); assertEquals(200,reply.statusCode(),reply.body());
    return JsonMapper.STRICT_MAPPER.readTree(reply.body());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"move-subtree", "outside-reference", "edit"})
  void aPartialDeletionRequiresFreshConfirmationAndProtectsChangedSurvivors(String change) throws Exception {
    documents.clear(); deleted.clear(); refuse = true;
    var root = folder("Deletion root ",home); var subtree = folder("Subtree ",root);
    refusedId = template("000-",folder("Stopped branch ",root)); String survivor = template("zzz-",subtree);
    String removedInstance = instance(refusedId,root);
    String token = plan(root).path("token").asText();
    var first = call(root,token);
    assertEquals(200,first.statusCode(),first.body());
    var partial = JsonMapper.STRICT_MAPPER.readTree(first.body());
    assertEquals("stopped",partial.path("status").asText());
    assertEquals(1,partial.path("deleted").path("instance").asInt());
    assertEquals(java.util.List.of(removedInstance),deleted);
    assertEquals(0,completion.getPendingCount(),"a definite refusal must not become a later background delete");
    assertNotNull(folders.findFolderById(root)); assertNotNull(folders.findFolderById(subtree));
    String added = null;
    if (change.equals("move-subtree")) {
      assertTrue(folders.moveFolder(subtree,home));
      added = template("new-",root);
    } else if (change.equals("outside-reference")) {
      added = instance(refusedId,home);
    } else {
      documents.compute(survivor,(id,old) -> new Stored(old.body().deepCopy().put("schema:name","New content"),2));
    }
    refuse = false;
    var stale = call(root,token);
    assertEquals(409,stale.statusCode(),stale.body());
    assertEquals("changed",JsonMapper.STRICT_MAPPER.readTree(stale.body()).path("status").asText());
    assertEquals(java.util.List.of(removedInstance),deleted,"stale confirmation must delete nothing more");
    var fresh = plan(root); var retry = call(root,fresh.path("token").asText());
    if (change.equals("outside-reference")) {
      assertFalse(fresh.path("allowed").asBoolean()); assertEquals(409,retry.statusCode(),retry.body());
      assertEquals(1,fresh.path("instancesOutside").asInt());
      assertTrue(documents.containsKey(added)); assertTrue(documents.containsKey(refusedId));
      assertEquals(java.util.List.of(removedInstance),deleted);
    } else {
      assertTrue(fresh.path("allowed").asBoolean()); assertEquals(200,retry.statusCode(),retry.body());
      assertEquals("completed",JsonMapper.STRICT_MAPPER.readTree(retry.body()).path("status").asText());
      assertNull(folders.findFolderById(root)); assertFalse(documents.containsKey(refusedId));
      if (change.equals("move-subtree")) {
        assertNotNull(folders.findFolderById(subtree)); assertTrue(documents.containsKey(survivor));
        assertFalse(deleted.contains(survivor)); assertTrue(deleted.contains(added));
      } else { assertFalse(documents.containsKey(survivor)); }
      assertEquals(deleted.size(),new java.util.HashSet<>(deleted).size(),"retry must not repeat successful deletions");
    }
  }
  private static void handleArtifact(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/templates/deletion-references")) {
      var references = JsonMapper.STRICT_MAPPER.createObjectNode();
      for (var template : JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody())) {
        var array = references.putArray(template.asText());
        documents.forEach((id,value) -> {
          if (template.asText().equals(value.body().path("schema:isBasedOn").asText())) array.add(id);
        });
      }
      respond(exchange,200,new Stored(references,1)); return;
    }
    String id = path.substring(path.indexOf('/',1)+1); Stored stored = documents.get(id);
    if (stored == null) { respond(exchange,404,null); return; }
    if (exchange.getRequestMethod().equals("GET")) { respond(exchange,200,stored); return; }
    if (exchange.getRequestMethod().equals("DELETE")) {
      if ((refuse && id.equals(refusedId)) || !stored.etag().equals(exchange.getRequestHeaders().getFirst("If-Match"))) {
        respond(exchange,412,null); return;
      }
      documents.remove(id); deleted.add(id); respond(exchange,204,null); return;
    }
    respond(exchange,405,null);
  }
  private static void respond(HttpExchange exchange,int status,Stored stored) throws IOException {
    exchange.getResponseHeaders().set("Content-Type","application/json");
    if (stored != null) exchange.getResponseHeaders().set("ETag",stored.etag());
    byte[] bytes = (stored == null ? "{}" : stored.body().toString()).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status,status == 204 ? -1 : bytes.length);
    if (status != 204) exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
