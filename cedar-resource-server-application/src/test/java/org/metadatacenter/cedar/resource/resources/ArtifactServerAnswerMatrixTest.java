package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.ModelNodeNames;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
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
import org.metadatacenter.util.test.TestHttpClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Every resource-server command that reads or writes a document through the artifact server, with
 * that request answered in each way it can fail.
 *
 * <p>The commands relay the artifact server's answer in different ways, and several lost it. An
 * outage during a publish or a draft became a bare 500 instead of a 503. A refused draft creation
 * became a 500 naming the artifact server's status in a parameter. A read that found no document took
 * the 404 envelope for the artifact, stamped a version on it and wrote it back.
 *
 * <p>Each case fails one request a command makes. The command must answer with the artifact server's
 * status and reason, or with 503 when no answer came. It must also leave both stores as they were.
 */
public class ArtifactServerAnswerMatrixTest {

  private static final Map<String, ObjectNode> ARTIFACTS = new ConcurrentHashMap<>();
  private static final AtomicInteger REVISIONS = new AtomicInteger();
  private static final String EXPLANATION = "The artifact server explains its refusal";

  /** A request the stub refuses: its method, and the answer it gives, or no answer at all. */
  private record Fault(String method, String collectionPath, Integer status) {
  }

  private static final AtomicReference<Fault> FAULT = new AtomicReference<>();
  private static HttpServer artifactServer;

  static {
    try {
      artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub artifact server", e);
    }
    artifactServer.createContext("/", ArtifactServerAnswerMatrixTest::handleArtifactRequest);
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

  private static CedarConfig cedarConfig;
  private static String authHeader;
  private static CedarFolderId homeFolderId;
  private static FolderServiceSession folderSession;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    cedarConfig = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
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
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    artifactServer.stop(0);
  }

  @AfterEach
  public void disarm() {
    FAULT.set(null);
  }

  /** One command a caller can make, with what it needs in place first and how it is asked. */
  private interface Command {
    HttpResponse<String> run(String templateId) throws Exception;
  }

  private static final Map<String, Command> COMMANDS = Map.of(
      "publish", id -> post("/command/publish-artifact", "{\"@id\":\"" + id + "\",\"newVersion\":\"1.0.0\"}"),
      "create a draft", id -> post("/command/create-draft-artifact",
          "{\"@id\":\"" + id + "\",\"newVersion\":\"1.0.1\",\"folderId\":\"" + homeFolderId.getId()
              + "\",\"propagateSharing\":false,\"newFolderName\":\"\"}"),
      "publish and draft", id -> post("/command/publish-create-draft-template/" + encode(id),
          templateDocument("Answer matrix fixture, edited").toString()),
      "copy", id -> post("/command/copy-artifact-to-folder",
          "{\"@id\":\"" + id + "\",\"targetFolderId\":\"" + homeFolderId.getId()
              + "\",\"nameTemplate\":\"Copy of {{name}}\"}"),
      "read", id -> send("GET", "/templates/" + encode(id), null),
      "update", id -> {
        ObjectNode edited = ARTIFACTS.get(id).deepCopy();
        edited.put(ModelNodeNames.SCHEMA_ORG_NAME, "Answer matrix fixture, renamed");
        return send("PUT", "/templates/" + encode(id), edited.toString());
      },
      "delete", id -> send("DELETE", "/templates/" + encode(id), null));

  /** The request of a command that is failed, as the method the artifact server receives it under. */
  private static final List<Object[]> STEPS = List.of(
      new Object[]{"publish", "GET"}, new Object[]{"publish", "PUT"},
      new Object[]{"create a draft", "GET"}, new Object[]{"create a draft", "POST"},
      new Object[]{"publish and draft", "GET"}, new Object[]{"publish and draft", "PUT"},
      new Object[]{"copy", "GET"}, new Object[]{"copy", "POST"},
      new Object[]{"read", "GET"}, new Object[]{"update", "PUT"}, new Object[]{"delete", "DELETE"});

