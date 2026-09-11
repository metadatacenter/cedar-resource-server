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
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
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
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.ModelUtil;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

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
import java.util.function.Function;

import org.metadatacenter.util.test.TestHttpClient;

/**
 * Every field CEDAR keeps in two places, checked in both after each write that can change it.
 *
 * <p>An artifact is a document in the artifact store and a node in the graph, and nine of its fields
 * are written to both. Nothing held the two accounts to each other, and three separate defects lived
 * in that gap: a copy carried the source artifact's DOI into a document whose node had none, an
 * instance moved to another template kept the old one on its node, and a draft's predecessor was
 * written to the node and then dropped from the document. Each was found one at a time. The gap is
 * the same in all three, so the check belongs to the pair of stores rather than to any one command.
 *
 * <p>A row is one write path. After it, every mirrored field the artifact's type carries is read from
 * both stores and compared, and one run reports every divergence rather than the first. The artifact
 * server is a stub keyed by identifier, so the document side is exactly what the resource server sent
 * it; the graph is embedded.
 */
public class StoreConsistencyMatrixTest {

  private static final Map<String, ObjectNode> ARTIFACTS = new ConcurrentHashMap<>();
  private static final AtomicInteger REVISIONS = new AtomicInteger();
  private static HttpServer artifactServer;

