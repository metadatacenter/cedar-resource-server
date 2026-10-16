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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUserPermissionPair;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
import org.metadatacenter.server.security.model.permission.resource.ResourceRole;
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
class DraftTransitionTest {
  private record Stored(ObjectNode body, long revision) { String etag() { return "\"" + revision + "\""; } }
  private static final Map<String, Stored> documents = new ConcurrentHashMap<>();
  private static HttpServer artifactServer;
  private static ExecutorService artifactThreads;
  private static CedarConfig config;
  private static FolderServiceSession folders;
  private static CedarFolderId home;
  private static String authorization;
  private static volatile String sourceId;
  private static volatile CountDownLatch postsEntered, releasePosts, cloneQueued;
  private static final java.util.List<String> cloneTargets = new CopyOnWriteArrayList<>();
  private static final java.util.List<String> deleted = new CopyOnWriteArrayList<>();
  private static final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
  private static final HttpClient client = HttpClient.newHttpClient();
  private static final DropwizardTestSupport<ResourceServerConfiguration> server =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));
  @BeforeAll
  static void start() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    artifactThreads = Executors.newCachedThreadPool();
    artifactServer.setExecutor(artifactThreads);
    artifactServer.createContext("/", DraftTransitionTest::handleArtifact);
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
    var queue = org.mockito.Mockito.mock(org.metadatacenter.server.resource.CloneInstancesEnqueueService.class);
    org.mockito.Mockito.doAnswer(call -> {
      cloneTargets.add(((org.metadatacenter.id.CedarTemplateId) call.getArgument(1)).getId());
      cloneQueued.countDown();
      return null;
    }).when(queue).cloneInstances(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyString());
    CommandVersionResource.injectCloneInstancesEnqueueServices(queue);
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

  private static String prepare(int callers) throws Exception {
    documents.clear(); cloneTargets.clear(); deleted.clear(); writes.set(0);
    postsEntered = new CountDownLatch(callers); releasePosts = new CountDownLatch(1); cloneQueued = new CountDownLatch(1);
    sourceId = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    var body = new org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer().renderTemplateSchemaArtifact(
        org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact.builder().withName("Published source")
            .withJsonLdId(URI.create(sourceId)).build());
    body.put("bibo:status", "bibo:published"); body.put("pav:version", "1.0.0");
    documents.put(sourceId, new Stored(body,1));
    var node = new FolderServerTemplate(); node.setId(sourceId); node.setName("Source " + sourceId);
    node.setVersion("1.0.0"); node.setPublicationStatus("bibo:published");
    node.setLatestVersion(true); node.setLatestPublishedVersion(true); node.setLatestDraftVersion(false);
    assertNotNull(folders.createResourceAsChildOfId(node, home));
    var instance = new org.metadatacenter.model.folderserver.basic.FolderServerInstance();
    instance.setId(config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.INSTANCE));
    instance.setName("Instance " + sourceId);
    instance.setIsBasedOn(org.metadatacenter.id.CedarTemplateId.build(sourceId));
    assertNotNull(folders.createResourceAsChildOfId(instance, home));
    return JsonMapper.STRICT_MAPPER.createObjectNode().put("@id",sourceId).put("newVersion","1.0.1")
        .put("folderId",home.getId()).put("propagateSharing",false).put("newFolderName","Clones").toString();
  }
  private static HttpRequest request(String body) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + server.getLocalPort() + "/command/create-draft-artifact"))
        .timeout(Duration.ofSeconds(25)).header("Authorization", authorization).header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
  }
  private static void assertSingleSuccessor() {
    assertEquals(1, cloneTargets.size(), "only the winning draft may schedule instance copies");
    String target = cloneTargets.get(0);
    var source = folders.findSchemaArtifactById(org.metadatacenter.id.CedarTemplateId.build(sourceId));
    var draft = folders.findSchemaArtifactById(org.metadatacenter.id.CedarTemplateId.build(target));
    assertNotNull(draft); assertEquals(sourceId, draft.getPreviousVersion().getId());
    assertEquals(2, folders.getVersionHistory(org.metadatacenter.id.CedarTemplateId.build(sourceId)).size());
    assertEquals(false, source.isLatestVersion()); assertEquals(true, source.isLatestPublishedVersion());
    assertEquals(true, draft.isLatestVersion()); assertEquals(true, draft.isLatestDraftVersion());
    assertEquals(false, draft.isLatestPublishedVersion());
    assertEquals(java.util.Set.of(sourceId,target), documents.keySet(), "the losing request must clean up its document");
  }
  @Test void simultaneousNextDraftsCreateOneSuccessorAndOneCloneJob() throws Exception {
    String body = prepare(2);
    var first = client.sendAsync(request(body), HttpResponse.BodyHandlers.ofString());
    var second = client.sendAsync(request(body), HttpResponse.BodyHandlers.ofString());
    try {
      assertTrue(postsEntered.await(15,TimeUnit.SECONDS), "both callers must pass preflight before either registers");
      releasePosts.countDown();
      var replies = java.util.List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
      assertEquals(java.util.List.of(201,409), replies.stream().map(HttpResponse::statusCode).sorted().toList(), replies.toString());
      assertSingleSuccessor();
      assertEquals(2,writes.get()); assertEquals(1,deleted.size());
    } finally { releasePosts.countDown(); }
  }
  @Test void aLostClientResponseAndRetryCannotCreateAnotherDraftOrCloneJob() throws Exception {
    String body = prepare(1);
    var abandoned = client.sendAsync(request(body),HttpResponse.BodyHandlers.ofString());
    try {
      assertTrue(postsEntered.await(15,TimeUnit.SECONDS));
      assertTrue(abandoned.cancel(true), "drop the client before the server can return its successful response");
      releasePosts.countDown();
      assertTrue(cloneQueued.await(15,TimeUnit.SECONDS), "the abandoned request must complete its graph write");
      var retry = client.send(request(body),HttpResponse.BodyHandlers.ofString());
      assertEquals(400,retry.statusCode(),retry.body());
      assertSingleSuccessor();
      assertEquals(1,writes.get()); assertTrue(deleted.isEmpty());
    } finally { releasePosts.countDown(); }
  }
  /**
   * Workspace's Create Draft and the Designer's Create new draft both ask for the source's sharing, so a
   * draft is shared as its source is; without the request it is its owner's alone. The source's ACL is
   * the same afterwards either way. "none" is a source user 2 holds no grant on.
   */
  @ParameterizedTest(name = "{0}, propagateSharing {1}")
  @CsvSource({"viewer,true", "editor,true", "manager,true", "none,true", "viewer,false", "editor,false", "manager,false"})
  void aDraftCarriesItsSourcesGrantsOnlyWhenAskedTo(String role, boolean propagate) throws Exception {
    prepare(1); releasePosts.countDown();
    var user1 = TestAuthUtil.getTestUser1(config); var user2 = TestAuthUtil.getTestUser2(config);
    String user2Authorization = TestAuthUtil.getTestUser2AuthHeader(config);
    if (!role.equals("none")) {
      var grant = new ResourcePermissionsRequest();
      grant.setOwner(new ResourcePermissionUser(user1.getId()));
      grant.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
          new ResourcePermissionUser(user2.getId()), ResourceRole.valueOf(role.toUpperCase())));
      assertFalse(CedarDataServices.getInstance().getResourcePermissionServiceSession(
          CedarRequestContextFactory.fromUser(user1)).updateResourcePermissions(
          org.metadatacenter.id.CedarTemplateId.build(sourceId), grant).isError());
    }
    String sourceAcl = get("/templates/" + enc(sourceId) + "/permissions", authorization).body();
    var body = JsonMapper.STRICT_MAPPER.createObjectNode().put("@id",sourceId).put("newVersion","1.0.1")
        .put("folderId",home.getId()).put("propagateSharing",propagate).put("newFolderName","").toString();
    var reply = client.send(request(body),HttpResponse.BodyHandlers.ofString());
    assertEquals(201,reply.statusCode(),reply.body());
    String draftId = JsonMapper.STRICT_MAPPER.readTree(reply.body()).path("@id").asText();
    assertNotEquals(sourceId,draftId);

    assertEquals(sourceAcl, get("/templates/" + enc(sourceId) + "/permissions", authorization).body(),
        "drafting must leave the source's ACL as it was");
    var draftAcl = JsonMapper.STRICT_MAPPER.readTree(get("/templates/" + enc(draftId) + "/permissions", authorization).body());
    assertEquals(user1.getId(), draftAcl.path("owner").path("@id").asText(), "the draft belongs to the owner who drafted it");
    var expected = propagate ? JsonMapper.STRICT_MAPPER.readTree(sourceAcl).path("userPermissions")
        : JsonMapper.STRICT_MAPPER.createArrayNode();
    assertEquals(expected, draftAcl.path("userPermissions"));

    var details = get("/templates/" + enc(draftId) + "/details", user2Authorization);
    if (!propagate || role.equals("none")) { assertEquals(403,details.statusCode(),details.body()); return; }
    assertEquals(200,details.statusCode(),details.body());
    var granted = JsonMapper.STRICT_MAPPER.readTree(details.body()).path("currentUserPermissions");
    String capabilities = granted.path("capabilities").toString();
    assertEquals(role, granted.path("role").asText());
    assertTrue(capabilities.contains("readResource"), capabilities);
    assertEquals(!role.equals("viewer"), capabilities.contains("updateResource"), capabilities);
    assertEquals(role.equals("manager"), capabilities.contains("manageGrants"), capabilities);
  }
  private static HttpResponse<String> get(String path, String auth) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.getLocalPort() + path))
        .timeout(Duration.ofSeconds(25)).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
  }
  private static String enc(String id) { return URLEncoder.encode(id, StandardCharsets.UTF_8); }
  private static void handleArtifact(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (exchange.getRequestMethod().equals("POST") && path.equals("/templates")) {
      ObjectNode body = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody());
      String id = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
      body.put("@id",id); Stored created = new Stored(body,7); documents.put(id,created); writes.incrementAndGet();
      postsEntered.countDown();
      try { if (!releasePosts.await(20,TimeUnit.SECONDS)) throw new IOException("draft barrier timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
      exchange.getResponseHeaders().set("Location",id);
      respond(exchange,201,created); return;
    }
    String id = config.getLinkedDataUtil().resolveResourceId(
        CedarResourceType.forPrefix(path.split("/")[1]), path.substring(path.indexOf('/',1)+1));
    Stored stored = documents.get(id);
    if (stored == null) { respond(exchange,404,null); return; }
    if (exchange.getRequestMethod().equals("GET")) { respond(exchange,200,stored); return; }
    if (!stored.etag().equals(exchange.getRequestHeaders().getFirst("If-Match"))) { respond(exchange,412,null); return; }
    if (exchange.getRequestMethod().equals("DELETE")) {
      assertEquals("\"7\"",exchange.getRequestHeaders().getFirst("If-Match"));
      assertFalse(cloneTargets.contains(id), "cleanup must not delete the winning draft");
      documents.remove(id); deleted.add(id); respond(exchange,204,null); return;
    }
    if (exchange.getRequestMethod().equals("PUT")) {
      var updated = new Stored((ObjectNode) JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody()),stored.revision()+1);
      documents.put(id,updated); respond(exchange,200,updated); return;
    }
    respond(exchange,405,null);
  }
  private static void respond(HttpExchange exchange,int status,Stored stored) throws IOException {
    exchange.getResponseHeaders().set("Content-Type","application/json");
    if (stored != null) exchange.getResponseHeaders().set("ETag",stored.etag());
    byte[] body = (stored == null ? "{}" : stored.body().toString()).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status,status == 204 ? -1 : body.length);
    if (status != 204) exchange.getResponseBody().write(body);
    exchange.close();
  }
}
