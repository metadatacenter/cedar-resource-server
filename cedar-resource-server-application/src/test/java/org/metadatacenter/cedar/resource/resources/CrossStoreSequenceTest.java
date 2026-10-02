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

/** Real resource HTTP and embedded graph, with a controllable content-store HTTP boundary. */
class CrossStoreSequenceTest {
  private record Stored(ObjectNode body, long revision) { String etag() { return "\"" + revision + "\""; } }
  private static final Map<String, Stored> documents = new ConcurrentHashMap<>();
  private static HttpServer artifactServer;
  private static ExecutorService artifactThreads;
  private static CedarConfig config;
  private static FolderServiceSession folders;
  private static CedarFolderId home;
  private static String authorization;
  private static volatile String delayedName;
  private static volatile CountDownLatch firstWriteStored;
  private static volatile CountDownLatch releaseFirstReply;
  private static volatile Runnable afterPost;
  private static volatile String copiedId;
  private static volatile boolean refuseCleanup;
  private static volatile boolean compressedCreationValidator;
  private static final HttpClient client = HttpClient.newHttpClient();
  private static final DropwizardTestSupport<ResourceServerConfiguration> server =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  @BeforeAll
  static void start() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    artifactThreads = Executors.newCachedThreadPool();
    artifactServer.setExecutor(artifactThreads);
    artifactServer.createContext("/", CrossStoreSequenceTest::handleArtifact);
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

  @AfterEach
  void clearHooks() {
    if (releaseFirstReply != null) releaseFirstReply.countDown();
    delayedName = null;
    afterPost = null;
    refuseCleanup = false;
    compressedCreationValidator = false;
  }

  @AfterAll
  static void stop() {
    server.after();
    artifactServer.stop(0);
    artifactThreads.shutdownNow();
  }

  @Test
  void aDelayedEarlierWriteCannotReplaceTheGraphOfALaterSuccessfulWrite() throws Exception {
    String id = createTemplate("Before overlapping writes");
    ObjectNode first = documents.get(id).body().deepCopy();
    first.put("schema:name", "First writer");
    ObjectNode second = first.deepCopy();
    second.put("schema:name", "Second writer");
    delayedName = "First writer";
    firstWriteStored = new CountDownLatch(1);
    releaseFirstReply = new CountDownLatch(1);
    CompletableFuture<HttpResponse<String>> firstResponse = client.sendAsync(
        request("PUT", "/templates/" + enc(id), first.toString(), "\"1\""), HttpResponse.BodyHandlers.ofString());
    try {
      assertTrue(firstWriteStored.await(10, TimeUnit.SECONDS), "first content update must be committed before the second read");
      HttpResponse<String> read = send("GET", "/templates/" + enc(id), null, null);
      assertEquals(200, read.statusCode(), read.body());
      assertEquals("First writer", JsonMapper.STRICT_MAPPER.readTree(read.body()).path("schema:name").asText());
      HttpResponse<String> newer = send("PUT", "/templates/" + enc(id), second.toString(),
          read.headers().firstValue("ETag").orElseThrow());
      assertEquals(200, newer.statusCode(), newer.body());
      assertEquals("Second writer", folders.findArtifactById(artifactId(id)).getName());
    } finally {
      releaseFirstReply.countDown();
    }
    HttpResponse<String> earlier = firstResponse.get(10, TimeUnit.SECONDS);
    assertEquals(200, earlier.statusCode(), earlier.body());
    assertEquals("Second writer", documents.get(id).body().path("schema:name").asText());
    assertEquals("Second writer", folders.findArtifactById(artifactId(id)).getName(),
        "the earlier HTTP reply must not project stale metadata over the already committed successor");
  }