  /** The artifact server's answers, as statuses; null is no answer at all. */
  private static final List<Integer> ANSWERS = java.util.Arrays.asList(400, 403, 404, 409, 412, 428, 500, 503, null);

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    for (Object[] step : STEPS) {
      for (Integer answer : ANSWERS) {
        cases.add(Arguments.of(step[0], step[1], answer == null ? "no answer" : answer.toString()));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}, its {1} answered with {2}")
  @MethodSource("cases")
  public void theCommandAnswersWithTheArtifactServersAnswerAndChangesNothing(String command, String method,
                                                                          String answer) throws Exception {
    String id = createTemplate();
    // A draft is made from a published version, so that command starts from one.
    if (command.equals("create a draft")) {
      HttpResponse<String> published = COMMANDS.get("publish").run(id);
      Assertions.assertEquals(200, published.statusCode(), published.body());
    }
    ObjectNode documentBefore = ARTIFACTS.get(id).deepCopy();
    int documentsBefore = ARTIFACTS.size();
    String nodeBefore = describe(id);

    Integer status = answer.equals("no answer") ? null : Integer.valueOf(answer);
    FAULT.set(new Fault(method, "/templates", status));
    HttpResponse<String> response = COMMANDS.get(command).run(id);
    FAULT.set(null);

    // A deletion that finds the document already gone has nothing left to delete there, so it
    // completes, and the dangling node goes with it. That is the one answer not relayed.
    if (command.equals("delete") && Integer.valueOf(404).equals(status)) {
      Assertions.assertEquals(2, response.statusCode() / 100, response.body());
      Assertions.assertEquals("absent", describe(id), "the deletion left the graph node behind");
      return;
    }
    int expected = status == null ? 503 : status;
    Assertions.assertEquals(expected, response.statusCode(),
        command + " answered " + response.statusCode() + " to the artifact server's " + answer + ": "
            + response.body());
    if (status != null) {
      Assertions.assertTrue(response.body().contains(EXPLANATION),
          command + " did not pass on the artifact server's reason: " + response.body());
    }
    Assertions.assertEquals(documentBefore, ARTIFACTS.get(id), command + " changed the stored document");
    Assertions.assertEquals(documentsBefore, ARTIFACTS.size(), command + " left a new document behind");
    Assertions.assertEquals(nodeBefore, describe(id), command + " changed the graph node");
  }

  /** What the graph says of a template, in the fields a failed command could have moved. */
  private static String describe(String id) {
    FolderServerArtifact node = folderSession.findArtifactById(CedarArtifactId.build(id, CedarResourceType.TEMPLATE));
    if (node == null) {
      return "absent";
    }
    String version = node instanceof FolderServerSchemaArtifact schema && schema.getVersion() != null
        ? schema.getVersion().getValue() : null;
    String status = node instanceof FolderServerSchemaArtifact schema && schema.getPublicationStatus() != null
        ? schema.getPublicationStatus().getValue() : null;
    return node.getName() + " " + version + " " + status;
  }

  private static String createTemplate() throws Exception {
    HttpResponse<String> created = post("/templates?folder_id=" + encode(homeFolderId.getId()),
        templateDocument("Answer matrix fixture").toString());
    Assertions.assertEquals(201, created.statusCode(), created.body());
    return JsonMapper.STRICT_MAPPER.readTree(created.body()).path("@id").asText();
  }

  private static ObjectNode templateDocument(String name) {
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(TemplateSchemaArtifact.builder()
        .withName(name)
        .withVersion(Version.fromString("0.0.1"))
        .withStatus(Status.DRAFT)
        .build());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static HttpResponse<String> post(String path, String body) throws Exception {
    return send("POST", path, body);
  }

  /** Every write names the revision it means as "whatever is there now"; the stub does not version. */
  private static HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .header("If-Match", "*");
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return TestHttpClient.send(request.build());
  }

  /** The stub keeps every template it is given, keyed by identifier, unless a fault is armed. */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();

    Fault fault = FAULT.get();
    if (fault != null && fault.method().equals(method) && path.startsWith(fault.collectionPath())) {
      if (fault.status() == null) {
        // No answer: the connection closes before a status line, as a server that went down would.
        exchange.close();
        return;
      }
      ObjectNode refusal = JsonMapper.STRICT_MAPPER.createObjectNode();
      refusal.put("message", EXPLANATION);
      refusal.put("statusCode", fault.status());
      respond(exchange, fault.status(), refusal, null);
      return;
    }

    String collection = "/templates/";
    String artifactId = path.startsWith(collection) && path.length() > collection.length()
        ? cedarConfig.getLinkedDataUtil().resolveResourceId(CedarResourceType.TEMPLATE,
            URLDecoder.decode(path.substring(collection.length()), StandardCharsets.UTF_8))
        : null;
    boolean names = artifactId != null && artifactId.startsWith("http");

    if ("POST".equals(method)) {
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      String mintedId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
      created.put(ModelNodeNames.JSON_LD_ID, mintedId);
      ARTIFACTS.put(mintedId, created);
      respond(exchange, 201, created, mintedId);
      return;
    }
    if ("PUT".equals(method) && names) {
      ObjectNode replacement = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      ARTIFACTS.put(artifactId, replacement);
      respond(exchange, 200, replacement, null);
      return;
    }
    if ("GET".equals(method) && names && ARTIFACTS.containsKey(artifactId)) {
      respond(exchange, 200, ARTIFACTS.get(artifactId), null);
      return;
    }
    if ("DELETE".equals(method) && names) {
      ARTIFACTS.remove(artifactId);
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(404, -1);
    exchange.close();
  }

  private static void respond(HttpExchange exchange, int status, JsonNode body, String location) throws IOException {
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("ETag", "\"" + REVISIONS.incrementAndGet() + "\"");
    if (location != null) {
      exchange.getResponseHeaders().set("Location", location);
    }
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
