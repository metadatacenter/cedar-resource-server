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
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.ModelNodeNames;
import org.metadatacenter.model.SystemComponent;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whatever a creation path produces has to survive being read, changed and written back.
 *
 * <p>Every path that creates an artifact composes a document out of another one, and each decides
 * for itself which fields to carry, reset or drop. The update path then judges what it is given
 * against what the graph holds, and it refuses a request whose DOI is not the one on the node. Two
 * creation paths produced artifacts that failed that judgement on the first ordinary edit: a copy
 * carried the source's DOI, and applying a definition to a new draft brought the published
 * artifact's DOI back. Both answered success, and the artifact they made was unusable.
 *
 * <p>The property is one line long and covers every such path: create it, read it, rename it, write
 * it back, and be served. It says nothing about which fields a path should carry, which is the point.
 * It fails whenever a creation path and the update path disagree about what a valid artifact is.
 */
public class CreatedArtifactSurvivesAnEditTest {

  private static final Map<String, ObjectNode> ARTIFACTS = new ConcurrentHashMap<>();
  private static final AtomicInteger REVISIONS = new AtomicInteger();
  private static HttpServer artifactServer;

  static {
    try {
      artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub artifact server", e);
    }
    artifactServer.createContext("/", CreatedArtifactSurvivesAnEditTest::handleArtifactRequest);
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
    FolderServiceSession folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    homeFolderId = folderSession.findHomeFolderOf().getResourceId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    artifactServer.stop(0);
  }

  @Test
  public void aCreatedTemplateSurvivesAnEdit() throws Exception {
    assertSurvivesAnEdit("create", createTemplate("Survives an edit"), "/templates/");
  }