  static {
    try {
      artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub artifact server", e);
    }
    artifactServer.createContext("/", StoreConsistencyMatrixTest::handleArtifactRequest);
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

  /**
   * One field held in both stores: where it lives in the document, where it lives on the node, and
   * which artifact types carry it at all.
   */
  private record Mirrored(String label,
                          Function<JsonNode, String> inDocument,
                          Function<FolderServerArtifact, String> onNode,
                          List<CedarResourceType> types) {
  }

  private static final List<CedarResourceType> BOTH =
      List.of(CedarResourceType.TEMPLATE, CedarResourceType.INSTANCE);
  private static final List<CedarResourceType> SCHEMA_ONLY = List.of(CedarResourceType.TEMPLATE);
  private static final List<CedarResourceType> INSTANCE_ONLY = List.of(CedarResourceType.INSTANCE);

  private static final List<Mirrored> MIRRORED = List.of(
      new Mirrored("schema:name", text(ModelNodeNames.SCHEMA_ORG_NAME), FolderServerArtifact::getName, BOTH),
      new Mirrored("schema:description", text(ModelNodeNames.SCHEMA_ORG_DESCRIPTION),
          FolderServerArtifact::getDescription, BOTH),
      new Mirrored("schema:identifier", text(ModelNodeNames.SCHEMA_ORG_IDENTIFIER),
          FolderServerArtifact::getIdentifier, BOTH),
      new Mirrored("pav:version", text(ModelNodeNames.PAV_VERSION),
          node -> versionOf(node), SCHEMA_ONLY),
      new Mirrored("bibo:status", text(ModelNodeNames.BIBO_STATUS),
          node -> statusOf(node), SCHEMA_ONLY),
      new Mirrored("pav:previousVersion", text(ModelNodeNames.PAV_PREVIOUS_VERSION),
          node -> previousVersionOf(node), SCHEMA_ONLY),
      new Mirrored("pav:derivedFrom", text(ModelNodeNames.PAV_DERIVED_FROM),
          node -> node.getDerivedFrom() == null ? null : node.getDerivedFrom().getId(), BOTH),
      new Mirrored("schema:isBasedOn", text(ModelNodeNames.SCHEMA_IS_BASED_ON),
          node -> isBasedOnOf(node), INSTANCE_ONLY),
      new Mirrored("the DOI annotation", document -> ModelUtil.extractDOIFromResource(document).getValue(),
          FolderServerArtifact::getDOI, BOTH));

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
  public void aCreatedTemplateIsDescribedTheSameWayByBothStores() throws Exception {
    String id = createTemplate("Consistency fixture", null);

    assertStoresAgree("create", id, CedarResourceType.TEMPLATE);
  }

  @Test
  public void anEditedTemplateIsDescribedTheSameWayByBothStores() throws Exception {
    String id = createTemplate("Consistency fixture, before the edit", null);

    ObjectNode edited = ARTIFACTS.get(id).deepCopy();
    edited.put(ModelNodeNames.SCHEMA_ORG_NAME, "Consistency fixture, edited");
    edited.put(ModelNodeNames.SCHEMA_ORG_DESCRIPTION, "A description the edit introduced");
    edited.put(ModelNodeNames.SCHEMA_ORG_IDENTIFIER, "CDE-4242");
    HttpResponse<String> response = put("/templates/" + encode(id), edited.toString(), anyEtag());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    assertStoresAgree("update", id, CedarResourceType.TEMPLATE);
  }

  @Test
  public void aCopiedTemplateIsDescribedTheSameWayByBothStores() throws Exception {
    String sourceId = createTemplate("Consistency copy source", null);
    setDoi(sourceId, "10.5072/FK2-consistency-source");

    HttpResponse<String> copied = post("/command/copy-artifact-to-folder",
        "{\"@id\":\"" + sourceId + "\",\"targetFolderId\":\"" + homeFolderId.getId()
            + "\",\"nameTemplate\":\"Copy of {{name}}\"}");

    Assertions.assertEquals(201, copied.statusCode(), copied.body());
    String copyId = JsonMapper.STRICT_MAPPER.readTree(copied.body()).path("@id").asText();
    assertStoresAgree("copy", copyId, CedarResourceType.TEMPLATE);
    assertStoresAgree("copy, at the source", sourceId, CedarResourceType.TEMPLATE);
  }

  @Test
  public void aMintedDoiIsDescribedTheSameWayByBothStores() throws Exception {
    String id = createTemplate("Consistency DOI fixture", null);

    setDoi(id, "10.5072/FK2-consistency-minted");

    assertStoresAgree("set the DOI", id, CedarResourceType.TEMPLATE);
  }

  @Test
  public void aPublishedTemplateAndItsDraftAreDescribedTheSameWayByBothStores() throws Exception {
    String id = createTemplate("Consistency version fixture", null);

    HttpResponse<String> published = post("/command/publish-artifact",
        "{\"@id\":\"" + id + "\",\"newVersion\":\"1.0.0\"}");
    Assertions.assertEquals(200, published.statusCode(), published.body());
    assertStoresAgree("publish", id, CedarResourceType.TEMPLATE);

    HttpResponse<String> drafted = post("/command/create-draft-artifact",
        "{\"@id\":\"" + id + "\",\"newVersion\":\"1.0.1\",\"folderId\":\"" + homeFolderId.getId()
            + "\",\"propagateSharing\":false,\"newFolderName\":\"\"}");
    Assertions.assertEquals(201, drafted.statusCode(), drafted.body());
    String draftId = JsonMapper.STRICT_MAPPER.readTree(drafted.body()).path("@id").asText();
    assertStoresAgree("create a draft", draftId, CedarResourceType.TEMPLATE);
    assertStoresAgree("create a draft, at the source", id, CedarResourceType.TEMPLATE);
  }

  @Test
  public void aTemplateDraftedFromAPublishedOneIsDescribedTheSameWayByBothStores() throws Exception {
    String id = createTemplate("Consistency publish-and-draft fixture", "10.5072/FK2-consistency-published");

    HttpResponse<String> response = post("/command/publish-create-draft-template/" + encode(id),
        templateDocument("Consistency publish-and-draft fixture, edited", null).toString());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    String draftId = JsonMapper.STRICT_MAPPER.readTree(response.body()).path("@id").asText();
    assertStoresAgree("publish and draft", draftId, CedarResourceType.TEMPLATE);
    assertStoresAgree("publish and draft, at the source", id, CedarResourceType.TEMPLATE);
  }

  @Test
  public void anInstanceIsDescribedTheSameWayByBothStoresThroughATemplateChange() throws Exception {
    URI firstTemplate = URI.create(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    URI secondTemplate = URI.create(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));

    HttpResponse<String> created = post("/template-instances?folder_id=" + encode(homeFolderId.getId()),
        instanceDocument("Consistency instance", firstTemplate, null).toString());
    Assertions.assertEquals(201, created.statusCode(), created.body());
    String id = JsonMapper.STRICT_MAPPER.readTree(created.body()).path("@id").asText();
    assertStoresAgree("create an instance", id, CedarResourceType.INSTANCE);

    HttpResponse<String> moved = put("/template-instances/" + encode(id),
        instanceDocument("Consistency instance", secondTemplate, id).toString(), anyEtag());
    Assertions.assertEquals(200, moved.statusCode(), moved.body());
    assertStoresAgree("change an instance's template", id, CedarResourceType.INSTANCE);
  }

  /** Reads every mirrored field the type carries from both stores and reports all that disagree. */
  private static void assertStoresAgree(String writePath, String artifactId, CedarResourceType type) {
    JsonNode document = ARTIFACTS.get(artifactId);
    Assertions.assertNotNull(document, writePath + ": the artifact store holds no " + artifactId);
    FolderServerArtifact node = folderSession.findArtifactById(CedarArtifactId.build(artifactId, type));
    Assertions.assertNotNull(node, writePath + ": the graph holds no " + artifactId);

    List<String> divergences = new ArrayList<>();
    for (Mirrored field : MIRRORED) {
      if (!field.types().contains(type)) {
        continue;
      }
      String stored = emptyToNull(field.inDocument().apply(document));
      String recorded = emptyToNull(field.onNode().apply(node));
      if (!java.util.Objects.equals(stored, recorded)) {
        divergences.add("  " + field.label() + ": the document says " + stored
            + ", the graph node says " + recorded);
      }
    }
    Assertions.assertTrue(divergences.isEmpty(),
        "After " + writePath + ", the two stores describe " + artifactId + " differently:\n"
            + String.join("\n", divergences));
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static Function<JsonNode, String> text(String key) {
    return document -> document.path(key).isTextual() ? document.get(key).asText() : null;
  }

  private static String versionOf(FolderServerArtifact node) {
    return node instanceof FolderServerSchemaArtifact schema && schema.getVersion() != null
        ? schema.getVersion().getValue() : null;
  }

  private static String statusOf(FolderServerArtifact node) {
    return node instanceof FolderServerSchemaArtifact schema && schema.getPublicationStatus() != null
        ? schema.getPublicationStatus().getValue() : null;
  }

  private static String previousVersionOf(FolderServerArtifact node) {
    return node instanceof FolderServerSchemaArtifact schema && schema.getPreviousVersion() != null
        ? schema.getPreviousVersion().getId() : null;
  }

  private static String isBasedOnOf(FolderServerArtifact node) {
    return node instanceof FolderServerInstance instance && instance.getIsBasedOn() != null
        ? instance.getIsBasedOn().getId() : null;
  }

  private static String createTemplate(String name, String doi) throws Exception {
    HttpResponse<String> created = post("/templates?folder_id=" + encode(homeFolderId.getId()),
        templateDocument(name, null).toString());
    Assertions.assertEquals(201, created.statusCode(), created.body());
    String id = JsonMapper.STRICT_MAPPER.readTree(created.body()).path("@id").asText();
    if (doi != null) {
      setDoi(id, doi);
    }
    return id;
  }

  private static void setDoi(String artifactId, String doi) throws Exception {
    HttpResponse<String> response = post("/command/annotations/doi",
        "{\"@id\":\"" + artifactId + "\",\"doi\":\"" + doi + "\"}");
    Assertions.assertEquals(200, response.statusCode(), response.body());
  }

  private static ObjectNode templateDocument(String name, String doi) {
    TemplateSchemaArtifact.Builder builder = TemplateSchemaArtifact.builder()
        .withName(name)
        .withVersion(Version.fromString("0.0.1"))
        .withStatus(Status.DRAFT);
    if (doi != null) {
      builder.withAnnotations(Annotations.builder()
          .withIriAnnotation(ModelNodeNames.DATACITE_DOI_URI, URI.create(doi))
          .build());
    }
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(builder.build());
  }

  private static ObjectNode instanceDocument(String name, URI templateId, String instanceId) {
    TemplateInstanceArtifact.Builder builder = TemplateInstanceArtifact.builder()
        .withName(name)
        .withIsBasedOn(templateId);
    if (instanceId != null) {
      builder.withJsonLdId(URI.create(instanceId));
    }
    return new JsonArtifactRenderer().renderTemplateInstanceArtifact(builder.build());
  }

  /**
   * The stub does not version what it stores, and this suite is not about preconditions. The
   * wildcard is what a caller sends when it means "whatever is there now", and the routes under test
   * require a validator rather than a particular one.
   */
  private static String anyEtag() {
    return "*";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json");
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    return TestHttpClient.send(request.PUT(HttpRequest.BodyPublishers.ofString(body)).build());
  }

  /** The stub keeps every artifact it is given, keyed by identifier, and answers by identifier. */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    // The identifier is an absolute IRI carrying slashes of its own, so it is everything after the
    // collection, not everything after the last slash.
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
      String mintedId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(resourceTypeForPath(path));
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

  private static CedarResourceType resourceTypeForPath(String path) {
    return path.startsWith("/template-instances") ? CedarResourceType.INSTANCE : CedarResourceType.TEMPLATE;
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
