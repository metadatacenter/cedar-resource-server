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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.Annotations;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TextField;
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
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.http.RevisionPreconditionParser;
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
 * <p>The command succeeds or fails as a whole. What a later step would refuse is refused before the
 * template is published, and a step that fails after the publication is undone: the draft it created is
 * removed and the template returns to the draft it was.
 *
 * <p>The artifact server is a stub keyed by identifier, so the sequence of reads and writes runs
 * against documents that persist between them; the graph is embedded.
 */
@Tag("integration")
public class PublishCreateDraftTemplateTest {

  private static final String SOURCE_VERSION = "1.0.0";
  private static final String DRAFT_VERSION = "1.0.1";
  private static final String PUBLISHED_DOI = "10.5072/FK2-published-template";
  private static final URI UNRELATED_PREDECESSOR =
      URI.create("https://repo.metadatacenter.orgx/templates/00000000-1111-2222-3333-444444444444");

  private static final Map<String, ObjectNode> ARTIFACTS = new ConcurrentHashMap<>();
  private static final Map<String, Integer> ETAGS = new ConcurrentHashMap<>();
  private static volatile String raceOnPut;
  /** The stub refuses, as the validator does, a definition that leaves out this property's declaration. */
  private static volatile boolean refusingDefinitionsWithoutModifiedBy;
  /** The stub answers 500 to the write that gives a successor its definition. */
  private static volatile boolean failingDefinitionWrites;
  /** Before it fails that write, the stub stores another writer's edit of the successor. */
  private static volatile boolean editingDraftsBeforeTheirDefinitionFails;
  /**
   * Before it fails that write, the stub leaves the successor as the artifact server leaves a draft it
   * put back after an update: the same content, with fresh provenance stamps on it and on its children.
   */
  private static volatile boolean puttingDraftsBackBeforeTheirDefinitionFails;
  /** Before it fails that write, the stub loses the successor's document, so that only the graph names it. */
  private static volatile boolean losingDraftsBeforeTheirDefinitionFails;
  /** The stub answers the draft's creation with this status and an error page rather than JSON. */
  private static volatile Integer refusingDraftCreationsWithAPage;
  /** The stub stores another document at this identifier when it publishes a template. */
  private static volatile String takingThisIdentityOnPublication;
  /**
   * The stub refuses to put a published template's draft document back, with this status, or with no
   * answer at all when it is "no answer".
   */
  private static volatile String refusingPutBacksOfPublishedTemplates;
  /** A write or deletion the stub received: of a template or a draft, its If-Match, and the revision it made. */
  private record Write(String method, boolean source, String condition, Integer revision) {
  }

