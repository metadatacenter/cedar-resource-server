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
import org.metadatacenter.artifacts.model.core.Annotations;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.model.BiboStatus;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.ModelNodeNames;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishing a template and applying a new definition to the draft it leaves behind.
 *
 * <p>The command runs three writes in sequence: it publishes the template, creates a draft that
 * records the published version as its predecessor, and then applies the submitted definition to that
 * draft. The submitted definition is the client's document and knows nothing of the draft, so the
 * third write can undo what the second established -- the predecessor, the draft status, and the
 * absence of the published artifact's DOI.
 *
 * <p>The artifact server is a stub keyed by identifier, so the sequence of reads and writes runs
 * against documents that persist between them; the graph is embedded.
 */
public class PublishCreateDraftTemplateTest {

  private static final String SOURCE_VERSION = "1.0.0";
  private static final String DRAFT_VERSION = "1.0.1";
  private static final String PUBLISHED_DOI = "10.5072/FK2-published-template";
  private static final URI UNRELATED_PREDECESSOR =
      URI.create("https://repo.metadatacenter.orgx/templates/00000000-1111-2222-3333-444444444444");

  private static final Map<String, ObjectNode> ARTIFACTS = new ConcurrentHashMap<>();
  private static final AtomicInteger REVISIONS = new AtomicInteger();
  private static HttpServer artifactServer;

  static {
    try {
      artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub artifact server", e);
    }
    artifactServer.createContext("/", PublishCreateDraftTemplateTest::handleArtifactRequest);
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

  @Test
  public void theNewDraftKeepsThePublishedTemplateAsItsPredecessor() throws Exception {
    CedarTemplateId sourceId = createSourceTemplate("Template with a successor", null);
    String submitted = templateDocument("Template with a successor, edited", null)
        .put(ModelNodeNames.PAV_PREVIOUS_VERSION, UNRELATED_PREDECESSOR.toString())
        .toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(200, response.statusCode(), response.body());
    JsonNode draft = storedDraftOf(sourceId, response);
    Assertions.assertEquals(sourceId.getId(), draft.path(ModelNodeNames.PAV_PREVIOUS_VERSION).asText(),
        "the draft lost the published version it was drawn from");
    Assertions.assertEquals(BiboStatus.DRAFT.getValue(), draft.path(ModelNodeNames.BIBO_STATUS).asText(),
        "the submitted definition decided the draft's publication status");
    Assertions.assertEquals(DRAFT_VERSION, draft.path(ModelNodeNames.PAV_VERSION).asText());
  }

  @Test
  public void theNewDraftDoesNotInheritThePublishedTemplateDoi() throws Exception {
    CedarTemplateId sourceId = createSourceTemplate("Template with a DOI", PUBLISHED_DOI);
    String submitted = templateDocument("Template with a DOI, edited", PUBLISHED_DOI).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(200, response.statusCode(), response.body());
    JsonNode draft = storedDraftOf(sourceId, response);
    Assertions.assertFalse(draft.path(ModelNodeNames.ANNOTATIONS).has(ModelNodeNames.DATACITE_DOI_URI),
        "the draft carries the DOI minted for the version it succeeds: " + draft);
  }

  /** The draft is whatever the command created under a new identifier, which the response names. */
  private static JsonNode storedDraftOf(CedarTemplateId sourceId, HttpResponse<String> response) throws IOException {
    String draftId = JsonMapper.STRICT_MAPPER.readTree(response.body()).path("@id").asText();
    Assertions.assertFalse(draftId.isBlank(), response.body());
    Assertions.assertNotEquals(sourceId.getId(), draftId, "the command answered with the published template");
    JsonNode stored = ARTIFACTS.get(draftId);
    Assertions.assertNotNull(stored, "the draft never reached the artifact server");
    return stored;
  }

  private static HttpResponse<String> publishCreateDraft(CedarTemplateId sourceId, String body) throws Exception {
    return CLIENT.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + SERVER.getLocalPort()
                + "/command/publish-create-draft-template/"
                + URLEncoder.encode(sourceId.getId(), StandardCharsets.UTF_8)))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** A draft template, present in both stores, that the test user owns. */
  private static CedarTemplateId createSourceTemplate(String name, String doi) {
    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName(name);
    template.setDescription("Publish-and-draft fixture");
    template.setVersion(SOURCE_VERSION);
    template.setPublicationStatus(BiboStatus.DRAFT.getValue());
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    if (doi != null) {
      template.setDOI(doi);
    }
    FolderServerArtifact created = folderSession.createResourceAsChildOfId(template, homeFolderId);
    Assertions.assertNotNull(created);
    CedarTemplateId sourceId = CedarTemplateId.build(created.getId());
    ObjectNode document = templateDocument(name, doi);
    document.put(ModelNodeNames.JSON_LD_ID, sourceId.getId());
    ARTIFACTS.put(sourceId.getId(), document);
    return sourceId;
  }

  private static ObjectNode templateDocument(String name, String doi) {
    TemplateSchemaArtifact.Builder builder = TemplateSchemaArtifact.builder()
        .withName(name)
        .withVersion(Version.fromString(SOURCE_VERSION))
        .withStatus(Status.DRAFT);
    if (doi != null) {
      builder.withAnnotations(Annotations.builder()
          .withIriAnnotation(ModelNodeNames.DATACITE_DOI_URI, URI.create(doi))
          .build());
    }
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(builder.build());
  }

  /** The stub keeps every artifact it is given, keyed by identifier, and answers by identifier. */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    String artifactId = path.contains("/templates/")
        ? URLDecoder.decode(path.substring(path.indexOf("/templates/") + "/templates/".length()),
        StandardCharsets.UTF_8)
        : null;

    if ("POST".equals(method)) {
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      String mintedId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
      created.put(ModelNodeNames.JSON_LD_ID, mintedId);
      ARTIFACTS.put(mintedId, created);
      send(exchange, 201, created, mintedId);
      return;
    }
    if ("PUT".equals(method) && artifactId != null) {
      ObjectNode replacement = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      ARTIFACTS.put(artifactId, replacement);
      send(exchange, 200, replacement, null);
      return;
    }
    if ("GET".equals(method) && artifactId != null && ARTIFACTS.containsKey(artifactId)) {
      send(exchange, 200, ARTIFACTS.get(artifactId), null);
      return;
    }
    exchange.sendResponseHeaders(404, -1);
    exchange.close();
  }

  private static void send(HttpExchange exchange, int status, JsonNode body, String location) throws IOException {
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
