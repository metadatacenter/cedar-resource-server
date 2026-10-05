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
import org.metadatacenter.util.artifact.ArtifactYamlTranscoder;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Every way a client can ask the resource server for an artifact, for each kind of artifact, in each
 * state the artifact server can hold it in; and every body the commands that read one can be sent.
 *
 * <p>The resource server produces every representation but JSON itself, from the JSON the artifact
 * server stores, and it did so in five places that each decided for themselves. The four downloads
 * parsed the Accept header as a string: a wildcard such as {@code application/*} was refused as an
 * invalid resource type, a preference for YAML over JSON was read as a request for JSON, and YAML was
 * labelled {@code application/x-yaml} whichever YAML type was asked for. A YAML read of an artifact the
 * artifact library could not read answered 500, even when the request accepted the JSON there was to
 * serve, and an instance read refused an Accept naming the N-Quads its format parameter produces.
 *
 * <p>A read must answer with the representation the Accept header prefers among those the server can
 * produce, labelled as what it is, varying on Accept; or 406 when there is none. A YAML read of an
 * unreadable artifact falls back to JSON where the header admits JSON, and is refused, saying why,
 * where it does not. A refusal the artifact server gave is relayed as the JSON it is.
 *
 * <p>The validate command must check the document a write would store, and a versioning command must
 * refuse a body that is not a template it can read rather than fail on it.
 */
public class RepresentationMatrixTest {

  private static final Map<String, ObjectNode> DOCUMENTS = new ConcurrentHashMap<>();
  private static final Map<String, Long> REVISIONS = new ConcurrentHashMap<>();
  private static final AtomicLong NEXT_REVISION = new AtomicLong(1);
  private static final AtomicBoolean OUTAGE = new AtomicBoolean();
  /** The last body the resource server sent to the validate command, and to a create. */
  private static final AtomicReference<JsonNode> VALIDATED = new AtomicReference<>();
  private static final AtomicReference<JsonNode> CREATED = new AtomicReference<>();
  /** The requests that changed a document, as method and path. */
  private static final List<String> WRITES = java.util.Collections.synchronizedList(new ArrayList<>());

  private static HttpServer artifactServer;

  static {
    try {
      artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub artifact server", e);
    }
    artifactServer.createContext("/", RepresentationMatrixTest::handleArtifactRequest);
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

  /** A representation a read can answer with, by the media type it is labelled with. */
  private enum Representation {
    JSON("application/json"), YAML("application/yaml"), X_YAML("application/x-yaml"),
    NQUADS("application/n-quads"), NONE(null);

    private final String mediaType;

    Representation(String mediaType) {
      this.mediaType = mediaType;
    }

    boolean isYaml() {
      return this == YAML || this == X_YAML;
    }
  }

  /**
   * An Accept header, with the representation a schema artifact and an instance should be read in,
   * and whether the header admits JSON at all. A null header sends none.
   */
  private record Accept(String header, Representation schema, Representation instance, boolean admitsJson) {
    @Override
    public String toString() {
      return header == null ? "no Accept" : "Accept " + header;
    }
  }

  private static final List<Accept> ACCEPTS = List.of(
      new Accept(null, Representation.JSON, Representation.JSON, true),
      new Accept("*/*", Representation.JSON, Representation.JSON, true),
      new Accept("application/*", Representation.JSON, Representation.JSON, true),
      new Accept("application/json", Representation.JSON, Representation.JSON, true),
      new Accept("application/yaml", Representation.YAML, Representation.YAML, false),
      new Accept("application/x-yaml", Representation.X_YAML, Representation.X_YAML, false),
      new Accept("application/yaml, application/json;q=0.5", Representation.YAML, Representation.YAML, true),
      new Accept("application/yaml, */*;q=0.1", Representation.YAML, Representation.YAML, true),
      new Accept("application/json;q=0.5, application/yaml", Representation.YAML, Representation.YAML, true),
      new Accept("application/n-quads", Representation.NONE, Representation.NQUADS, false),
      new Accept("application/n-quads, application/json;q=0.5", Representation.JSON, Representation.NQUADS, true),
      new Accept("text/html", Representation.NONE, Representation.NONE, false),
      new Accept("text/html, */*;q=0.8", Representation.JSON, Representation.JSON, true));

  private static final List<CedarResourceType> KINDS = List.of(
      CedarResourceType.TEMPLATE, CedarResourceType.ELEMENT, CedarResourceType.FIELD, CedarResourceType.INSTANCE);

  /**
   * How the artifact a read names is held: in both stores, readable or in a form the artifact library
   * can not read; in the graph with its document gone; in neither; or behind an artifact server that
   * does not answer.
   */
  private enum Stored { READABLE, UNREADABLE, DOCUMENT_GONE, ABSENT, OUTAGE }

  /** A way to read an artifact: a GET of it, or of its download. Only the GET of an instance has N-Quads. */
  private enum Endpoint { GET, DOWNLOAD }

  private static final String TEMPLATE_YAML = """
      type: template
      name: Representation Matrix Template
      children:
        - key: filled
          type: text-field
          name: Filled
        - key: omitted
          type: text-field
          name: Omitted
      """;

  private static final Map<CedarResourceType, Map<Stored, String>> FIXTURES = new EnumMap<>(CedarResourceType.class);
  private static String templateId;

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

    templateId = create(CedarResourceType.TEMPLATE);
    for (CedarResourceType kind : KINDS) {
      Map<Stored, String> ids = new EnumMap<>(Stored.class);
      ids.put(Stored.READABLE, create(kind));
      ids.put(Stored.OUTAGE, ids.get(Stored.READABLE));
      String unreadable = create(kind);
      DOCUMENTS.get(unreadable).put(ModelNodeNames.PAV_CREATED_ON, "yesterday");
      ids.put(Stored.UNREADABLE, unreadable);
      String gone = create(kind);
      DOCUMENTS.remove(gone);
      ids.put(Stored.DOCUMENT_GONE, gone);
      ids.put(Stored.ABSENT, cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(kind));
      FIXTURES.put(kind, ids);
    }
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    artifactServer.stop(0);
  }

  @AfterEach
  public void restore() {
    OUTAGE.set(false);
  }

  static Stream<Arguments> reads() {
    List<Arguments> cases = new ArrayList<>();
    for (Endpoint endpoint : Endpoint.values()) {
      for (CedarResourceType kind : KINDS) {
        for (Stored stored : Stored.values()) {
          for (Accept accept : ACCEPTS) {
            cases.add(Arguments.of(endpoint, kind.getValue(), stored, accept));
          }
        }
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0} of a {1} stored {2}, with {3}")
  @MethodSource("reads")
  public void aReadAnswersWithTheRepresentationTheClientPrefersAndTheServerCanProduce(
      Endpoint endpoint, String kindName, Stored stored, Accept accept) throws Exception {
    CedarResourceType kind = CedarResourceType.forValue(kindName);
    String id = FIXTURES.get(kind).get(stored);
    String path = "/" + kind.getPrefix() + "/" + encode(id) + (endpoint == Endpoint.DOWNLOAD ? "/download" : "");

    Representation preferred = endpoint == Endpoint.GET && kind == CedarResourceType.INSTANCE
        ? accept.instance() : accept.schema();
    Representation expected = preferred;
    if (stored == Stored.UNREADABLE && preferred.isYaml()) {
      expected = accept.admitsJson() ? Representation.JSON : Representation.NONE;
    }

    OUTAGE.set(stored == Stored.OUTAGE);
    HttpRequest.Builder request = request(path).GET();
    if (accept.header() != null) {
      request.header("Accept", accept.header());
    }
    HttpResponse<String> response = TestHttpClient.send(request.build());
    OUTAGE.set(false);
    String body = response.body();
    String mediaType = response.headers().firstValue("Content-Type").map(RepresentationMatrixTest::stripParameters)
        .orElse(null);

    if (expected == Representation.NONE) {
      Assertions.assertEquals(406, response.statusCode(), body);
      Assertions.assertEquals("application/json", mediaType, "a refusal is the JSON error envelope: " + body);
      if (stored == Stored.UNREADABLE && preferred.isYaml()) {
        Assertions.assertTrue(body.contains("no YAML form"), "the refusal should say why: " + body);
      }
      return;
    }
    int failure = switch (stored) {
      case ABSENT, DOCUMENT_GONE -> 404;
      case OUTAGE -> 503;
      default -> 0;
    };
    if (failure != 0) {
      Assertions.assertEquals(failure, response.statusCode(), body);
      Assertions.assertEquals("application/json", mediaType, "an error is the JSON error envelope: " + body);
      return;
    }

    Assertions.assertEquals(200, response.statusCode(), body);
    Assertions.assertEquals(expected.mediaType, mediaType, "the representation is labelled as what it is");
    Assertions.assertTrue(response.headers().allValues("Vary").stream().anyMatch(vary -> vary.contains("Accept")),
        "a negotiated read varies on Accept: " + response.headers().map());
    long revision = REVISIONS.get(id);
    if (endpoint == Endpoint.GET) {
      String suffix = switch (expected) {
        case YAML, X_YAML -> "-yaml";
        case NQUADS -> "-rdf-nquad";
        default -> "";
      };
      Assertions.assertEquals("\"" + revision + suffix + "\"", response.headers().firstValue("ETag").orElse(null),
          "the validator names the representation served");
    } else {
      String extension = expected == Representation.JSON ? ".json" : ".yaml";
      Assertions.assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains(extension),
          "the file is named for what it holds: " + response.headers().firstValue("Content-Disposition"));
    }
    switch (expected) {
      case JSON -> Assertions.assertEquals(id, JsonMapper.STRICT_MAPPER.readTree(body).path("@id").asText());
      case YAML, X_YAML -> Assertions.assertTrue(body.startsWith("type:"), "a YAML artifact opens with its type: " + head(body));
      case NQUADS -> Assertions.assertTrue(body.contains("<" + id + ">"), "the N-Quads describe the instance: " + head(body));
      default -> Assertions.fail("no representation to check");
    }
  }

  /** A YAML body sent to the validate command and to a create of the same kind. */
  private record YamlBody(String label, CedarResourceType kind, String yaml) {
    @Override
    public String toString() {
      return label;
    }
  }

  static Stream<Arguments> yamlBodies() {
    return Stream.of(
        new YamlBody("a template", CedarResourceType.TEMPLATE, TEMPLATE_YAML),
        new YamlBody("an element", CedarResourceType.ELEMENT, "type: element\nname: Representation Matrix Element\n"),
        new YamlBody("a field", CedarResourceType.FIELD, "type: text-field\nname: Representation Matrix Field\n"),
        new YamlBody("an instance carrying one of its two fields", CedarResourceType.INSTANCE, "ONE"),
        new YamlBody("an instance carrying both fields", CedarResourceType.INSTANCE, "BOTH")
    ).map(Arguments::of);
  }

  @ParameterizedTest(name = "{0} in YAML")
  @MethodSource("yamlBodies")
  public void theValidateCommandChecksTheDocumentAWriteWouldStore(YamlBody body) throws Exception {
    String yaml = switch (body.yaml()) {
      case "ONE" -> instanceYaml(templateId, false);
      case "BOTH" -> instanceYaml(templateId, true);
      default -> body.yaml();
    };
    VALIDATED.set(null);
    CREATED.set(null);
    HttpResponse<String> validated = TestHttpClient.send(request("/command/validate?resource_type=" + body.kind().getValue())
        .header("Content-Type", "application/yaml").POST(HttpRequest.BodyPublishers.ofString(yaml)).build());
    Assertions.assertEquals(200, validated.statusCode(), validated.body());
    HttpResponse<String> created = TestHttpClient.send(request("/" + body.kind().getPrefix() + "?folder_id="
        + encode(homeFolderId.getId()))
        .header("Content-Type", "application/yaml").POST(HttpRequest.BodyPublishers.ofString(yaml)).build());
    Assertions.assertEquals(201, created.statusCode(), created.body());

    Assertions.assertEquals(CREATED.get(), VALIDATED.get(),
        "the command validated a document other than the one the write stores");
  }

  /** A body sent to the two commands that compare a submitted template with the stored one. */
  private record TemplateBody(String label, String body) {
    @Override
    public String toString() {
      return label;
    }
  }

  private static final String READABLE_TEMPLATE = "the stored template, renamed";

  static Stream<Arguments> versioningBodies() {
    List<TemplateBody> bodies = List.of(
        new TemplateBody(READABLE_TEMPLATE, null),
        new TemplateBody("a template the artifact library can not read", "UNREADABLE"),
        new TemplateBody("an empty JSON object", "{}"),
        new TemplateBody("a JSON array", "[]"),
        new TemplateBody("a JSON string", "\"template\""),
        new TemplateBody("JSON null", "null"),
        new TemplateBody("malformed JSON", "{\"schema:name\":"),
        new TemplateBody("no body", ""));
    List<Arguments> cases = new ArrayList<>();
    for (TemplateBody body : bodies) {
      cases.add(Arguments.of("check-update", false, body));
      cases.add(Arguments.of("check-update", true, body));
      cases.add(Arguments.of("publish-create-draft", false, body));
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}, instances {1}, sent {2}")
  @MethodSource("versioningBodies")
  public void aVersioningCommandRefusesABodyThatIsNotATemplate(String command, boolean withInstance,
                                                             TemplateBody body) throws Exception {
    String id = create(CedarResourceType.TEMPLATE);
    if (withInstance) {
      createInstanceOf(id);
    }
    String content = body.body();
    if (content == null || content.equals("UNREADABLE")) {
      ObjectNode template = DOCUMENTS.get(id).deepCopy();
      template.put(ModelNodeNames.SCHEMA_ORG_NAME, "Representation Matrix Template, renamed");
      if (content != null) {
        template.put(ModelNodeNames.PAV_CREATED_ON, "yesterday");
      }
      content = template.toString();
    }
    ObjectNode before = DOCUMENTS.get(id).deepCopy();
    WRITES.clear();

    String path = command.equals("check-update")
        ? "/command/check-update-template/" + encode(id)
        : "/command/publish-create-draft-template/" + encode(id);
    HttpResponse<String> response = TestHttpClient.send(request(path)
        .header("Content-Type", "application/json").header("If-Match", "*")
        .POST(HttpRequest.BodyPublishers.ofString(content)).build());

    if (body.label().equals(READABLE_TEMPLATE)) {
      Assertions.assertEquals(200, response.statusCode(), response.body());
      return;
    }
    Assertions.assertEquals(400, response.statusCode(), command + " answered " + response.body());
    Assertions.assertEquals("application/json",
        response.headers().firstValue("Content-Type").map(RepresentationMatrixTest::stripParameters).orElse(null));
    Assertions.assertEquals(before, DOCUMENTS.get(id), "the stored template changed");
    Assertions.assertEquals(List.of(), WRITES, "a refused command wrote to the artifact server");
  }

  // Fixtures

  private static String create(CedarResourceType kind) throws Exception {
    String json = switch (kind) {
      case TEMPLATE -> ArtifactYamlTranscoder.yamlToJsonString(TEMPLATE_YAML, kind);
      case ELEMENT -> ArtifactYamlTranscoder.yamlToJsonString("type: element\nname: Representation Matrix Element\n", kind);
      case FIELD -> ArtifactYamlTranscoder.yamlToJsonString("type: text-field\nname: Representation Matrix Field\n", kind);
      case INSTANCE -> ArtifactYamlTranscoder.yamlToJsonString(instanceYaml(templateId, true), kind,
          iri -> DOCUMENTS.get(iri));
      default -> throw new IllegalArgumentException(kind.getValue());
    };
    HttpResponse<String> created = TestHttpClient.send(request("/" + kind.getPrefix() + "?folder_id="
        + encode(homeFolderId.getId()))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
    Assertions.assertEquals(201, created.statusCode(), created.body());
    return JsonMapper.STRICT_MAPPER.readTree(created.body()).path("@id").asText();
  }

  private static void createInstanceOf(String template) throws Exception {
    String json = ArtifactYamlTranscoder.yamlToJsonString(instanceYaml(template, true), CedarResourceType.INSTANCE,
        iri -> DOCUMENTS.get(iri));
    HttpResponse<String> created = TestHttpClient.send(request("/" + CedarResourceType.INSTANCE.getPrefix()
        + "?folder_id=" + encode(homeFolderId.getId()))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
    Assertions.assertEquals(201, created.statusCode(), created.body());
  }

  private static String instanceYaml(String template, boolean both) {
    return "type: instance\nname: Representation Matrix Instance\nisBasedOn: " + template
        + "\nchildren:\n  filled:\n    value: Alice\n" + (both ? "  omitted:\n    value: Bob\n" : "");
  }

  private static HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String stripParameters(String mediaType) {
    int semicolon = mediaType.indexOf(';');
    return (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).trim();
  }

  private static String head(String body) {
    return body.length() <= 300 ? body : body.substring(0, 300) + "...";
  }

  // The stub artifact server

  private static final List<CedarResourceType> COLLECTIONS = List.of(
      CedarResourceType.TEMPLATE, CedarResourceType.ELEMENT, CedarResourceType.FIELD, CedarResourceType.INSTANCE);

  /**
   * Keeps every document it is given, keyed by identifier, and answers as the artifact server does:
   * JSON by default, N-Quads for an instance asked for with format=rdf-nquad, and a validator naming
   * the revision and the representation. When an outage is armed, it reads nothing.
   */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getQuery();

    if (path.equals("/command/validate")) {
      VALIDATED.set(JsonMapper.STRICT_MAPPER.readTree(requestBody));
      respond(exchange, 200, "application/json", "{\"validates\":\"true\",\"warnings\":[],\"errors\":[]}", null);
      return;
    }
    CedarResourceType kind = COLLECTIONS.stream().filter(k -> path.startsWith("/" + k.getPrefix())).findFirst()
        .orElse(null);
    if (kind == null) {
      respond(exchange, 404, "application/json", "{\"message\":\"No such route\"}", null);
      return;
    }
    String collection = "/" + kind.getPrefix() + "/";
    String artifactId = path.startsWith(collection) && path.length() > collection.length()
        ? cedarConfig.getLinkedDataUtil().resolveResourceId(kind,
            URLDecoder.decode(path.substring(collection.length()), StandardCharsets.UTF_8))
        : null;

    if ("GET".equals(method) && OUTAGE.get()) {
      // No answer: the connection closes before a status line, as a server that went down would.
      exchange.close();
      return;
    }
    if ("POST".equals(method) && artifactId == null) {
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      CREATED.set(created.deepCopy());
      String mintedId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(kind);
      created.put(ModelNodeNames.JSON_LD_ID, mintedId);
      DOCUMENTS.put(mintedId, created);
      REVISIONS.put(mintedId, NEXT_REVISION.getAndIncrement());
      WRITES.add(method + " " + path);
      respond(exchange, 201, "application/json", created.toString(), mintedId);
      return;
    }
    if ("PUT".equals(method) && artifactId != null) {
      ObjectNode replacement = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      DOCUMENTS.put(artifactId, replacement);
      REVISIONS.put(artifactId, NEXT_REVISION.getAndIncrement());
      WRITES.add(method + " " + path);
      respond(exchange, 200, "application/json", replacement.toString(), artifactId);
      return;
    }
    if ("GET".equals(method) && artifactId != null && DOCUMENTS.containsKey(artifactId)) {
      ObjectNode document = DOCUMENTS.get(artifactId);
      if (query != null && query.contains("format=rdf-nquad")) {
        respond(exchange, 200, "application/n-quads",
            "<" + artifactId + "> <http://schema.org/name> \"" + document.path("schema:name").asText() + "\" .\n",
            artifactId, "-rdf-nquad");
        return;
      }
      respond(exchange, 200, "application/json", document.toString(), artifactId);
      return;
    }
    if ("DELETE".equals(method) && artifactId != null) {
      DOCUMENTS.remove(artifactId);
      WRITES.add(method + " " + path);
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    respond(exchange, 404, "application/json", "{\"message\":\"The artifact can not be found\",\"statusCode\":404}", null);
  }

  private static void respond(HttpExchange exchange, int status, String type, String body, String artifactId)
      throws IOException {
    respond(exchange, status, type, body, artifactId, "");
  }

  private static void respond(HttpExchange exchange, int status, String type, String body, String artifactId,
                              String representation) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    if (artifactId != null && REVISIONS.containsKey(artifactId)) {
      exchange.getResponseHeaders().set("ETag", "\"" + REVISIONS.get(artifactId) + representation + "\"");
      exchange.getResponseHeaders().set("Vary", "Accept");
      if (status == 201) {
        exchange.getResponseHeaders().set("Location", artifactId);
      }
    }
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