  private static final java.util.List<Write> WRITES = new java.util.concurrent.CopyOnWriteArrayList<>();
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
        new SearchPermissionEnqueueService(cedarConfig));

    CedarRequestContext userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    homeFolderId = folderSession.findHomeFolderOf().getResourceId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    artifactServer.stop(0);
  }

  @org.junit.jupiter.api.AfterEach
  public void disarm() {
    refusingDefinitionsWithoutModifiedBy = false;
    failingDefinitionWrites = false;
    editingDraftsBeforeTheirDefinitionFails = false;
    puttingDraftsBackBeforeTheirDefinitionFails = false;
    losingDraftsBeforeTheirDefinitionFails = false;
    refusingDraftCreationsWithAPage = null;
    takingThisIdentityOnPublication = null;
    refusingPutBacksOfPublishedTemplates = null;
    WRITES.clear();
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
    return publishCreateDraft(sourceId, body, "\"" + ETAGS.get(sourceId.getId()) + "\"");
  }

  private static HttpResponse<String> publishCreateDraft(CedarTemplateId sourceId, String body, String etag) throws Exception {
    return publishCreateDraft(sourceId, body, etag, null);
  }

  private static HttpResponse<String> publishCreateDraft(CedarTemplateId sourceId, String body, String etag,
                                                         String draftId) throws Exception {
    return publishCreateDraft(sourceId, body, etag, draftId, authHeader);
  }

  private static HttpResponse<String> publishCreateDraft(CedarTemplateId sourceId, String body, String etag,
                                                         String draftId, String authorization) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort()
            + "/command/publish-create-draft-template/"
            + URLEncoder.encode(sourceId.getId(), StandardCharsets.UTF_8)
            + (draftId == null ? "" : "?draft_id=" + URLEncoder.encode(draftId, StandardCharsets.UTF_8))))
        .header("Authorization", authorization)
        .header("Content-Type", "application/json");
    if (etag != null) request.header("If-Match", etag);
    return CLIENT.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void missingAndStaleValidatorsLeaveBothStoresUntouched() throws Exception {
    CedarTemplateId id = createSourceTemplate("Conditional version source", null);
    ObjectNode before = ARTIFACTS.get(id.getId()).deepCopy();
    int count = ARTIFACTS.size();
    Assertions.assertEquals(428, publishCreateDraft(id, before.toString(), null).statusCode());
    Assertions.assertEquals(412, publishCreateDraft(id, before.toString(), "\"0\"").statusCode());
    Assertions.assertEquals(before, ARTIFACTS.get(id.getId()));
    Assertions.assertEquals(count, ARTIFACTS.size(), "no draft may be minted");
    Assertions.assertEquals(BiboStatus.DRAFT,
        ((FolderServerTemplate) folderSession.findArtifactById(id)).getPublicationStatus());
  }

  @Test
  public void concurrentWriteAfterReadCannotPublishOrCreateADraft() throws Exception {
    CedarTemplateId id = createSourceTemplate("Concurrent version source", null);
    int count = ARTIFACTS.size();
    raceOnPut = id.getId();
    HttpResponse<String> response = publishCreateDraft(id, ARTIFACTS.get(id.getId()).toString());
    Assertions.assertEquals(412, response.statusCode(), response.body());
    Assertions.assertEquals("Concurrent editor", ARTIFACTS.get(id.getId()).path("schema:description").asText());
    Assertions.assertEquals(BiboStatus.DRAFT.getValue(), ARTIFACTS.get(id.getId()).path("bibo:status").asText());
    Assertions.assertEquals(count, ARTIFACTS.size(), "a failed source CAS must not create a draft");
    Assertions.assertEquals(BiboStatus.DRAFT,
        ((FolderServerTemplate) folderSession.findArtifactById(id)).getPublicationStatus());
  }

  /**
   * An owner may version a template that sits in another user's folder, where the owner is only a
   * viewer, and may not create the draft there. That refusal used to come after the publication, which
   * stayed. It comes first now, and the template is left as the author opened it.
   */
  @Test
  public void aParentFolderTheOwnerCannotCreateInRefusesBeforePublishing() throws Exception {
    CedarRequestContext user2 = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser2(cedarConfig));
    FolderServiceSession user2Folders = CedarDataServices.getInstance().getFolderServiceSession(user2);
    org.metadatacenter.model.folderserver.basic.FolderServerFolder folder =
        new org.metadatacenter.model.folderserver.basic.FolderServerFolder();
    folder.setName("Folder user 1 may only view " + REVISIONS.incrementAndGet());
    folder.setDescription("Owned by user 2");
    CedarFolderId folderId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    Assertions.assertNotNull(user2Folders.createFolderAsChildOfId(folder, user2Folders.findHomeFolderOf().getResourceId(),
        folderId));
    var grant = new org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest();
    grant.setOwner(new org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser(
        TestAuthUtil.getTestUser2(cedarConfig).getId()));
    grant.getUserPermissions().add(
        new org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUserPermissionPair(
            new org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser(
                TestAuthUtil.getTestUser1(cedarConfig).getId()),
            org.metadatacenter.server.security.model.permission.resource.ResourceRole.VIEWER));
    Assertions.assertFalse(CedarDataServices.getInstance().getResourcePermissionServiceSession(user2)
        .updateResourcePermissions(folderId, grant).isError());
    CedarTemplateId sourceId = createSourceTemplate("Template in a folder its owner only views", null, folderId);
    ObjectNode before = ARTIFACTS.get(sourceId.getId()).deepCopy();
    Integer etagBefore = ETAGS.get(sourceId.getId());
    int documents = ARTIFACTS.size();
    String submitted = templateDocument("Template in a folder its owner only views, edited", null).toString();

    for (int attempt = 0; attempt < 2; attempt++) {
      HttpResponse<String> response = publishCreateDraft(sourceId, submitted);
      Assertions.assertEquals(403, response.statusCode(), response.body());
    }

    assertUntouched(sourceId, before, etagBefore, documents);
  }

  /**
   * Only a template's owner may version it. A Manager who is not the owner, and who may create in the
   * template's folder, is forbidden with the rule's own key, and nothing is published. The owner may
   * version it, and asking again, of a template that is now published, is refused with a bad request.
   * That reason concerns the template, not the caller.
   */
  @Test
  public void aManagerWhoIsNotTheOwnerIsForbiddenToVersionTheTemplate() throws Exception {
    org.metadatacenter.model.folderserver.basic.FolderServerFolder folder =
        new org.metadatacenter.model.folderserver.basic.FolderServerFolder();
    folder.setName("Folder user 2 manages " + REVISIONS.incrementAndGet());
    folder.setDescription("Owned by user 1");
    CedarFolderId folderId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    Assertions.assertNotNull(folderSession.createFolderAsChildOfId(folder, homeFolderId, folderId));
    var grant = new org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest();
    grant.setOwner(new org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser(
        TestAuthUtil.getTestUser1(cedarConfig).getId()));
    grant.getUserPermissions().add(
        new org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUserPermissionPair(
            new org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser(
                TestAuthUtil.getTestUser2(cedarConfig).getId()),
            org.metadatacenter.server.security.model.permission.resource.ResourceRole.MANAGER));
    Assertions.assertFalse(CedarDataServices.getInstance().getResourcePermissionServiceSession(
        CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig)))
        .updateResourcePermissions(folderId, grant).isError());
    CedarTemplateId sourceId = createSourceTemplate("Template in a folder user 2 manages", null, folderId);
    ObjectNode before = ARTIFACTS.get(sourceId.getId()).deepCopy();
    Integer etagBefore = ETAGS.get(sourceId.getId());
    int documents = ARTIFACTS.size();
    String submitted = templateDocument("Template in a folder user 2 manages, edited", null).toString();

    HttpResponse<String> manager = publishCreateDraft(sourceId, submitted, "\"" + etagBefore + "\"", null,
        TestAuthUtil.getTestUser2AuthHeader(cedarConfig));

    Assertions.assertEquals(403, manager.statusCode(), manager.body());
    Assertions.assertEquals("versioningOnlyByOwner",
        JsonMapper.STRICT_MAPPER.readTree(manager.body()).path("errorKey").asText(), manager.body());
    assertUntouched(sourceId, before, etagBefore, documents);

    HttpResponse<String> owner = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(200, owner.statusCode(), owner.body());
    int versioned = ARTIFACTS.size();

    HttpResponse<String> again = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(400, again.statusCode(), again.body());
    Assertions.assertEquals("publishOnlyDraft",
        JsonMapper.STRICT_MAPPER.readTree(again.body()).path("errorKey").asText(), again.body());
    Assertions.assertEquals(versioned, ARTIFACTS.size(), "a refused request made another draft");
    Assertions.assertEquals(2, folderSession.getVersionHistory(sourceId).size());
  }

  /**
   * A definition the draft would refuse is refused while nothing has changed, with the validator's
   * report, so the author can correct it and send it again with the ETag the template was opened with.
   */
  @Test
  public void aDefinitionTheValidatorRefusesIsRefusedBeforePublishing() throws Exception {
    refusingDefinitionsWithoutModifiedBy = true;
    CedarTemplateId sourceId = createSourceTemplate("Template with a refused definition", null);
    ObjectNode before = ARTIFACTS.get(sourceId.getId()).deepCopy();
    Integer etagBefore = ETAGS.get(sourceId.getId());
    int documents = ARTIFACTS.size();
    ObjectNode valid = templateDocument("Template with a refused definition, corrected", null);
    Assertions.assertTrue(valid.path("properties").has(ModelNodeNames.OSLC_MODIFIED_BY),
        "the case needs a definition the stub accepts: " + valid);
    ObjectNode refused = valid.deepCopy();
    ((ObjectNode) refused.path("properties")).remove(ModelNodeNames.OSLC_MODIFIED_BY);

    HttpResponse<String> refusal = publishCreateDraft(sourceId, refused.toString());

    Assertions.assertEquals(400, refusal.statusCode(), refusal.body());
    Assertions.assertTrue(JsonMapper.STRICT_MAPPER.readTree(refusal.body()).path("objects").has("validationReport"),
        "the validator's report did not reach the caller: " + refusal.body());
    assertUntouched(sourceId, before, etagBefore, documents);

    HttpResponse<String> corrected = publishCreateDraft(sourceId, valid.toString(), "\"" + etagBefore + "\"");

    Assertions.assertEquals(200, corrected.statusCode(), corrected.body());
    JsonNode draft = storedDraftOf(sourceId, corrected);
    Assertions.assertEquals("Template with a refused definition, corrected", draft.path("schema:name").asText());
    Assertions.assertEquals(sourceId.getId(), draft.path(ModelNodeNames.PAV_PREVIOUS_VERSION).asText());
    Assertions.assertEquals(documents + 1, ARTIFACTS.size(), "the corrected request made more than one draft");
    Assertions.assertEquals(2, folderSession.getVersionHistory(sourceId).size());

    HttpResponse<String> repeated = publishCreateDraft(sourceId, valid.toString(), "\"" + etagBefore + "\"");

    Assertions.assertEquals(412, repeated.statusCode(), repeated.body());
    Assertions.assertEquals(documents + 1, ARTIFACTS.size(), "a repeat made another draft");
    Assertions.assertEquals(2, folderSession.getVersionHistory(sourceId).size());
  }

  /**
   * When the definition's write fails after the publication and the draft have committed, both are
   * undone. The draft is deleted on the ETag it was created with, and the template's document is put
   * back on the ETag its publication returned, so neither undoing can overwrite a write that came after.
   */
  @Test
  public void aFailureAfterPublicationUndoesItAndLeavesNoSuccessor() throws Exception {
    failingDefinitionWrites = true;
    CedarTemplateId sourceId = createSourceTemplate("Template whose draft cannot be written", null);
    ObjectNode before = ARTIFACTS.get(sourceId.getId()).deepCopy();
    int documents = ARTIFACTS.size();
    String submitted = templateDocument("Template whose draft cannot be written, edited", null).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    assertDescribesTheUndo(response, sourceId);
    Assertions.assertEquals(before, ARTIFACTS.get(sourceId.getId()), "the template's document was not put back");
    Assertions.assertEquals(documents, ARTIFACTS.size(), "the draft was left in the artifact store");
    FolderServerTemplate node = (FolderServerTemplate) folderSession.findArtifactById(sourceId);
    Assertions.assertEquals(BiboStatus.DRAFT, node.getPublicationStatus());
    Assertions.assertTrue(node.isLatestVersion() && node.isLatestDraftVersion() && !node.isLatestPublishedVersion(),
        "the template's latest flags still describe its publication");
    Assertions.assertEquals(1, folderSession.getVersionHistory(sourceId).size(), "the draft was left in the graph");
    Assertions.assertEquals(5, WRITES.size(), WRITES.toString());
    Write publication = WRITES.get(0);
    Write creation = WRITES.get(1);
    Assertions.assertEquals(new Write("PUT", true, publication.condition(), publication.revision()), publication);
    Assertions.assertEquals(new Write("PUT", false, null, creation.revision()), creation);
    Assertions.assertEquals(new Write("PUT", false, quoted(creation.revision()), null), WRITES.get(2),
        "the definition was not written on the draft's revision");
    Assertions.assertEquals(new Write("DELETE", false, quoted(creation.revision()), null), WRITES.get(3),
        "the draft was not deleted on the revision its creation returned");
    Assertions.assertEquals("PUT", WRITES.get(4).method());
    Assertions.assertTrue(WRITES.get(4).source());
    Assertions.assertEquals(quoted(publication.revision()), WRITES.get(4).condition(),
        "the template was not put back on the revision its publication returned");
  }

  /**
   * A draft that was put back after an update carries the provenance the put-back stamped, on the draft
   * and on its children, and nothing else of the update. It still holds what this command stored, so the
   * undoing removes it and returns the template to the draft it was.
   */
  @Test
  public void aDraftPutBackWithFreshProvenanceIsStillUndone() throws Exception {
    failingDefinitionWrites = true;
    puttingDraftsBackBeforeTheirDefinitionFails = true;
    CedarTemplateId sourceId = createSourceTemplate("Template whose draft was put back", null);
    ObjectNode withAField = new JsonArtifactRenderer().renderTemplateSchemaArtifact(TemplateSchemaArtifact.builder()
        .withName("Template whose draft was put back")
        .withVersion(Version.fromString(SOURCE_VERSION))
        .withStatus(Status.DRAFT)
        .withFieldSchema(TextField.builder().withName("Notes").build())
        .build());
    withAField.put(ModelNodeNames.JSON_LD_ID, sourceId.getId());
    ARTIFACTS.put(sourceId.getId(), withAField);
    ObjectNode before = withAField.deepCopy();
    int documents = ARTIFACTS.size();
    String submitted = templateDocument("Template whose draft was put back, edited", null).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertTrue(WRITES.stream().anyMatch(write -> write.method().equals("DELETE")),
        "the draft was taken for another writer's: " + WRITES);
    Assertions.assertEquals(before, ARTIFACTS.get(sourceId.getId()), "the template's document was not put back");
    Assertions.assertEquals(documents, ARTIFACTS.size(), "the draft was left in the artifact store");
    Assertions.assertEquals(BiboStatus.DRAFT,
        ((FolderServerTemplate) folderSession.findArtifactById(sourceId)).getPublicationStatus());
    Assertions.assertEquals(1, folderSession.getVersionHistory(sourceId).size(), "the draft was left in the graph");
  }

  /**
   * A failure answered after the publication was undone describes the template as the undoing left it:
   * the ETag it has now, no Location for the draft that was removed, and a message saying so.
   */
  private static void assertDescribesTheUndo(HttpResponse<String> response, CedarTemplateId sourceId) throws IOException {
    Assertions.assertEquals(java.util.Optional.of(quoted(ETAGS.get(sourceId.getId()))), response.headers().firstValue("ETag"),
        "the answer does not carry the template's ETag after the undoing: " + response.headers().map());
    Assertions.assertTrue(response.headers().firstValue("Location").isEmpty(),
        "the answer names the draft the undoing removed: " + response.headers().map());
    String message = JsonMapper.STRICT_MAPPER.readTree(response.body()).path("message").asText();
    Assertions.assertTrue(message.contains("publication was undone"), "the answer does not say the publication was undone: "
        + message);
  }

  private static String quoted(Integer revision) {
    return "\"" + revision + "\"";
  }

  /**
   * A draft that someone else changed after the command created it is not the command's to remove, so
   * the undoing stops there. The draft stays with its edit, and the template stays published, since
   * returning it to a draft beside its successor would give the series two drafts.
   */
  @Test
  public void aDraftAnotherWriterChangedIsLeftWithTheirEdit() throws Exception {
    failingDefinitionWrites = true;
    editingDraftsBeforeTheirDefinitionFails = true;
    CedarTemplateId sourceId = createSourceTemplate("Template whose draft another writer edits", null);
    int documents = ARTIFACTS.size();
    String submitted = templateDocument("Template whose draft another writer edits, edited", null).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertEquals(documents + 1, ARTIFACTS.size(), "the edited draft was removed");
    Assertions.assertTrue(ARTIFACTS.values().stream()
            .anyMatch(document -> "Edited by another writer".equals(document.path("schema:description").asText())),
        "the other writer's edit was lost");
    Assertions.assertTrue(WRITES.stream().noneMatch(write -> write.method().equals("DELETE")), WRITES.toString());
    Assertions.assertEquals(2, folderSession.getVersionHistory(sourceId).size());
    Assertions.assertEquals(BiboStatus.PUBLISHED,
        ((FolderServerTemplate) folderSession.findArtifactById(sourceId)).getPublicationStatus());
    Assertions.assertEquals(BiboStatus.PUBLISHED.getValue(),
        ARTIFACTS.get(sourceId.getId()).path(ModelNodeNames.BIBO_STATUS).asText(),
        "the template's document and graph disagree about its publication");
  }

  /**
   * A draft whose document has gone, so that only the graph still names it, is removed from the graph by
   * the undoing, and the template returns to the draft it was.
   */
  @Test
  public void aDraftWhoseDocumentHasGoneIsStillRemovedFromTheGraph() throws Exception {
    failingDefinitionWrites = true;
    losingDraftsBeforeTheirDefinitionFails = true;
    CedarTemplateId sourceId = createSourceTemplate("Template whose draft document is lost", null);
    ObjectNode before = ARTIFACTS.get(sourceId.getId()).deepCopy();
    int documents = ARTIFACTS.size();
    String draftId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    String submitted = templateDocument("Template whose draft document is lost, edited", null).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted, "\"" + ETAGS.get(sourceId.getId()) + "\"",
        draftId);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertNull(folderSession.findArtifactById(CedarTemplateId.build(draftId)),
        "the graph still names a draft whose document has gone");
    Assertions.assertEquals(before, ARTIFACTS.get(sourceId.getId()), "the template's document was not put back");
    Assertions.assertEquals(documents, ARTIFACTS.size());
    Assertions.assertEquals(BiboStatus.DRAFT,
        ((FolderServerTemplate) folderSession.findArtifactById(sourceId)).getPublicationStatus());
    Assertions.assertEquals(1, folderSession.getVersionHistory(sourceId).size());
  }

  /**
   * The artifact server's refusal of the draft is answered with its own status even when its body is not
   * JSON, and the publication is undone.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.ValueSource(ints = {400, 503})
  public void aDraftRefusedWithAnErrorPageIsAnsweredWithItsStatusAndUndone(int status) throws Exception {
    refusingDraftCreationsWithAPage = status;
    CedarTemplateId sourceId = createSourceTemplate("Template whose draft is refused with a page", null);
    ObjectNode before = ARTIFACTS.get(sourceId.getId()).deepCopy();
    int documents = ARTIFACTS.size();
    String submitted = templateDocument("Template whose draft is refused with a page, edited", null).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(status, response.statusCode(), response.body());
    Assertions.assertEquals(before, ARTIFACTS.get(sourceId.getId()), "the template's document was not put back");
    Assertions.assertEquals(documents, ARTIFACTS.size(), "a draft reached the artifact store");
    Assertions.assertEquals(BiboStatus.DRAFT,
        ((FolderServerTemplate) folderSession.findArtifactById(sourceId)).getPublicationStatus());
    Assertions.assertEquals(1, folderSession.getVersionHistory(sourceId).size(), "a draft reached the graph");
  }

  /**
   * The draft's identifier is checked before the template is published, and another writer can still
   * take it before the draft is created. The draft is then refused with 412 as before, the publication is
   * undone, and the other writer's document is left where it is.
   */
  @Test
  public void anIdentifierTakenAfterItWasCheckedIsRefusedAndThePublicationUndone() throws Exception {
    CedarTemplateId sourceId = createSourceTemplate("Template whose draft identifier is taken", null);
    ObjectNode before = ARTIFACTS.get(sourceId.getId()).deepCopy();
    String draftId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    takingThisIdentityOnPublication = draftId;
    String submitted = templateDocument("Template whose draft identifier is taken, edited", null).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted, "\"" + ETAGS.get(sourceId.getId()) + "\"",
        draftId);

    Assertions.assertEquals(412, response.statusCode(), response.body());
    Assertions.assertEquals("nodeAlreadyPresent", JsonMapper.STRICT_MAPPER.readTree(response.body()).path("errorKey").asText());
    assertDescribesTheUndo(response, sourceId);
    Assertions.assertEquals("Another writer's template", ARTIFACTS.get(draftId).path("schema:name").asText(),
        "the other writer's document was touched");
    Assertions.assertEquals(before, ARTIFACTS.get(sourceId.getId()), "the template's document was not put back");
    Assertions.assertEquals(BiboStatus.DRAFT,
        ((FolderServerTemplate) folderSession.findArtifactById(sourceId)).getPublicationStatus());
    Assertions.assertEquals(1, folderSession.getVersionHistory(sourceId).size());
  }

  /**
   * When the template's draft document cannot be put back, the undoing stops after removing the draft.
   * The template stays published, and both stores say so, so the two never disagree about it. The
   * command still answers with the failing step's own status.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "the put-back answered {0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"503", "no answer"})
  public void aTemplateWhoseDraftDocumentCannotBePutBackStaysPublishedInBothStores(String answer) throws Exception {
    failingDefinitionWrites = true;
    refusingPutBacksOfPublishedTemplates = answer;
    CedarTemplateId sourceId = createSourceTemplate("Template that cannot be put back after " + answer, null);
    int documents = ARTIFACTS.size();
    String submitted = templateDocument("Template that cannot be put back, edited", null).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertEquals(documents, ARTIFACTS.size(), "the draft was left in the artifact store");
    Assertions.assertEquals(1, folderSession.getVersionHistory(sourceId).size(), "the draft was left in the graph");
    Assertions.assertEquals(BiboStatus.PUBLISHED.getValue(),
        ARTIFACTS.get(sourceId.getId()).path(ModelNodeNames.BIBO_STATUS).asText());
    Assertions.assertEquals(BiboStatus.PUBLISHED,
        ((FolderServerTemplate) folderSession.findArtifactById(sourceId)).getPublicationStatus(),
        "the template's document and graph disagree about its publication");
  }

  /**
   * The graph can refuse to return a template to the draft it was after its draft document has been put
   * back, and with no restore outbox it cannot be returned at all. The published document is then put back
   * in turn, conditional on the ETag the first put-back returned, so the template ends published in both
   * stores, and no restore is left pending.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"the graph refuses", "there is no restore outbox"})
  public void aTemplateTheGraphKeepsPublishedGetsItsPublishedDocumentBack(String reason) throws Exception {
    failingDefinitionWrites = true;
    CedarTemplateId sourceId = createSourceTemplate("Template the graph keeps published because " + reason, null);
    int documents = ARTIFACTS.size();
    String submitted = templateDocument("Template the graph keeps published, edited", null).toString();
    var original = AbstractResourceServerResource.artifactRestoreCompletionService;
    var keepingItPublished = org.mockito.Mockito.spy(original);
    org.mockito.Mockito.doReturn(false).when(keepingItPublished).returnToDraft(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    long restoresPending = original.getPendingCount();
    HttpResponse<String> response;
    try {
      AbstractResourceServerResource.injectArtifactRestoreCompletionService(
          reason.equals("the graph refuses") ? keepingItPublished : null);
      response = publishCreateDraft(sourceId, submitted);
    } finally {
      AbstractResourceServerResource.injectArtifactRestoreCompletionService(original);
    }

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertEquals(documents, ARTIFACTS.size(), "the draft was left in the artifact store");
    Assertions.assertEquals(1, folderSession.getVersionHistory(sourceId).size(), "the draft was left in the graph");
    JsonNode stored = ARTIFACTS.get(sourceId.getId());
    Assertions.assertEquals(BiboStatus.PUBLISHED.getValue(), stored.path(ModelNodeNames.BIBO_STATUS).asText(),
        "the published document was not put back");
    Assertions.assertEquals(SOURCE_VERSION, stored.path(ModelNodeNames.PAV_VERSION).asText());
    Assertions.assertEquals(BiboStatus.PUBLISHED,
        ((FolderServerTemplate) folderSession.findArtifactById(sourceId)).getPublicationStatus());
    java.util.List<Write> sourceWrites = WRITES.stream().filter(Write::source).toList();
    Assertions.assertEquals(3, sourceWrites.size(), sourceWrites.toString());
    Assertions.assertEquals(quoted(sourceWrites.get(0).revision()), sourceWrites.get(1).condition(),
        "the draft document was not put back on the revision the publication returned");
    Assertions.assertEquals(quoted(sourceWrites.get(1).revision()), sourceWrites.get(2).condition(),
        "the published document was not put back on the revision the first put-back returned");
    Assertions.assertEquals(restoresPending, original.getPendingCount(), "a restore was left pending");
  }

  /** A draft identifier the caller chose is where the draft is created, and a repeat finds it taken. */
  @Test
  public void theDraftIsCreatedAtTheIdentifierTheCallerChose() throws Exception {
    CedarTemplateId sourceId = createSourceTemplate("Template drafted at a chosen identifier", null);
    String draftId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    String submitted = templateDocument("Template drafted at a chosen identifier, edited", null).toString();

    HttpResponse<String> response = publishCreateDraft(sourceId, submitted, "\"" + ETAGS.get(sourceId.getId()) + "\"",
        draftId);

    Assertions.assertEquals(200, response.statusCode(), response.body());
    Assertions.assertEquals(draftId, JsonMapper.STRICT_MAPPER.readTree(response.body()).path("@id").asText());
    Assertions.assertEquals(sourceId.getId(), ARTIFACTS.get(draftId).path(ModelNodeNames.PAV_PREVIOUS_VERSION).asText());
    int documents = ARTIFACTS.size();

    HttpResponse<String> repeated = publishCreateDraft(sourceId, submitted,
        "\"" + ETAGS.get(sourceId.getId()) + "\"", draftId);

    Assertions.assertEquals(412, repeated.statusCode(), repeated.body());
    Assertions.assertEquals("nodeAlreadyPresent", JsonMapper.STRICT_MAPPER.readTree(repeated.body()).path("errorKey").asText());
    Assertions.assertEquals(documents, ARTIFACTS.size());
    Assertions.assertEquals(2, folderSession.getVersionHistory(sourceId).size());
  }

  /** An identifier outside the templates collection is refused before anything is read or written. */
  @Test
  public void aDraftIdentifierOfAnotherCollectionIsRefused() throws Exception {
    CedarTemplateId sourceId = createSourceTemplate("Template drafted at a field identifier", null);
    ObjectNode before = ARTIFACTS.get(sourceId.getId()).deepCopy();
    Integer etagBefore = ETAGS.get(sourceId.getId());
    int documents = ARTIFACTS.size();
    String fieldId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FIELD);

    HttpResponse<String> response = publishCreateDraft(sourceId,
        templateDocument("Template drafted at a field identifier, edited", null).toString(),
        "\"" + etagBefore + "\"", fieldId);

    Assertions.assertEquals(400, response.statusCode(), response.body());
    assertUntouched(sourceId, before, etagBefore, documents);
  }

  /** Nothing the command could have changed has changed, in either store. */
  private static void assertUntouched(CedarTemplateId sourceId, ObjectNode before, Integer etagBefore, int documents) {
    Assertions.assertEquals(before, ARTIFACTS.get(sourceId.getId()), "the template's document changed");
    Assertions.assertEquals(etagBefore, ETAGS.get(sourceId.getId()), "the template's ETag changed");
    Assertions.assertEquals(documents, ARTIFACTS.size(), "a draft reached the artifact store");
    FolderServerTemplate node = (FolderServerTemplate) folderSession.findArtifactById(sourceId);
    Assertions.assertEquals(BiboStatus.DRAFT, node.getPublicationStatus());
    Assertions.assertTrue(node.isLatestVersion() && node.isLatestDraftVersion(), "the template is no longer latest");
    Assertions.assertEquals(1, folderSession.getVersionHistory(sourceId).size(), "a draft reached the graph");
  }

  /** A draft template, present in both stores, that the test user owns. */
  private static CedarTemplateId createSourceTemplate(String name, String doi) {
    return createSourceTemplate(name, doi, homeFolderId);
  }

  private static CedarTemplateId createSourceTemplate(String name, String doi, CedarFolderId parent) {
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
    FolderServerArtifact created = folderSession.createResourceAsChildOfId(template, parent);
    Assertions.assertNotNull(created);
    CedarTemplateId sourceId = CedarTemplateId.build(created.getId());
    ObjectNode document = templateDocument(name, doi);
    document.put(ModelNodeNames.JSON_LD_ID, sourceId.getId());
    ARTIFACTS.put(sourceId.getId(), document);
    ETAGS.put(sourceId.getId(), REVISIONS.incrementAndGet());
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

  /**
   * The stub keeps every artifact it is given, keyed by identifier, and answers by identifier. Like the
   * artifact server, it creates at an unused identifier a PUT names without If-Match, refuses a write or
   * deletion whose If-Match is not the current revision, and judges a definition before it is written.
   */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    boolean judgement = path.endsWith("/replacement-judgement");
    if (judgement) path = path.substring(0, path.length() - "/replacement-judgement".length());
    String artifactId = path.contains("/templates/")
        ? URLDecoder.decode(path.substring(path.indexOf("/templates/") + "/templates/".length()),
        StandardCharsets.UTF_8)
        : null;
    if (artifactId != null) artifactId = cedarConfig.getLinkedDataUtil().resolveResourceId(CedarResourceType.TEMPLATE, artifactId);
    String condition = exchange.getRequestHeaders().getFirst("If-Match");

    if (judgement) {
      ObjectNode replacement = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody).path("replacement");
      if (refusesDefinition(replacement)) {
        send(exchange, 400, validationRefusal(), null);
      } else {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
      }
      return;
    }
    if ("PUT".equals(method) && artifactId != null && !ARTIFACTS.containsKey(artifactId)) {
      if (condition != null) {
        send(exchange, 412, JsonMapper.STRICT_MAPPER.createObjectNode().put("message", "absent"), null);
        return;
      }
      Integer refusal = refusingDraftCreationsWithAPage;
      if (refusal != null) {
        byte[] page = ("<html><body><h1>" + refusal + "</h1></body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html");
        exchange.sendResponseHeaders(refusal, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(page);
        }
        return;
      }
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      ARTIFACTS.put(artifactId, created);
      ETAGS.put(artifactId, REVISIONS.incrementAndGet());
      WRITES.add(new Write("PUT", false, null, ETAGS.get(artifactId)));
      send(exchange, 201, created, artifactId);
      return;
    }
    if ("DELETE".equals(method) && artifactId != null) {
      if (!ARTIFACTS.containsKey(artifactId)) {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
        return;
      }
      if (condition == null || !RevisionPreconditionParser.parse(condition).matches(ETAGS.get(artifactId))) {
        send(exchange, condition == null ? 428 : 412, JsonMapper.STRICT_MAPPER.createObjectNode().put("message", "stale"), null);
        return;
      }
      WRITES.add(new Write("DELETE", isSource(artifactId), condition, null));
      ARTIFACTS.remove(artifactId);
      ETAGS.remove(artifactId);
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    if ("PUT".equals(method) && artifactId != null && !isSource(artifactId) && failingDefinitionWrites) {
      if (puttingDraftsBackBeforeTheirDefinitionFails) {
        ObjectNode draft = ARTIFACTS.get(artifactId);
        stampAsAPutBack(draft);
        boolean stampedAChild = false;
        for (JsonNode child : draft.path("properties")) {
          if (child instanceof ObjectNode field && field.path("@type").isTextual()) {
            stampAsAPutBack(field.put(ModelNodeNames.PAV_CREATED_ON, "2030-01-01T00:00:00-08:00")
                .put(ModelNodeNames.PAV_CREATED_BY, "https://metadatacenter.org/users/put-back"));
            stampedAChild = true;
          }
        }
        if (!stampedAChild) {
          throw new IllegalStateException("The case needs a draft with a child: " + draft);
        }
        ETAGS.put(artifactId, REVISIONS.incrementAndGet());
      }
      if (editingDraftsBeforeTheirDefinitionFails) {
        ARTIFACTS.get(artifactId).put("schema:description", "Edited by another writer");
        ETAGS.put(artifactId, REVISIONS.incrementAndGet());
      }
      if (losingDraftsBeforeTheirDefinitionFails) {
        ARTIFACTS.remove(artifactId);
        ETAGS.remove(artifactId);
      }
      WRITES.add(new Write("PUT", false, condition, null));
      send(exchange, 500, JsonMapper.STRICT_MAPPER.createObjectNode().put("message", "The definition was not written"), null);
      return;
    }
    if ("PUT".equals(method) && artifactId != null && refusesDefinition(
        (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody))) {
      send(exchange, 400, validationRefusal(), null);
      return;
    }

    if ("POST".equals(method)) {
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      String mintedId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
      created.put(ModelNodeNames.JSON_LD_ID, mintedId);
      ARTIFACTS.put(mintedId, created);
      ETAGS.put(mintedId, REVISIONS.incrementAndGet());
      send(exchange, 201, created, mintedId);
      return;
    }
    String putBackRefusal = refusingPutBacksOfPublishedTemplates;
    if ("PUT".equals(method) && artifactId != null && putBackRefusal != null && isSource(artifactId)
        && BiboStatus.PUBLISHED.getValue().equals(ARTIFACTS.get(artifactId).path(ModelNodeNames.BIBO_STATUS).asText())) {
      if (putBackRefusal.equals("no answer")) {
        exchange.close();
      } else {
        send(exchange, Integer.parseInt(putBackRefusal),
            JsonMapper.STRICT_MAPPER.createObjectNode().put("message", "The template cannot be put back"), null);
      }
      return;
    }
    if ("PUT".equals(method) && artifactId != null) {
      if (artifactId.equals(raceOnPut)) {
        raceOnPut = null;
        ARTIFACTS.get(artifactId).put("schema:description", "Concurrent editor");
        ETAGS.put(artifactId, REVISIONS.incrementAndGet());
      }
      if (condition == null || !RevisionPreconditionParser.parse(condition).matches(ETAGS.get(artifactId))) {
        send(exchange, condition == null ? 428 : 412, JsonMapper.STRICT_MAPPER.createObjectNode().put("message", "stale"), null);
        return;
      }
      boolean source = isSource(artifactId);
      ObjectNode replacement = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(requestBody);
      ARTIFACTS.put(artifactId, replacement);
      ETAGS.put(artifactId, REVISIONS.incrementAndGet());
      WRITES.add(new Write("PUT", source, condition, ETAGS.get(artifactId)));
      String taken = takingThisIdentityOnPublication;
      if (source && taken != null) {
        takingThisIdentityOnPublication = null;
        ARTIFACTS.put(taken, templateDocument("Another writer's template", null).put(ModelNodeNames.JSON_LD_ID, taken));
        ETAGS.put(taken, REVISIONS.incrementAndGet());
      }
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

  /** The modification stamps an ordinary write gives a document it stores. */
  private static void stampAsAPutBack(ObjectNode document) {
    document.put(ModelNodeNames.PAV_LAST_UPDATED_ON, "2030-01-01T00:00:00-08:00")
        .put(ModelNodeNames.OSLC_MODIFIED_BY, "https://metadatacenter.org/users/put-back");
  }

  /** Whether a document is one of the templates the cases publish, rather than a draft of one. */
  private static boolean isSource(String artifactId) {
    return !ARTIFACTS.containsKey(artifactId)
        || !ARTIFACTS.get(artifactId).path(ModelNodeNames.PAV_PREVIOUS_VERSION).isTextual()
        || ARTIFACTS.get(artifactId).path(ModelNodeNames.PAV_PREVIOUS_VERSION).asText().isEmpty();
  }

  private static boolean refusesDefinition(ObjectNode definition) {
    return refusingDefinitionsWithoutModifiedBy && !definition.path("properties").has(ModelNodeNames.OSLC_MODIFIED_BY);
  }

  /** The artifact server's refusal of an invalid definition, in the shape it answers a PUT with. */
  private static ObjectNode validationRefusal() {
    ObjectNode refusal = JsonMapper.STRICT_MAPPER.createObjectNode();
    refusal.put("errorKey", "invalidData").put("errorReasonKey", "validationError")
        .put("message", "There was an error while validating the artifact");
    refusal.putObject("objects").putObject("validationReport").put("validates", "false").putArray("errors")
        .addObject().put("message", "object has missing required properties (['oslc:modifiedBy'])");
    return refusal;
  }

  private static void send(HttpExchange exchange, int status, JsonNode body, String location) throws IOException {
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    Integer revision = ETAGS.get(body.path("@id").asText());
    if (revision != null) exchange.getResponseHeaders().set("ETag", "\"" + revision + "\"");
    if (location != null) {
      exchange.getResponseHeaders().set("Location", location);
    }
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
