package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.cedar.resource.deletion.ArtifactDeletionCompletionService;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarSchemaArtifactId;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.id.CedarUntypedSchemaArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.*;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recursive folder deletion over real resource HTTP and graph, across artifact type, version history,
 * folder layout, where the folder's boundary falls in a version chain, and identifier order. The
 * document store is a stand-in that keeps revisions and refuses a stale If-Match. As the artifact
 * server does, it rewrites a successor's link when the resource server reports its predecessor gone,
 * which moves the successor's revision on.
 */
class RecursiveDeletionMatrixTest {
  /** A version history, oldest first. */
  enum History {
    DRAFT("0.0.1:draft"),
    PUBLISHED("1.0.0:published"),
    PUBLISHED_THEN_DRAFT("1.0.0:published", "1.0.1:draft"),
    TWO_PUBLISHED_THEN_DRAFT("1.0.0:published", "2.0.0:published", "2.0.1:draft");
    final List<String> versions;
    History(String... versions) { this.versions = List.of(versions); }
  }
  /** Where the versions sit below the folder being deleted. */
  enum Layout { FLAT, NESTED, OLDEST_DEEPEST, NEWEST_DEEPEST }
  /** Which versions of the chain the folder holds. */
  enum Boundary { INSIDE, OLDEST_OUTSIDE, NEWEST_OUTSIDE }
  /** Whether the oldest version's identifier sorts first or last. */
  enum Order { OLDEST_FIRST, NEWEST_FIRST }

