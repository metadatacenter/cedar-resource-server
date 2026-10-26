package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.metadatacenter.artifacts.model.core.Annotations;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarArtifactId;
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
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Endpoint tests for commands that copy artifacts through the artifact service. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
public class CommandFileSystemResourceTest {

  private static final String SOURCE_NAME = "Named source artifact";
  private static final String COPIED_NAME = "Copy of " + SOURCE_NAME;
  private static final String SOURCE_DOI = "https://doi.org/10.5072/FK2-source-artifact";

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static HttpServer artifactServer;
  private static String authHeader;
  private static CedarFolderId homeFolderId;
  private static CedarFolderId moveDestinationId;
  private static FolderServerArtifact sourceArtifact;
  private static String missingArtifactId;
  private static JsonNode postedArtifact;
  private static CedarConfig cedarConfig;
  private static FolderServiceSession folderSession;
  private static NoOpNodeIndexingService nodeIndexingService;
  private static int sourceGetStatus = 200;
  private static boolean omitSourceGetBody;
  private static String receivedPutIfMatch;
  /**
   * Identities a case chose for a copy. The stub answers for these as the artifact server does, from
   * what is stored at them, and answers everything else as before.
   */
  private static final java.util.Set<String> CHOSEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final Map<String, ObjectNode> STORED_AT_CHOSEN = new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicInteger CREATES_AT_CHOSEN =
      new java.util.concurrent.atomic.AtomicInteger();
  private static final java.util.concurrent.atomic.AtomicInteger DELETES_AT_CHOSEN =
      new java.util.concurrent.atomic.AtomicInteger();
  /** The next create at a chosen identity is stored, and its answer never sent. */
  private static volatile boolean losingTheNextCreateAnswer;
  /**
   * The next create at a chosen identity loses its answer and is held back, so that reading the
   * identity finds nothing until a case stores what was held.
   */
  private static volatile boolean holdingTheNextCreate;
  private static final Map<String, ObjectNode> HELD_CREATES = new java.util.concurrent.ConcurrentHashMap<>();

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    artifactServer.createContext("/", CommandFileSystemResourceTest::handleArtifactRequest);
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

    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);
    missingArtifactId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    nodeIndexingService = new NoOpNodeIndexingService(cedarConfig);
    AbstractResourceServerResource.injectServices(
        nodeIndexingService,
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig));

    CedarRequestContext userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    homeFolderId = folderSession.findHomeFolderOf().getResourceId();
    org.metadatacenter.model.folderserver.basic.FolderServerFolder moveDestination =
        new org.metadatacenter.model.folderserver.basic.FolderServerFolder();
    moveDestination.setName("Move command destination");
    moveDestination.setDescription("Conditional move fixture");
    moveDestinationId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    Assertions.assertNotNull(folderSession.createFolderAsChildOfId(
        moveDestination, homeFolderId, moveDestinationId));

    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName(SOURCE_NAME);
    template.setDescription("Copy command regression fixture");
    template.setVersion("0.0.1");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    template.setDOI(SOURCE_DOI);
    sourceArtifact = folderSession.createResourceAsChildOfId(template, homeFolderId);
    Assertions.assertNotNull(sourceArtifact);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    if (artifactServer != null) {
      artifactServer.stop(0);
    }
  }

  @Test
  @Order(0)
  public void artifactRepresentationsKeepDistinctStrongEtagsThroughTheResourceServer() throws Exception {
    String encodedId = java.net.URLEncoder.encode(sourceArtifact.getId(), StandardCharsets.UTF_8);
    URI uri = URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/" + encodedId);

    HttpResponse<String> json = CLIENT.send(HttpRequest.newBuilder(uri)
        .header("Authorization", authHeader)
        .header("Accept", "application/json")
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> yaml = CLIENT.send(HttpRequest.newBuilder(uri)
        .header("Authorization", authHeader)
        .header("Accept", "application/yaml")
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> compact = CLIENT.send(HttpRequest.newBuilder(
            URI.create(uri + "?compact=true"))
        .header("Authorization", authHeader)
        .header("Accept", "application/yaml")
        .GET().build(), HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, json.statusCode(), json.body());
    Assertions.assertEquals("\"1\"", json.headers().firstValue("ETag").orElse(null));
    Assertions.assertEquals("\"1-yaml\"", yaml.headers().firstValue("ETag").orElse(null));
    Assertions.assertEquals("\"1-yaml-compact-v2\"", compact.headers().firstValue("ETag").orElse(null));
    Assertions.assertTrue(yaml.headers().firstValue("Vary").orElse("").contains("Accept"));
  }

  @Test
  @Order(1)
  public void copyInterpolatesTheSourceArtifactName() throws Exception {
    String body = "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\","
        + "\"nameTemplate\":\"Copy of {{name}}\"}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/copy-artifact-to-folder"))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(201, response.statusCode(), response.body());
    Assertions.assertNotNull(postedArtifact, "the copy should be posted to the artifact service");
    Assertions.assertEquals(COPIED_NAME, postedArtifact.get("schema:name").asText());
    Assertions.assertEquals(COPIED_NAME,
        JsonMapper.STRICT_MAPPER.readTree(response.body()).get("schema:name").asText());
  }

  /**
   * A DOI identifies the artifact it was minted for. The copy is a different artifact, and its graph
   * node records no DOI, so a copy that kept the annotation would misidentify itself and fail the
   * consistency check on its first ordinary edit.
   */
  @Test
  @Order(2)
  public void copyDropsTheSourceDoi() throws Exception {
    postedArtifact = null;

    HttpResponse<String> response = postCommand("copy-artifact-to-folder", copyBody());

    Assertions.assertEquals(201, response.statusCode(), response.body());
    Assertions.assertNotNull(postedArtifact, "the copy should be posted to the artifact service");
    Assertions.assertFalse(
        postedArtifact.path(ModelNodeNames.ANNOTATIONS).has(ModelNodeNames.DATACITE_DOI_URI),
        "the copy carries the source artifact's DOI: " + postedArtifact);

    String copyId = JsonMapper.STRICT_MAPPER.readTree(response.body()).path("@id").asText();
    FolderServerArtifact copy = folderSession.findArtifactById(
        CedarArtifactId.build(copyId, CedarResourceType.TEMPLATE));
    Assertions.assertNotNull(copy, response.body());
    Assertions.assertNull(copy.getDOI(), "the copy's graph record was given a DOI");
  }

  @Test
  @Order(3)
  public void sourceFetchErrorsAreReturnedWithoutPostingACopy() throws Exception {
    try {
      for (int status : new int[]{404, 500}) {
        sourceGetStatus = status;
        postedArtifact = null;

        HttpResponse<String> response = postCommand("copy-artifact-to-folder", copyBody());

        Assertions.assertEquals(status, response.statusCode(), response.body());
        Assertions.assertEquals("{\"status\":" + status + "}", response.body());
        Assertions.assertNull(postedArtifact,
            "a source fetch error must not be posted to the artifact service");
      }
    } finally {
      sourceGetStatus = 200;
    }
  }

  @Test
  @Order(4)
  public void emptySourceFetchReturnsBadGatewayWithoutPostingACopy() throws Exception {
    try {
      omitSourceGetBody = true;
      postedArtifact = null;

      HttpResponse<String> response = postCommand("copy-artifact-to-folder", copyBody());

      Assertions.assertEquals(502, response.statusCode(), response.body());
      JsonNode error = JsonMapper.STRICT_MAPPER.readTree(response.body());
      Assertions.assertEquals("BAD_GATEWAY", error.path("status").asText(), response.body());
      Assertions.assertEquals("Artifact service returned an empty source artifact",
          error.path("message").asText(), response.body());
      Assertions.assertNull(postedArtifact,
          "an empty source response must not be posted to the artifact service");
    } finally {
      omitSourceGetBody = false;
    }
  }

  @Test
  @Order(5)
  public void moveMissingResourceReturnsNotFound() throws Exception {
    String body = "{\"@id\":\"" + missingArtifactId + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\"}";

    HttpResponse<String> response = postCommand("move-resource-to-folder", body);

    Assertions.assertEquals(404, response.statusCode(), response.body());
  }

  @Test
  @Order(6)
  public void moveRequiresCurrentSourceEtagAndReturnsTheReplacement() throws Exception {
    String body = "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + moveDestinationId.getId() + "\"}";

    HttpResponse<String> missing = postCommand("move-resource-to-folder", body);
    Assertions.assertEquals(428, missing.statusCode(), missing.body());

    HttpResponse<String> stale = postCommand("move-resource-to-folder", body, "\"0\"");
    Assertions.assertEquals(412, stale.statusCode(), stale.body());
    Assertions.assertEquals("\"1\"", JsonMapper.STRICT_MAPPER.readTree(stale.body())
        .path("parameters").path("currentETag").asText());

    HttpResponse<String> moved = postCommand("move-resource-to-folder", body, "\"1\"");
    Assertions.assertEquals(201, moved.statusCode(), moved.body());
    Assertions.assertEquals("\"2\"", moved.headers().firstValue("ETag").orElse(null));

    HttpResponse<String> staleAfterMove = postCommand("move-resource-to-folder", body, "\"1\"");
    Assertions.assertEquals(412, staleAfterMove.statusCode(), staleAfterMove.body());

    HttpResponse<String> wildcard = postCommand("move-resource-to-folder", body, "*");
    Assertions.assertEquals(201, wildcard.statusCode(), wildcard.body());
    Assertions.assertEquals("\"3\"", wildcard.headers().firstValue("ETag").orElse(null));
  }

  @Test
  @Order(7)
  public void renameMissingResourceReturnsNotFound() throws Exception {
    String body = "{\"@id\":\"" + missingArtifactId + "\","
        + "\"schema:name\":\"Renamed artifact\"}";

    HttpResponse<String> response = postCommand("rename-resource", body);

    Assertions.assertEquals(404, response.statusCode(), response.body());
  }

  @Test
  @Order(8)
  public void renameArtifactRequiresAndForwardsTheCallersIfMatch() throws Exception {
    String body = "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"schema:name\":\"Renamed artifact\"}";

    HttpResponse<String> missing = postCommand("rename-resource", body);
    Assertions.assertEquals(428, missing.statusCode(), missing.body());

    receivedPutIfMatch = null;
    HttpResponse<String> stale = postCommand("rename-resource", body, "\"0\"");
    Assertions.assertEquals(412, stale.statusCode(), stale.body());
    Assertions.assertEquals("\"0\"", receivedPutIfMatch,
        "rename must forward the caller's validator, not replace it with the internal GET ETag");
  }

  @Test
  @Order(9)
  public void authenticatedCreatesReachArtifactGraphAndIndexServices() throws Exception {
    JsonArtifactRenderer renderer = new JsonArtifactRenderer();
    URI templateId = URI.create("https://repo.metadatacenter.org/templates/write-path-fixture");
    Map<String, ObjectNode> artifacts = Map.of(
        "/templates", renderer.renderTemplateSchemaArtifact(
            TemplateSchemaArtifact.builder().withName("Created template").build()),
        "/template-elements", renderer.renderElementSchemaArtifact(
            ElementSchemaArtifact.builder().withName("Created element").build()),
        "/template-fields", renderer.renderFieldSchemaArtifact(
            TextField.builder().withName("Created field").build()),
        "/template-instances", renderer.renderTemplateInstanceArtifact(
            TemplateInstanceArtifact.builder().withName("Created instance").withIsBasedOn(templateId).build()));

    for (Map.Entry<String, ObjectNode> artifact : artifacts.entrySet()) {
      HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + artifact.getKey()))
              .header("Authorization", authHeader)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(artifact.getValue().toString()))
              .build(),
          HttpResponse.BodyHandlers.ofString());

      Assertions.assertEquals(201, response.statusCode(), artifact.getKey() + ": " + response.body());
      JsonNode created = JsonMapper.STRICT_MAPPER.readTree(response.body());
      String createdId = created.path("@id").asText();
      Assertions.assertFalse(createdId.isBlank(), response.body());
      Assertions.assertNotNull(folderSession.findArtifactById(
              CedarArtifactId.build(createdId, resourceTypeForPath(artifact.getKey()))),
          artifact.getKey() + " should be represented in the resource graph");
      Assertions.assertTrue(nodeIndexingService.wasIndexed(createdId),
          artifact.getKey() + " should be offered to the indexing service");
      Assertions.assertTrue(response.headers().firstValue("Location").orElse("").endsWith(
          artifact.getKey() + "/" + createdId.substring(createdId.lastIndexOf('/') + 1)),
          artifact.getKey() + " should return Resource's created-artifact location");
    }
  }

  /** Fields, elements, instances and folders each move under the permissions of their own kind. */
  @Test
  @Order(10)
  public void everyKindOfResourceMovesToAnotherFolder() throws Exception {
    List<org.metadatacenter.model.folderserver.basic.FileSystemResource> resources = List.of(
        artifact(new org.metadatacenter.model.folderserver.basic.FolderServerField(), CedarResourceType.FIELD),
        artifact(new org.metadatacenter.model.folderserver.basic.FolderServerElement(), CedarResourceType.ELEMENT),
        artifact(new org.metadatacenter.model.folderserver.basic.FolderServerInstance(), CedarResourceType.INSTANCE),
        childFolder("Moved folder " + java.util.UUID.randomUUID()));

    for (var resource : resources) {
      HttpResponse<String> moved = postCommand("move-resource-to-folder", "{\"@id\":\"" + resource.getId() + "\","
          + "\"targetFolderId\":\"" + moveDestinationId.getId() + "\"}", "*");
      Assertions.assertEquals(201, moved.statusCode(), resource.getType() + ": " + moved.body());
      Assertions.assertTrue(parentPathOf(resource).endsWith("/Move command destination"),
          resource.getType() + " is now in the destination: " + parentPathOf(resource));
    }
  }

  @Test
  @Order(11)
  public void aMoveOrCopyNamesTheFolderItCannotFindAndAFolderIsNotCopied() throws Exception {
    String missingFolder = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FOLDER);
    JsonNode noTarget = refusal(postCommand("move-resource-to-folder", "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + missingFolder + "\"}", "*"), 400);
    Assertions.assertEquals("targetFolderNotFound", noTarget.path("errorKey").asText(), noTarget.toString());

    var folder = childFolder("Uncopyable folder " + java.util.UUID.randomUUID());
    JsonNode folderCopy = refusal(postCommand("copy-artifact-to-folder", "{\"@id\":\"" + folder.getId() + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\",\"nameTemplate\":\"Copy of {{name}}\"}"), 400);
    Assertions.assertEquals("folderCopyNotAllowed", folderCopy.path("errorKey").asText(), folderCopy.toString());
  }

  private static org.metadatacenter.model.folderserver.basic.FileSystemResource artifact(
      org.metadatacenter.model.folderserver.basic.FolderServerArtifact artifact, CedarResourceType type) {
    artifact.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(type));
    artifact.setName("Moved " + type.getValue() + " " + java.util.UUID.randomUUID());
    artifact.setDescription("Move command fixture");
    org.metadatacenter.model.folderserver.basic.FileSystemResource created =
        folderSession.createResourceAsChildOfId(artifact, homeFolderId);
    Assertions.assertNotNull(created, type.getValue());
    return created;
  }

  private static org.metadatacenter.model.folderserver.basic.FolderServerFolder childFolder(String name) {
    var folder = new org.metadatacenter.model.folderserver.basic.FolderServerFolder();
    folder.setName(name);
    folder.setDescription("Command fixture");
    CedarFolderId id = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    var created = folderSession.createFolderAsChildOfId(folder, homeFolderId, id);
    Assertions.assertNotNull(created);
    return created;
  }

  private static String parentPathOf(org.metadatacenter.model.folderserver.basic.FileSystemResource resource) {
    if (resource.getType() == CedarResourceType.FOLDER) {
      var folder = folderSession.findFolderById(CedarFolderId.build(resource.getId()));
      folderSession.addPathAndParentId(folder);
      return folder.getParentPath();
    }
    var artifact = folderSession.findArtifactById(CedarArtifactId.build(resource.getId(), resource.getType()));
    folderSession.addPathAndParentId(artifact);
    return artifact.getParentPath();
  }

  private static JsonNode refusal(HttpResponse<String> response, int status) throws Exception {
    Assertions.assertEquals(status, response.statusCode(), response.body());
    return JsonMapper.STRICT_MAPPER.readTree(response.body());
  }

  @Test
  @Order(20)
  public void unavailableArtifactServerRemainsServiceUnavailableAcrossCopyAndDelete() throws Exception {
    artifactServer.stop(0);
    artifactServer = null;

    String copyBody = "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\","
        + "\"nameTemplate\":\"Copy of {{name}}\"}";
    HttpResponse<String> copyResponse = postCommand("copy-artifact-to-folder", copyBody);
    assertServiceUnavailable(copyResponse);

    HttpRequest deleteRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/"
            + java.net.URLEncoder.encode(sourceArtifact.getId(), StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .header("If-Match", "*")
        .DELETE()
        .build();
    HttpResponse<String> deleteResponse = CLIENT.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
    assertServiceUnavailable(deleteResponse);
  }

  /**
   * A command accepts the properties it declares. The Workspace sent the resource type alongside the
   * move, where nothing read it, and a caller who misspelled targetFolderId was answered as though
   * the parent folder were missing rather than told which property the endpoint does not accept.
   *
   * <p>The refusal happens before any call to the artifact server, so this holds whether or not the
   * fixture's artifact server is still up.
   */
  @Test
  @Order(21)
  public void aCommandRefusesPropertiesItDoesNotAccept() throws Exception {
    String withTheResourceType = "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"resourceType\":\"template\","
        + "\"targetFolderId\":\"" + moveDestinationId.getId() + "\"}";
    HttpResponse<String> moved = postCommand("move-resource-to-folder", withTheResourceType, "*");
    Assertions.assertEquals(400, moved.statusCode(), moved.body());
    Assertions.assertTrue(moved.body().contains("resourceType"), moved.body());

    String withAMisspelling = "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderid\":\"" + moveDestinationId.getId() + "\"}";
    HttpResponse<String> misspelled = postCommand("move-resource-to-folder", withAMisspelling, "*");
    Assertions.assertEquals(400, misspelled.statusCode(), misspelled.body());
    Assertions.assertTrue(misspelled.body().contains("targetFolderid"), misspelled.body());
  }

  /**
   * A copy repeated at the identity its caller chose, as after a lost answer, finds the identity taken
   * and answers 412 instead of making a second copy.
   */
  @Test
  @Order(10)
  public void aCopyRepeatedAtItsCopyIdFindsItTakenAndLeavesOneCopy() throws Exception {
    String copyId = chooseIdentity(CedarResourceType.TEMPLATE);
    int creates = CREATES_AT_CHOSEN.get();

    HttpResponse<String> first = postCommand("copy-artifact-to-folder", copyBody(copyId));
    HttpResponse<String> repeated = postCommand("copy-artifact-to-folder", copyBody(copyId));

    Assertions.assertEquals(201, first.statusCode(), first.body());
    Assertions.assertEquals(copyId, JsonMapper.STRICT_MAPPER.readTree(first.body()).path("@id").asText());
    Assertions.assertEquals(412, repeated.statusCode(), repeated.body());
    Assertions.assertEquals("nodeAlreadyPresent",
        JsonMapper.STRICT_MAPPER.readTree(repeated.body()).path("errorKey").asText(), repeated.body());
    Assertions.assertEquals(creates + 1, CREATES_AT_CHOSEN.get(), "the repeat wrote a second copy");
    Assertions.assertEquals(sourceArtifact.getId(), STORED_AT_CHOSEN.get(copyId).path("pav:derivedFrom").asText());
    Assertions.assertNotNull(folderSession.findArtifactById(CedarArtifactId.build(copyId, CedarResourceType.TEMPLATE)));
  }

  /**
   * The artifact server stores the copy and its answer is lost. The copy's identity is known, so the
   * command finds the document and removes it, since the graph never registered it, and answers 503.
   * The caller's retry with the same identity then creates the one copy.
   */
  @Test
  @Order(11)
  public void aCopyWhoseCreateAnswerIsLostIsRemovedAndItsRetryMakesOneCopy() throws Exception {
    String copyId = chooseIdentity(CedarResourceType.TEMPLATE);
    losingTheNextCreateAnswer = true;
    int creates = CREATES_AT_CHOSEN.get();
    int deletes = DELETES_AT_CHOSEN.get();

    HttpResponse<String> lost = postCommand("copy-artifact-to-folder", copyBody(copyId));

    Assertions.assertEquals(503, lost.statusCode(), lost.body());
    Assertions.assertEquals(creates + 1, CREATES_AT_CHOSEN.get(), "the copy was never stored");
    Assertions.assertEquals(deletes + 1, DELETES_AT_CHOSEN.get(), "the unregistered copy was not removed");
    Assertions.assertFalse(STORED_AT_CHOSEN.containsKey(copyId));
    Assertions.assertNull(folderSession.findArtifactById(CedarArtifactId.build(copyId, CedarResourceType.TEMPLATE)));

    HttpResponse<String> retried = postCommand("copy-artifact-to-folder", copyBody(copyId));

    Assertions.assertEquals(201, retried.statusCode(), retried.body());
    Assertions.assertEquals(creates + 2, CREATES_AT_CHOSEN.get());
    Assertions.assertTrue(STORED_AT_CHOSEN.containsKey(copyId));
    Assertions.assertNotNull(folderSession.findArtifactById(CedarArtifactId.build(copyId, CedarResourceType.TEMPLATE)));
  }

  /**
   * The artifact server's answer to the copy is lost, reading the identity back finds nothing, and the
   * copy is stored only afterwards. Nothing registers it, and while it is there a retry at the identity
   * is refused as occupied. The create's cleanup job has held the identity since before the write, so
   * the relay's next attempt finds the copy and removes it at the revision it read, and the retry then
   * makes the one copy.
   */
  @Test
  @Order(14)
  public void aCopyStoredAfterItsLostAnswerWasReadBackIsRemovedByTheRelay() throws Exception {
    String copyId = chooseIdentity(CedarResourceType.TEMPLATE);
    holdingTheNextCreate = true;
    int deletes = DELETES_AT_CHOSEN.get();

    HttpResponse<String> lost = postCommand("copy-artifact-to-folder", copyBody(copyId));

    Assertions.assertEquals(503, lost.statusCode(), lost.body());
    Assertions.assertTrue(HELD_CREATES.containsKey(copyId), "the copy never reached the artifact server");
    Assertions.assertFalse(STORED_AT_CHOSEN.containsKey(copyId));
    String job = cleanupJob(copyId);
    Assertions.assertNotNull(job, "the create's cleanup job does not hold the identity it wrote at");

    STORED_AT_CHOSEN.put(copyId, HELD_CREATES.remove(copyId));
    HttpResponse<String> blocked = postCommand("copy-artifact-to-folder", copyBody(copyId));
    Assertions.assertEquals(412, blocked.statusCode(), blocked.body());

    // The relay runs this step for each pending job. The case runs it now instead of waiting for the delay.
    AbstractResourceServerResource.artifactCreateCleanupService.cleanupNow(job,
        CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig)));

    Assertions.assertEquals(deletes + 1, DELETES_AT_CHOSEN.get(), "the unregistered copy was not removed");
    Assertions.assertFalse(STORED_AT_CHOSEN.containsKey(copyId));
    Assertions.assertNull(cleanupJob(copyId), "the cleanup did not finish");
    Assertions.assertNull(folderSession.findArtifactById(CedarArtifactId.build(copyId, CedarResourceType.TEMPLATE)));

    HttpResponse<String> retried = postCommand("copy-artifact-to-folder", copyBody(copyId));

    Assertions.assertEquals(201, retried.statusCode(), retried.body());
    Assertions.assertEquals(copyId, JsonMapper.STRICT_MAPPER.readTree(retried.body()).path("@id").asText());
    Assertions.assertTrue(STORED_AT_CHOSEN.containsKey(copyId));
    Assertions.assertNotNull(folderSession.findArtifactById(CedarArtifactId.build(copyId, CedarResourceType.TEMPLATE)));
    Assertions.assertNull(cleanupJob(copyId), "registration did not retire the retry's cleanup job");
  }

  /** A copyId either store already holds is refused before anything is written. */
  @Test
  @Order(12)
  public void anOccupiedCopyIdIsRefusedWithoutAWrite() throws Exception {
    String heldByTheArtifactStore = chooseIdentity(CedarResourceType.TEMPLATE);
    STORED_AT_CHOSEN.put(heldByTheArtifactStore, JsonMapper.STRICT_MAPPER.createObjectNode()
        .put("@id", heldByTheArtifactStore));
    int creates = CREATES_AT_CHOSEN.get();
    for (String occupied : List.of(sourceArtifact.getId(), heldByTheArtifactStore)) {
      postedArtifact = null;

      HttpResponse<String> response = postCommand("copy-artifact-to-folder", copyBody(occupied));

      Assertions.assertEquals(412, response.statusCode(), response.body());
      Assertions.assertEquals("nodeAlreadyPresent",
          JsonMapper.STRICT_MAPPER.readTree(response.body()).path("errorKey").asText(), response.body());
      Assertions.assertNull(postedArtifact, "an occupied identity reached the artifact server's create");
      Assertions.assertEquals(creates, CREATES_AT_CHOSEN.get());
    }
  }

  /** A copyId that is not an identifier of the source's collection is refused before anything is written. */
  @Test
  @Order(13)
  public void aCopyIdOutsideTheSourcesCollectionIsRefused() throws Exception {
    int creates = CREATES_AT_CHOSEN.get();
    for (String copyId : List.of(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FIELD),
        "copy-of-the-source")) {
      postedArtifact = null;

      HttpResponse<String> response = postCommand("copy-artifact-to-folder", copyBody(copyId));

      Assertions.assertEquals(400, response.statusCode(), response.body());
      Assertions.assertNull(postedArtifact);
      Assertions.assertEquals(creates, CREATES_AT_CHOSEN.get());
    }
  }

  /** The cleanup job that holds an identity, or null. */
  private static String cleanupJob(String id) {
    var neo = org.metadatacenter.server.neo4j.Neo4jConfig.fromCedarConfig(cedarConfig);
    try (var driver = org.neo4j.driver.GraphDatabase.driver(neo.getUri(),
        org.neo4j.driver.AuthTokens.basic(neo.getUserName(), neo.getUserPassword()));
         var session = driver.session()) {
      var result = session.run("MATCH (j:CedarArtifactCreateCleanup {resourceId:$id}) RETURN j.jobId AS job",
          Map.of("id", id));
      return result.hasNext() ? result.single().get("job").asString() : null;
    }
  }

  private static String chooseIdentity(CedarResourceType type) {
    String id = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(type);
    CHOSEN.add(id);
    return id;
  }

  private static String copyBody(String copyId) {
    return "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\","
        + "\"nameTemplate\":\"Copy of {{name}}\","
        + "\"copyId\":\"" + copyId + "\"}";
  }

  /** The stub's answer for an identity a case chose, or false when the request names no such identity. */
  private static boolean answeredAtAChosenIdentity(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (!path.startsWith("/templates/")) {
      return false;
    }
    String id = cedarConfig.getLinkedDataUtil().resolveResourceId(CedarResourceType.TEMPLATE,
        java.net.URLDecoder.decode(path.substring("/templates/".length()), StandardCharsets.UTF_8));
    if (!CHOSEN.contains(id)) {
      return false;
    }
    String method = exchange.getRequestMethod();
    byte[] request = exchange.getRequestBody().readAllBytes();
    ObjectNode stored = STORED_AT_CHOSEN.get(id);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (method.equals("GET") && stored != null) {
      exchange.getResponseHeaders().set("ETag", "\"5\"");
      byte[] body = stored.toString().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    } else if (method.equals("PUT") && exchange.getRequestHeaders().getFirst("If-Match") == null) {
      if (stored != null) {
        exchange.sendResponseHeaders(412, -1);
      } else {
        ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(request);
        if (holdingTheNextCreate) {
          holdingTheNextCreate = false;
          HELD_CREATES.put(id, created);
          exchange.close();
          return true;
        }
        postedArtifact = created;
        STORED_AT_CHOSEN.put(id, created);
        CREATES_AT_CHOSEN.incrementAndGet();
        if (losingTheNextCreateAnswer) {
          losingTheNextCreateAnswer = false;
          exchange.close();
          return true;
        }
        exchange.getResponseHeaders().set("ETag", "\"5\"");
        exchange.getResponseHeaders().set("Location", id);
        byte[] body = created.toString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(201, body.length);
        exchange.getResponseBody().write(body);
      }
    } else if (method.equals("DELETE") && stored != null
        && "\"5\"".equals(exchange.getRequestHeaders().getFirst("If-Match"))) {
      STORED_AT_CHOSEN.remove(id);
      DELETES_AT_CHOSEN.incrementAndGet();
      exchange.sendResponseHeaders(204, -1);
    } else {
      exchange.sendResponseHeaders(stored == null ? 404 : 412, -1);
    }
    exchange.close();
    return true;
  }

  private static void assertServiceUnavailable(HttpResponse<String> response) throws IOException {
    Assertions.assertEquals(503, response.statusCode(), response.body());
    JsonNode error = JsonMapper.STRICT_MAPPER.readTree(response.body());
    Assertions.assertEquals("SERVICE_UNAVAILABLE", error.path("status").asText(), response.body());
    Assertions.assertEquals("Downstream service is unavailable", error.path("message").asText(), response.body());
  }

  private static HttpResponse<String> postCommand(String command, String body) throws Exception {
    return postCommand(command, body, null);
  }

  private static HttpResponse<String> postCommand(String command, String body, String ifMatch) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/" + command))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json");
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    return CLIENT.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String copyBody() {
    return "{\"@id\":\"" + sourceArtifact.getId() + "\","
        + "\"targetFolderId\":\"" + homeFolderId.getId() + "\","
        + "\"nameTemplate\":\"Copy of {{name}}\"}";
  }

  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    if (answeredAtAChosenIdentity(exchange)) {
      return;
    }
    byte[] response;
    int status;
    if ("GET".equals(exchange.getRequestMethod())) {
      status = sourceGetStatus;
      if (omitSourceGetBody) {
        response = null;
      } else if (status == 200) {
        response = sourceDocument().toString().getBytes(StandardCharsets.UTF_8);
      } else {
        response = ("{\"status\":" + status + "}").getBytes(StandardCharsets.UTF_8);
      }
    } else if ("POST".equals(exchange.getRequestMethod())) {
      postedArtifact = JsonMapper.STRICT_MAPPER.readTree(exchange.getRequestBody());
      ObjectNode created = ((ObjectNode) postedArtifact).deepCopy();
      CedarResourceType resourceType = resourceTypeForPath(exchange.getRequestURI().getPath());
      String createdArtifactId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(resourceType);
      created.put("@id", createdArtifactId);
      status = 201;
      response = created.toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Location", createdArtifactId);
    } else if ("PUT".equals(exchange.getRequestMethod())) {
      receivedPutIfMatch = exchange.getRequestHeaders().getFirst("If-Match");
      response = exchange.getRequestBody().readAllBytes();
      if (!"\"1\"".equals(receivedPutIfMatch) && !"*".equals(receivedPutIfMatch)) {
        status = 412;
        response = "{\"status\":412}".getBytes(StandardCharsets.UTF_8);
      } else {
        status = 200;
        exchange.getResponseHeaders().set("ETag", "\"2\"");
      }
    } else {
      exchange.getRequestBody().readAllBytes();
      status = 405;
      response = new byte[0];
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if ("GET".equals(exchange.getRequestMethod()) && status == 200) {
      exchange.getResponseHeaders().set("ETag", "\"1\"");
      exchange.getResponseHeaders().set("Vary", "Accept");
    }
    if (response == null) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
    }
    exchange.close();
  }

  private static CedarResourceType resourceTypeForPath(String path) {
    return switch (path) {
      case "/templates" -> CedarResourceType.TEMPLATE;
      case "/template-elements" -> CedarResourceType.ELEMENT;
      case "/template-fields" -> CedarResourceType.FIELD;
      case "/template-instances" -> CedarResourceType.INSTANCE;
      default -> throw new IllegalArgumentException("Unexpected artifact create path " + path);
    };
  }

  private static ObjectNode sourceDocument() {
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(
        TemplateSchemaArtifact.builder()
            .withName(SOURCE_NAME)
            .withJsonLdId(URI.create(sourceArtifact.getId()))
            .withAnnotations(Annotations.builder()
                .withIriAnnotation(ModelNodeNames.DATACITE_DOI_URI, URI.create(SOURCE_DOI))
                .build())
            .build());
  }
}