  @Test
  public void aCreatedInstanceSurvivesAnEdit() throws Exception {
    URI templateId = URI.create(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    HttpResponse<String> created = post("/template-instances?folder_id=" + encode(homeFolderId.getId()),
        new JsonArtifactRenderer().renderTemplateInstanceArtifact(TemplateInstanceArtifact.builder()
            .withName("Instance that survives an edit")
            .withIsBasedOn(templateId)
            .build()).toString());
    Assertions.assertEquals(201, created.statusCode(), created.body());

    assertSurvivesAnEdit("create an instance", idOf(created), "/template-instances/");
  }

  @Test
  public void aCopySurvivesAnEdit() throws Exception {
    String sourceId = createTemplate("Copy source that carries a DOI");
    setDoi(sourceId, "10.5072/FK2-survives-copy");

    HttpResponse<String> copied = post("/command/copy-artifact-to-folder",
        "{\"@id\":\"" + sourceId + "\",\"targetFolderId\":\"" + homeFolderId.getId()
            + "\",\"nameTemplate\":\"Copy of {{name}}\"}");
    Assertions.assertEquals(201, copied.statusCode(), copied.body());

    assertSurvivesAnEdit("copy", idOf(copied), "/templates/");
  }

  @Test
  public void aDraftSurvivesAnEdit() throws Exception {
    String sourceId = createTemplate("Draft source that carries a DOI");
    setDoi(sourceId, "10.5072/FK2-survives-draft");
    Assertions.assertEquals(200, post("/command/publish-artifact",
        "{\"@id\":\"" + sourceId + "\",\"newVersion\":\"1.0.0\"}").statusCode());

    HttpResponse<String> drafted = post("/command/create-draft-artifact",
        "{\"@id\":\"" + sourceId + "\",\"newVersion\":\"1.0.1\",\"folderId\":\"" + homeFolderId.getId()
            + "\",\"propagateSharing\":false,\"newFolderName\":\"\"}");
    Assertions.assertEquals(201, drafted.statusCode(), drafted.body());

    assertSurvivesAnEdit("create a draft", idOf(drafted), "/templates/");
  }

  @Test
  public void aDraftCarryingASubmittedDefinitionSurvivesAnEdit() throws Exception {
    String sourceId = createTemplate("Publish-and-draft source that carries a DOI");
    setDoi(sourceId, "10.5072/FK2-survives-publish-and-draft");

    // What a client submits here is the template it read and edited, DOI and all, rather than a
    // document composed from nothing.
    ObjectNode submitted = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(
        get("/templates/" + encode(sourceId)).body());
    submitted.put(ModelNodeNames.SCHEMA_ORG_NAME, "Publish-and-draft source, edited");

    HttpResponse<String> drafted = post("/command/publish-create-draft-template/" + encode(sourceId),
        submitted.toString());
    Assertions.assertEquals(200, drafted.statusCode(), drafted.body());

    assertSurvivesAnEdit("publish and draft", idOf(drafted), "/templates/");
  }

  /**
   * Reads the artifact back through the resource server, renames it, and writes it back. The rename
   * is what an ordinary edit is; the point is that the rest of the document goes back exactly as it
   * came, since that is what a client that read before writing would send.
   */
  private static void assertSurvivesAnEdit(String createdBy, String artifactId, String collection)
      throws Exception {
    String path = collection + encode(artifactId);
    HttpResponse<String> read = get(path);
    Assertions.assertEquals(200, read.statusCode(),
        "an artifact from " + createdBy + " could not be read back: " + read.body());

    ObjectNode edited = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(read.body());
    edited.put(ModelNodeNames.SCHEMA_ORG_NAME, edited.path(ModelNodeNames.SCHEMA_ORG_NAME).asText() + ", edited");
    HttpResponse<String> written = put(path, edited.toString(),
        read.headers().firstValue("ETag").orElse("*"));

    Assertions.assertEquals(200, written.statusCode(),
        "an artifact from " + createdBy + " was refused by the first ordinary edit: " + written.body());
  }

  private static String createTemplate(String name) throws Exception {
    HttpResponse<String> created = post("/templates?folder_id=" + encode(homeFolderId.getId()),
        templateDocument(name).toString());
    Assertions.assertEquals(201, created.statusCode(), created.body());
    return idOf(created);
  }

  private static void setDoi(String artifactId, String doi) throws Exception {
    HttpResponse<String> response = post("/command/annotations/doi",
        "{\"@id\":\"" + artifactId + "\",\"doi\":\"" + doi + "\"}");
    Assertions.assertEquals(200, response.statusCode(), response.body());
  }

  private static ObjectNode templateDocument(String name) {
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(TemplateSchemaArtifact.builder()
        .withName(name)
        .withVersion(Version.fromString("0.0.1"))
        .withStatus(Status.DRAFT)
        .build());
  }

  private static String idOf(HttpResponse<String> response) throws IOException {
    String id = JsonMapper.STRICT_MAPPER.readTree(response.body()).path("@id").asText();
    Assertions.assertFalse(id.isBlank(), response.body());
    return id;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return TestHttpClient.send(HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .GET().build());
  }

  private static HttpResponse<String> post(String path, String body) throws Exception {
    return TestHttpClient.send(HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build());
  }

  private static HttpResponse<String> put(String path, String body, String ifMatch) throws Exception {
    return TestHttpClient.send(HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .header("If-Match", ifMatch)
        .PUT(HttpRequest.BodyPublishers.ofString(body))
        .build());
  }

  /** The stub keeps every artifact it is given, keyed by identifier, and answers by identifier. */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    String artifactId = null;
    for (String collection : List.of("/template-instances/", "/templates/")) {
      if (path.startsWith(collection) && path.length() > collection.length()) {
        artifactId = URLDecoder.decode(path.substring(collection.length()), StandardCharsets.UTF_8);
        break;
      }
    }
    boolean names = artifactId != null && artifactId.startsWith("http");

    if ("POST".equals(method)) {
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      String mintedId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(
          path.startsWith("/template-instances") ? CedarResourceType.INSTANCE : CedarResourceType.TEMPLATE);
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