  private record Stored(ObjectNode body, long revision) { String etag() { return "\"" + revision + "\""; } }
  private static final Map<String, Stored> documents = new ConcurrentHashMap<>();
  private static final List<String> deleted = new CopyOnWriteArrayList<>();
  private static HttpServer artifactServer;
  private static ExecutorService artifactThreads;
  private static CedarConfig config;
  private static FolderServiceSession folders;
  private static CedarFolderId home;
  private static String authorization;
  private static ArtifactDeletionCompletionService completion;
  private static final HttpClient client = HttpClient.newHttpClient();
  private static final DropwizardTestSupport<ResourceServerConfiguration> server =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  @BeforeAll
  static void start() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    artifactThreads = Executors.newCachedThreadPool();
    artifactServer.setExecutor(artifactThreads);
    artifactServer.createContext("/", RecursiveDeletionMatrixTest::handleArtifact);
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
    completion = new ArtifactDeletionCompletionService(config,
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

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    for (CedarResourceType type : List.of(CedarResourceType.TEMPLATE, CedarResourceType.ELEMENT, CedarResourceType.FIELD))
      for (History history : History.values())
        for (Layout layout : Layout.values())
          for (Boundary boundary : Boundary.values())
            for (Order order : Order.values()) {
              // A single version has no boundary to cross and no order to choose.
              if (history.versions.size() == 1 && (boundary != Boundary.INSIDE || order != Order.NEWEST_FIRST)) continue;
              cases.add(Arguments.of(type, history, layout, boundary, order));
            }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0} {1} {2} {3} {4}")
  @MethodSource("cases")
  void oneConfirmedPassDeletesTheWholeTree(CedarResourceType type, History history, Layout layout,
                                           Boundary boundary, Order order) throws Exception {
    deleted.clear();
    var root = folder("Matrix root ", home);
    var a = folder("Matrix a ", root);
    var b = folder("Matrix b ", a);
    var c = folder("Matrix c ", b);
    List<CedarFolderId> levels = List.of(root, a, b, c);
    int n = history.versions.size();
    List<String> chain = new ArrayList<>();
    List<String> inside = new ArrayList<>();
    String previous = null;
    for (int i = 0; i < n; i++) {
      boolean outside = (boundary == Boundary.OLDEST_OUTSIDE && i == 0)
          || (boundary == Boundary.NEWEST_OUTSIDE && i == n - 1);
      CedarFolderId parent = outside ? home : switch (layout) {
        case FLAT -> root;
        case NESTED -> c;
        case OLDEST_DEEPEST -> levels.get(n - 1 - i);
        case NEWEST_DEEPEST -> levels.get(i);
      };
      String prefix = (order == Order.OLDEST_FIRST ? "a" + i : "z" + (9 - i)) + "-";
      String[] version = history.versions.get(i).split(":");
      previous = schemaArtifact(type, prefix, version[0], version[1], previous, i == n - 1, parent);
      chain.add(previous);
      if (!outside) inside.add(previous);
    }
    Map<String, Long> outsideRevisions = new HashMap<>();
    for (String id : chain) if (!inside.contains(id)) outsideRevisions.put(id, documents.get(id).revision());

    JsonNode plan = plan(root);
    assertTrue(plan.path("allowed").asBoolean(), plan.toString());
    assertEquals(4, plan.path("counts").path("folder").asInt());
    assertEquals(inside.size(), plan.path("counts").path(countKey(type)).asInt());
    var reply = call(root, plan.path("token").asText());
    assertEquals(200, reply.statusCode(), reply.body());
    JsonNode outcome = JsonMapper.STRICT_MAPPER.readTree(reply.body());
    assertEquals("completed", outcome.path("status").asText(), outcome.toString());
    assertEquals(0, outcome.path("remaining").asInt());
    assertEquals(inside.size(), outcome.path("deleted").path(countKey(type)).asInt());

    for (CedarFolderId folder : levels) assertNull(folders.findFolderById(folder));
    for (String id : inside) {
      assertNull(folders.findArtifactById(CedarArtifactId.build(id, type)), id);
      assertFalse(documents.containsKey(id), id);
    }
    // A newer version is deleted before the version it was made from.
    List<String> deletionOrder = deleted.stream().filter(inside::contains).toList();
    List<String> newestFirst = new ArrayList<>(inside);
    Collections.reverse(newestFirst);
    assertEquals(newestFirst, deletionOrder);
    // Versions outside the folder survive. One whose predecessor went loses its link to it; one whose
    // successor went is left as it was.
    for (String id : outsideRevisions.keySet()) {
      assertNotNull(folders.findArtifactById(CedarArtifactId.build(id, type)), id);
      assertTrue(documents.containsKey(id), id);
      int index = chain.indexOf(id);
      if (index > 0) {
        assertFalse(documents.get(id).body().has("pav:previousVersion"), id);
        assertNull(folders.findSchemaArtifactById(CedarSchemaArtifactId.build(id, type)).getPreviousVersion(), id);
      } else {
        assertEquals(outsideRevisions.get(id), documents.get(id).revision(), id);
      }
      folders.deleteResourceById(CedarArtifactId.build(id, type));
      documents.remove(id);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("orders")
  void instancesOfEveryVersionGoFirstAndTheChainFollowsNewestFirst(Order order) throws Exception {
    deleted.clear();
    var root = folder("Instances root ", home);
    var nested = folder("Instances nested ", root);
    List<String> chain = new ArrayList<>();
    String previous = null;
    List<String> versions = History.TWO_PUBLISHED_THEN_DRAFT.versions;
    for (int i = 0; i < versions.size(); i++) {
      String prefix = (order == Order.OLDEST_FIRST ? "a" + i : "z" + (9 - i)) + "-";
      String[] version = versions.get(i).split(":");
      previous = schemaArtifact(CedarResourceType.TEMPLATE, prefix, version[0], version[1], previous,
          i == versions.size() - 1, i % 2 == 0 ? root : nested);
      chain.add(previous);
    }
    List<String> instances = new ArrayList<>();
    for (String template : chain) {
      instances.add(instance(template, root));
      instances.add(instance(template, nested));
    }
    JsonNode plan = plan(root);
    assertTrue(plan.path("allowed").asBoolean(), plan.toString());
    var reply = call(root, plan.path("token").asText());
    JsonNode outcome = JsonMapper.STRICT_MAPPER.readTree(reply.body());
    assertEquals("completed", outcome.path("status").asText(), outcome.toString());
    assertEquals(6, outcome.path("deleted").path("instance").asInt());
    assertEquals(3, outcome.path("deleted").path("template").asInt());
    assertEquals(Set.copyOf(instances), Set.copyOf(deleted.subList(0, instances.size())));
    List<String> newestFirst = new ArrayList<>(chain);
    Collections.reverse(newestFirst);
    assertEquals(newestFirst, deleted.subList(instances.size(), deleted.size()));
    assertNull(folders.findFolderById(root));
  }

  static Stream<Order> orders() { return Stream.of(Order.values()); }

  private static String countKey(CedarResourceType type) {
    return switch (type) {
      case TEMPLATE -> "template";
      case ELEMENT -> "element";
      case FIELD -> "field";
      default -> throw new IllegalArgumentException(type.toString());
    };
  }

  private static CedarFolderId folder(String name, CedarFolderId parent) {
    var node = new FolderServerFolder();
    node.setName(name + UUID.randomUUID());
    var id = CedarFolderId.build(config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FOLDER));
    return folders.createFolderAsChildOfId(node, parent, id).getResourceId();
  }

  /** One version, in the graph and the document store, linked to the version it was made from. */
  private static String schemaArtifact(CedarResourceType type, String prefix, String version, String status,
                                       String previous, boolean latest, CedarFolderId parent) {
    String id = "https://repo.metadatacenter.org/" + type.getPrefix() + "/" + prefix + UUID.randomUUID();
    FolderServerSchemaArtifact node = switch (type) {
      case TEMPLATE -> new FolderServerTemplate();
      case ELEMENT -> new FolderServerElement();
      case FIELD -> new FolderServerField();
      default -> throw new IllegalArgumentException(type.toString());
    };
    boolean draft = status.equals("draft");
    node.setId(id);
    node.setName(id);
    node.setVersion(version);
    node.setPublicationStatus("bibo:" + status);
    node.setLatestVersion(latest);
    node.setLatestDraftVersion(latest && draft);
    node.setLatestPublishedVersion(!draft && latest);
    if (previous != null) node.setPreviousVersion(CedarUntypedSchemaArtifactId.build(previous));
    assertNotNull(folders.createResourceAsChildOfId(node, parent));
    ObjectNode body = JsonMapper.STRICT_MAPPER.createObjectNode().put("@id", id)
        .put("pav:version", version).put("bibo:status", "bibo:" + status);
    if (previous != null) body.put("pav:previousVersion", previous);
    documents.put(id, new Stored(body, 1));
    return id;
  }

  private static String instance(String template, CedarFolderId parent) {
    String id = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.INSTANCE);
    var node = new FolderServerInstance();
    node.setId(id);
    node.setName(id);
    node.setIsBasedOn(CedarTemplateId.build(template));
    assertNotNull(folders.createResourceAsChildOfId(node, parent));
    documents.put(id, new Stored(JsonMapper.STRICT_MAPPER.createObjectNode().put("@id", id)
        .put("schema:isBasedOn", template), 1));
    return id;
  }