  @Test
  void aCommittedSaveKeepsItsDerivedWorkWhenSearchIsUnavailable() throws Exception {
    String id = createTemplate("Before index outage");
    var index = org.mockito.Mockito.mock(org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService.class);
    org.mockito.Mockito.when(index.indexDocumentForProjection(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new org.metadatacenter.exception.CedarProcessingException("search unavailable"));
    AbstractResourceServerResource.injectServices(index,
        new IndexUtils(config).getNodeSearchingService(), new SearchPermissionEnqueueService(config),
        new ValuerecommenderReindexQueueService(config.getCacheConfig().getPersistent()));
    try {
      ObjectNode content = documents.get(id).body().deepCopy().put("schema:name", "Committed during outage");
      var saved = send("PUT", "/templates/" + enc(id), content.toString(), "\"1\"");
      assertEquals(200, saved.statusCode(), saved.body());
      assertEquals("Committed during outage", folders.findArtifactById(artifactId(id)).getName());
      assertEquals("Committed during outage", documents.get(id).body().path("schema:name").asText());
      var neo = org.metadatacenter.server.neo4j.Neo4jConfig.fromCedarConfig(config);
      try (var driver = org.neo4j.driver.GraphDatabase.driver(neo.getUri(),
          org.neo4j.driver.AuthTokens.basic(neo.getUserName(), neo.getUserPassword())); var session = driver.session()) {
        var job = session.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN j.content AS content",
            Map.of("id", id)).single();
        assertEquals("Committed during outage", JsonMapper.STRICT_MAPPER.readTree(job.get("content").asString())
            .path("schema:name").asText());
        assertEquals(0, session.run("MATCH (j:CedarArtifactRestoreOutbox {resourceId:$id}) RETURN count(j) AS n",
            Map.of("id", id)).single().get("n").asInt(), "committed graph work must not be compensated");
        var next = content.deepCopy().put("schema:name", "Successor during outage");
        assertEquals(200, send("PUT", "/templates/" + enc(id), next.toString(), "\"2\"").statusCode());
        var successor = session.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN j.content AS content",
            Map.of("id", id)).single();
        assertEquals("Successor during outage", JsonMapper.STRICT_MAPPER.readTree(successor.get("content").asString())
            .path("schema:name").asText(), "recovery must retain the last committed document");
      }
    } finally {
      AbstractResourceServerResource.injectServices(new NoOpNodeIndexingService(config),
          new IndexUtils(config).getNodeSearchingService(), new SearchPermissionEnqueueService(config),
          new ValuerecommenderReindexQueueService(config.getCacheConfig().getPersistent()));
    }
  }

  @Test
  void aCopyCleansUpItsDocumentWhenTheDestinationDisappears() throws Exception {
    String source = createTemplate("Copy source");
    FolderServerFolder target = new FolderServerFolder();
    target.setName("Disposable copy destination");
    target.setDescription("Removed after authorization and content creation");
    CedarFolderId targetId = config.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    assertNotNull(folders.createFolderAsChildOfId(target, home, targetId));
    afterPost = () -> assertTrue(folders.deleteFolderById(targetId));
    ObjectNode command = JsonMapper.MAPPER.createObjectNode().put("@id", source)
        .put("targetFolderId", targetId.getId()).put("nameTemplate", "Copy of {{name}}");
    HttpResponse<String> copy = send("POST", "/command/copy-artifact-to-folder", command.toString(), null);
    assertTrue(copy.statusCode() >= 400, copy.body());
    assertNotNull(copiedId, "the content service created a document before the destination disappeared");
    assertNull(folders.findArtifactById(artifactId(copiedId)), "the copy has no workspace node");
    assertFalse(documents.containsKey(copiedId), "a refused copy must discard the newly created content-store orphan");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"create", "copy", "draft"})
  void failedCreationAndFailedCleanupRecoverThroughANewRelay(String operation) throws Exception {
    String source = createTemplate("Durable cleanup source");
    if (operation.equals("draft")) {
      folders.updateArtifactById(artifactId(source), CedarResourceType.TEMPLATE,
          Map.of(org.metadatacenter.server.neo4j.cypher.NodeProperty.PUBLICATION_STATUS, "bibo:published",
              org.metadatacenter.server.neo4j.cypher.NodeProperty.VERSION, "0.0.1"));
      documents.get(source).body().put("bibo:status", "bibo:published");
    }
    FolderServerFolder target = new FolderServerFolder();
    target.setName("Destination removed during " + operation);
    target.setDescription("Failed-create recovery");
    CedarFolderId targetId = config.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    assertNotNull(folders.createFolderAsChildOfId(target, home, targetId));
    refuseCleanup = true;
    compressedCreationValidator = true;
    afterPost = () -> assertTrue(folders.deleteFolderById(targetId));
    ObjectNode command;
    String path;
    if (operation.equals("copy")) {
      command = JsonMapper.MAPPER.createObjectNode().put("@id", source)
          .put("targetFolderId", targetId.getId()).put("nameTemplate", "Copy of {{name}}");
      path = "/command/copy-artifact-to-folder";
    } else if (operation.equals("draft")) {
      command = JsonMapper.MAPPER.createObjectNode().put("@id", source)
          .put("folderId", targetId.getId()).put("newVersion", "0.0.2");
      path = "/command/create-draft-artifact";
    } else {
      command = documents.get(source).body().deepCopy(); command.putNull("@id");
      path = "/templates?folder_id=" + enc(targetId.getId());
    }
    var failed = send("POST", path, command.toString(), null);
    assertTrue(failed.statusCode() >= 400, failed.body());
    String orphan = copiedId;
    assertNotNull(orphan);
    assertNull(folders.findArtifactById(artifactId(orphan)));
    assertTrue(documents.containsKey(orphan), "the injected outage must prevent immediate cleanup");
    String job = cleanupJob(orphan);
    assertNotNull(job, "failed cleanup must survive the request");
    afterPost = null; refuseCleanup = false;
    try (var restarted = new org.metadatacenter.server.resource.ArtifactCreateCleanupService(config,
        CedarDataServices.getInstance().getNeoUserService())) {
      restarted.cleanupNow(job, CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(config)));
    }
    assertFalse(documents.containsKey(orphan), "a fresh relay must delete the exact unregistered revision");
    assertNull(cleanupJob(orphan));
    assertTrue(documents.containsKey(source), "the source must survive compensation of its derivative");
  }

  @Test
  void anIndexFailureAfterCopyRegistrationDoesNotDeleteSuccessfulContent() throws Exception {
    String source = createTemplate("Copy indexing outage source");
    var index = org.mockito.Mockito.mock(org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService.class);
    org.mockito.Mockito.doThrow(new org.metadatacenter.exception.CedarProcessingException("index outage"))
        .when(index).indexDocument(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    AbstractResourceServerResource.injectServices(index,
        new IndexUtils(config).getNodeSearchingService(), new SearchPermissionEnqueueService(config),
        new ValuerecommenderReindexQueueService(config.getCacheConfig().getPersistent()));
    try {
      ObjectNode command = JsonMapper.MAPPER.createObjectNode().put("@id", source)
          .put("targetFolderId", home.getId()).put("nameTemplate", "Copy of {{name}}");
      var failed = send("POST", "/command/copy-artifact-to-folder", command.toString(), null);
      assertEquals(500, failed.statusCode());
      assertNotNull(folders.findArtifactById(artifactId(copiedId)));
      assertTrue(documents.containsKey(copiedId), "index failure must not compensate a committed registration");
      assertNull(cleanupJob(copiedId), "registration must retire cleanup before downstream calls");
    } finally {
      AbstractResourceServerResource.injectServices(new NoOpNodeIndexingService(config),
          new IndexUtils(config).getNodeSearchingService(), new SearchPermissionEnqueueService(config),
          new ValuerecommenderReindexQueueService(config.getCacheConfig().getPersistent()));
    }
  }

  private static String cleanupJob(String id) {
    var neo = org.metadatacenter.server.neo4j.Neo4jConfig.fromCedarConfig(config);
    try (var driver = org.neo4j.driver.GraphDatabase.driver(neo.getUri(),
        org.neo4j.driver.AuthTokens.basic(neo.getUserName(), neo.getUserPassword())); var session = driver.session()) {
      var result = session.run("MATCH (j:CedarArtifactCreateCleanup {resourceId:$id}) RETURN j.jobId AS job",
          Map.of("id", id));
      return result.hasNext() ? result.single().get("job").asString() : null;
    }
  }

  @Test
  void sequentialEditsKeepContentAndGraphInAgreement() throws Exception {
    String id = createTemplate("Sequential control");
    for (String name : new String[]{"First sequential edit", "Second sequential edit"}) {
      Stored before = documents.get(id);
      ObjectNode edited = before.body().deepCopy();
      edited.put("schema:name", name);
      HttpResponse<String> result = send("PUT", "/templates/" + enc(id), edited.toString(), before.etag());
      assertEquals(200, result.statusCode(), result.body());
      assertEquals(name, documents.get(id).body().path("schema:name").asText());
      assertEquals(name, folders.findArtifactById(artifactId(id)).getName());
    }
  }

  private static String createTemplate(String name) {
    String id = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(id); template.setName(name); template.setDescription("Cross-store audit");
    template.setVersion("0.0.1"); template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true); template.setLatestDraftVersion(true); template.setLatestPublishedVersion(false);
    assertNotNull(folders.createResourceAsChildOfId(template, home));
    documents.put(id, new Stored(JsonMapper.MAPPER.createObjectNode().put("@id", id)
        .put("@type", "https://schema.metadatacenter.org/core/Template")
        .put("schema:name", name).put("schema:description", "Cross-store audit")
        .put("pav:version", "0.0.1").put("bibo:status", "bibo:draft"), 1));
    return id;
  }

  private static CedarArtifactId artifactId(String id) { return CedarArtifactId.build(id, CedarResourceType.TEMPLATE); }
  private static String enc(String id) { return URLEncoder.encode(id, StandardCharsets.UTF_8); }
  private static HttpRequest request(String method, String path, String body, String etag) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getLocalPort() + path))
        .timeout(Duration.ofSeconds(20)).header("Authorization", authorization).header("Content-Type", "application/json");
    if (etag != null) builder.header("If-Match", etag);
    return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
  }
  private static HttpResponse<String> send(String method, String path, String body, String etag) throws Exception {
    return client.send(request(method, path, body, etag), HttpResponse.BodyHandlers.ofString());
  }

  private static void handleArtifact(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String id = path.startsWith("/templates/") ? path.substring("/templates/".length()) : null;
    String method = exchange.getRequestMethod();
    if (method.equals("POST")) {
      ObjectNode body = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody());
      copiedId = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
      body.put("@id", copiedId);
      Stored stored = new Stored(body, 1);
      documents.put(copiedId, stored);
      Runnable hook = afterPost;
      if (hook != null) hook.run();
      exchange.getResponseHeaders().set("Location", copiedId);
      respond(exchange, 201, stored);
      return;
    }
    Stored stored = id == null ? null : documents.get(id);
    if (stored == null) { respond(exchange, 404, null); return; }
    if (method.equals("GET")) { respond(exchange, 200, stored); return; }
    if (!stored.etag().equals(exchange.getRequestHeaders().getFirst("If-Match"))) {
      respond(exchange, 412, null); return;
    }
    if (method.equals("DELETE")) {
      if (refuseCleanup) { respond(exchange, 503, null); return; }
      documents.remove(id); respond(exchange, 204, null); return;
    }
    if (method.equals("PUT")) {
      ObjectNode body = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody());
      Stored replacement = new Stored(body, stored.revision() + 1);
      if (!documents.replace(id, stored, replacement)) { respond(exchange, 412, null); return; }
      if (body.path("schema:name").asText().equals(delayedName)) {
        firstWriteStored.countDown();
        try {
          if (!releaseFirstReply.await(15, TimeUnit.SECONDS)) throw new IOException("audit did not release delayed reply");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
      }
      respond(exchange, 200, replacement);
      return;
    }
    respond(exchange, 405, null);
  }

  private static void respond(HttpExchange exchange, int status, Stored stored) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (stored != null) {
      String validator = status == 201 && compressedCreationValidator
          ? "\"" + stored.revision() + "--gzip\"" : stored.etag();
      exchange.getResponseHeaders().set("ETag", validator);
    }
    byte[] bytes = (stored == null ? "{}" : stored.body().toString()).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