  private static HttpResponse<String> call(CedarFolderId root, String token) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getLocalPort() + "/folders/"
            + URLEncoder.encode(root.getId(), StandardCharsets.UTF_8) + "/deletion"))
        .timeout(Duration.ofSeconds(25)).header("Authorization", authorization).header("Content-Type", "application/json");
    if (token == null) builder.GET();
    else builder.POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}"));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode plan(CedarFolderId root) throws Exception {
    var reply = call(root, null);
    assertEquals(200, reply.statusCode(), reply.body());
    return JsonMapper.STRICT_MAPPER.readTree(reply.body());
  }

  private static void handleArtifact(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/templates/deletion-references")) {
      var references = JsonMapper.STRICT_MAPPER.createObjectNode();
      for (var template : JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody())) {
        String templateId = config.getLinkedDataUtil().resolveResourceId(CedarResourceType.TEMPLATE, template.asText());
        var array = references.putArray(templateId);
        documents.forEach((id, value) -> {
          if (templateId.equals(value.body().path("schema:isBasedOn").asText())) array.add(id);
        });
      }
      respond(exchange, 200, new Stored(references, 1));
      return;
    }
    boolean link = path.endsWith("/version-predecessor");
    String target = link ? path.substring(0, path.length() - "/version-predecessor".length()) : path;
    String id = config.getLinkedDataUtil().resolveResourceId(
        CedarResourceType.forPrefix(target.split("/")[1]), target.substring(target.indexOf('/', 1) + 1));
    Stored stored = documents.get(id);
    if (stored == null) { respond(exchange, 404, null); return; }
    String method = exchange.getRequestMethod();
    String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
    if (method.equals("GET")) { respond(exchange, 200, stored); return; }
    if (method.equals("DELETE")) {
      if (!stored.etag().equals(ifMatch)) { respond(exchange, 412, null); return; }
      documents.remove(id);
      deleted.add(id);
      respond(exchange, 204, null);
      return;
    }
    if (method.equals("PUT") && link) {
      if (!stored.etag().equals(ifMatch)) { respond(exchange, 412, null); return; }
      JsonNode patch = JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody());
      ObjectNode body = stored.body().deepCopy();
      if (patch.path("previousVersion").isNull()) body.remove("pav:previousVersion");
      else body.put("pav:previousVersion", patch.path("previousVersion").asText());
      Stored updated = new Stored(body, stored.revision() + 1);
      documents.put(id, updated);
      respond(exchange, 200, updated);
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
