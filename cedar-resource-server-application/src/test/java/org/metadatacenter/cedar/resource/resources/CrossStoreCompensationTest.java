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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.metadatacenter.artifacts.model.core.Annotations;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.cedar.resource.restore.ArtifactRestoreCompletionService;
import org.metadatacenter.cedar.resource.restore.ArtifactRestoreJob;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.ModelNodeNames;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.neo4j.Neo4jConfig;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.util.http.RevisionPreconditionParser;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;
import org.metadatacenter.util.test.TestHttpClient;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a write that touches both stores does when the step after its artifact write fails.
 *
 * <p>A write through the resource server stores the document on the artifact server first and updates
 * the graph second. When the second step fails, the document is put back by a PUT conditional on the
 * ETag of the replacement, so that the put-back cannot undo a later write. The put-back is verbatim
 * when the write was. Production repairs write verbatim, and an ordinary put-back would store the
 * pre-image with fresh provenance, a document neither store held before. The put-back is recorded in
 * the restore outbox before the graph step runs, and one the request cannot make stays there for the
 * relay. Where nothing could be recorded, the request makes the only attempt there is, and that
 * attempt must not overwrite a later write either.
 *
 * <p>The artifact server is a stub that gives every document it stores the next revision of one
 * series, and answers as the artifact server does unless a case arms a fault. The graph is embedded.
 */
@Tag("integration")
public class CrossStoreCompensationTest {

  private record Stored(ObjectNode body, long revision) {
    String etag() {
      return RevisionPreconditionParser.format(revision);
    }
  }

  /** A request the stub received: its method, the identifier it names, its If-Match and whether it was verbatim. */
  private record Seen(String method, String id, String condition, boolean verbatim, String body) {
  }

  /** A write the stub applied, with the ETag of the revision it made. */
  private record Applied(String method, String id, String condition, boolean verbatim, String etag) {
  }

  /** Answers a request in place of the stub's store, or declines it by returning false. */
  private interface Fault {
    boolean answer(HttpExchange exchange, Seen request) throws IOException;
  }

  private static final Map<String, Stored> DOCUMENTS = new ConcurrentHashMap<>();
  private static final List<Seen> SEEN = new CopyOnWriteArrayList<>();
  private static final List<Applied> APPLIED = new CopyOnWriteArrayList<>();
  private static final List<Fault> FAULTS = new CopyOnWriteArrayList<>();
  private static final AtomicLong REVISIONS = new AtomicLong();
  private static HttpServer artifactServer;

  static {
    try {
      artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("Could not bind the stub artifact server", e);
    }
    artifactServer.createContext("/", CrossStoreCompensationTest::handleArtifactRequest);
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
  private static String userAuthHeader;
  private static String adminAuthHeader;
  private static CedarFolderId homeFolderId;
  private static FolderServiceSession folderSession;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    cedarConfig = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    userAuthHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    adminAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
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

  @AfterEach
  public void disarm() {
    FAULTS.clear();
  }

  /**
   * The step after an edit's artifact write fails, and the document is put back. The put-back names the
   * replacement's ETag and is verbatim exactly when the edit was, whether the restore outbox makes it or
   * the request does because nothing could be recorded. Both stores then describe the artifact as they
   * did before the edit, and no restore is left pending.
   */
  @ParameterizedTest(name = "put back by {0}, verbatim {1}")
  @CsvSource({"the restore outbox, false", "the restore outbox, true", "the request, false", "the request, true"})
  public void aFailedStepAfterAnEditPutsTheDocumentBack(String by, boolean verbatim) throws Exception {
    String id = createTemplate("Edit that is put back");
    Stored before = DOCUMENTS.get(id);
    FAULTS.add(unreadableAnswerToTheNextReplacementOf(id));

    HttpResponse<String> response = by.equals("the request")
        ? withoutTheRestoreOutbox(() -> edit(id, before, verbatim))
        : edit(id, before, verbatim);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    assertPutBack(id, before, verbatim);
    Assertions.assertNull(restoreJobOf(id), "a restore is still pending after it succeeded");
  }

  /**
   * A put-back the artifact server refuses in the request stays in the restore outbox. The job holds the
   * pre-image, the replacement's ETag and whether the edit was verbatim. It is not parked, since an
   * artifact server that is unavailable can come back. The relay then completes it as the request would
   * have.
   */
  @ParameterizedTest(name = "verbatim {0}")
  @ValueSource(booleans = {false, true})
  public void aPutBackTheRequestCannotMakeIsLeftForTheRelay(boolean verbatim) throws Exception {
    String id = createTemplate("Edit whose put-back waits for the relay");
    Stored before = DOCUMENTS.get(id);
    FAULTS.add(unreadableAnswerToTheNextReplacementOf(id));
    Fault refusingPutBacks = refusing("PUT", id, 503);
    FAULTS.add(refusingPutBacks);

    HttpResponse<String> response = edit(id, before, verbatim);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Applied replacement = appliedTo(id).get(0);
    Map<String, Object> job = restoreJobOf(id);
    Assertions.assertNotNull(job, "the refused put-back was not left for the relay");
    Assertions.assertEquals(before.body(), JsonMapper.STRICT_MAPPER.readTree((String) job.get("preImage")));
    Assertions.assertEquals(replacement.etag(), job.get("conditionEtag"));
    Assertions.assertEquals(verbatim, job.get("verbatim"), "the job does not know whether the edit was verbatim");
    Assertions.assertNotEquals(Boolean.TRUE, job.get("parked"), "a refusal that may pass parked the job");

    FAULTS.remove(refusingPutBacks);
    AbstractResourceServerResource.artifactRestoreCompletionService.restoreNow(jobOf(job),
        CedarRequestContextFactory.fromUser(TestAuthUtil.getAdminUser(cedarConfig)));

    assertPutBack(id, before, verbatim);
    Assertions.assertNull(restoreJobOf(id), "the relay's put-back did not retire the job");
  }

  /**
   * With no restore outbox, the request's put-back is the only one there is. Whatever answer it gets, it
   * never overwrites a later write. It names the replacement's ETag, so a document another writer has
   * replaced since keeps their content, and a put-back that is refused or unanswered is never sent again
   * without that condition. The caller is told the edit failed either way.
   */
  @ParameterizedTest(name = "the put-back answered {0}")
  @ValueSource(strings = {"412 after another write", "503", "no answer"})
  public void aPutBackInTheRequestNeverOverwritesALaterWrite(String answer) throws Exception {
    String id = createTemplate("Edit whose put-back meets " + answer);
    Stored before = DOCUMENTS.get(id);
    FAULTS.add(unreadableAnswerToTheNextReplacementOf(id));
    ObjectNode laterWrite = before.body().deepCopy().put(ModelNodeNames.SCHEMA_ORG_NAME, "A later writer's edit");
    FAULTS.add(switch (answer) {
      case "412 after another write" -> (exchange, request) -> {
        if (request.method().equals("PUT") && id.equals(request.id()) && appliedTo(id).size() == 1) {
          store(id, laterWrite.deepCopy(), "PUT", null, false);
        }
        return false;
      };
      case "503" -> refusing("PUT", id, 503);
      default -> refusing("PUT", id, null);
    });

    HttpResponse<String> response = withoutTheRestoreOutbox(() -> edit(id, before, false));

    Assertions.assertEquals(500, response.statusCode(), response.body());
    String replacementEtag = appliedTo(id).get(0).etag();
    List<Seen> putBacks = SEEN.stream().filter(seen -> seen.method().equals("PUT") && id.equals(seen.id())).skip(1).toList();
    Assertions.assertFalse(putBacks.isEmpty(), "no put-back was attempted");
    Assertions.assertTrue(putBacks.stream().allMatch(putBack -> replacementEtag.equals(putBack.condition())),
        "a put-back did not name the replacement's ETag: " + putBacks);
    ObjectNode expected = answer.startsWith("412") ? laterWrite : editOf(before, false);
    Assertions.assertEquals(expected, DOCUMENTS.get(id).body(), "the put-back overwrote what it should have left");
  }

  /**
   * An artifact server that answers a replacement without an ETag leaves nothing to make a put-back
   * conditional on, so none is sent. An unconditional one could overwrite a later write.
   */
  @Test
  public void aReplacementAnsweredWithoutAnEtagIsNotPutBackUnconditionally() throws Exception {
    String id = createTemplate("Edit answered without an ETag");
    Stored before = DOCUMENTS.get(id);
    AtomicBoolean armed = new AtomicBoolean(true);
    FAULTS.add((exchange, request) -> {
      if (!request.method().equals("PUT") || !id.equals(request.id()) || !armed.getAndSet(false)) {
        return false;
      }
      store(id, (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(request.body()), "PUT", request.condition(), false);
      respond(exchange, 200, "not a document", null);
      return true;
    });

    HttpResponse<String> response = edit(id, before, false);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertEquals(1, SEEN.stream().filter(seen -> seen.method().equals("PUT") && id.equals(seen.id())).count(),
        "a put-back was sent with nothing to make it conditional on");
    Assertions.assertNull(restoreJobOf(id), "a put-back with no condition was recorded");
  }

  /**
   * The relay can reach an edit's put-back before the edit's graph step does, when that step is slow.
   * The graph step then finds the document put back and does not commit, and the edit is answered as
   * failed rather than as written. Both stores describe the artifact as it was, and neither the restore
   * nor its receipt is left behind.
   */
  @Test
  public void anEditWhosePutBackRanFirstIsAnsweredAsFailed() throws Exception {
    String id = createTemplate("Edit the relay put back first");
    Stored before = DOCUMENTS.get(id);
    ArtifactRestoreCompletionService original = AbstractResourceServerResource.artifactRestoreCompletionService;
    ArtifactRestoreCompletionService relayFirst = Mockito.spy(original);
    Mockito.doAnswer(call -> {
      ArtifactRestoreJob job = (ArtifactRestoreJob) call.callRealMethod();
      original.restoreNow(job, CedarRequestContextFactory.fromUser(TestAuthUtil.getAdminUser(cedarConfig)));
      return job;
    }).when(relayFirst).prepare(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString(),
        ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
    HttpResponse<String> response;
    try {
      AbstractResourceServerResource.injectArtifactRestoreCompletionService(relayFirst);
      response = edit(id, before, false);
    } finally {
      AbstractResourceServerResource.injectArtifactRestoreCompletionService(original);
    }

    Assertions.assertEquals(500, response.statusCode(), response.body());
    assertPutBack(id, before, false);
    Assertions.assertNull(restoreJobOf(id), "the restore was left pending");
    Assertions.assertEquals(0, count("MATCH (o:CedarArtifactRestoreOutcome {resourceId:$id}) RETURN count(o) AS n", id),
        "the edit left its restore receipt behind");
  }

  /**
   * An edit refused before its artifact write changes neither store, and the artifact server receives
   * no write for it.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"a changed DOI", "a dropped DOI", "a document published since the graph was read",
      "lifecycle metadata the document no longer holds"})
  public void anEditRefusedBeforeItsWriteChangesNeitherStore(String refusal) throws Exception {
    String doi = refusal.contains("DOI") ? "10.5072/FK2-compensation-" + REVISIONS.incrementAndGet() : null;
    String id = createTemplate("Edit refused for " + refusal, "0.0.1", doi);
    if (refusal.startsWith("a document published")) {
      replaceDocument(id, DOCUMENTS.get(id).body().deepCopy().put(ModelNodeNames.BIBO_STATUS, "bibo:published"));
    } else if (refusal.startsWith("lifecycle")) {
      replaceDocument(id, DOCUMENTS.get(id).body().deepCopy().put(ModelNodeNames.PAV_VERSION, "0.0.2"));
    }
    Stored before = DOCUMENTS.get(id);
    ObjectNode edited = templateDocument(id, "Edit that is refused", "0.0.1",
        refusal.equals("a changed DOI") ? "10.5072/FK2-another-doi" : null);

    HttpResponse<String> response = send("PUT", "/templates/" + encode(id), edited.toString(), before.etag(), userAuthHeader);

    Assertions.assertEquals(400, response.statusCode(), response.body());
    String expectedKey = switch (refusal) {
      case "a document published since the graph was read" -> "publishedArtifactCanNotBeChanged";
      case "lifecycle metadata the document no longer holds" -> "invalidData";
      default -> "doiCanNotBeAltered";
    };
    Assertions.assertEquals(expectedKey, JsonMapper.STRICT_MAPPER.readTree(response.body()).path("errorKey").asText(),
        response.body());
    Assertions.assertTrue(writesTo(id).isEmpty(), "a refused edit reached the artifact server: " + writesTo(id));
    Assertions.assertEquals(before, DOCUMENTS.get(id));
    Assertions.assertEquals("Edit refused for " + refusal, nodeOf(id).getName());
  }

  /**
   * A create refused before its artifact write sends the artifact server nothing. A new version series
   * starts as a draft at its first version with no predecessor, and a DOI is minted by its own command.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"a later version", "a published status", "a predecessor", "a DOI"})
  public void aCreateRefusedBeforeItsWriteChangesNeitherStore(String refusal) throws Exception {
    ObjectNode body = templateDocument(null, "Create refused for " + refusal, "0.0.1",
        refusal.equals("a DOI") ? "10.5072/FK2-refused-create" : null);
    body.remove(ModelNodeNames.JSON_LD_ID);
    switch (refusal) {
      case "a later version" -> body.put(ModelNodeNames.PAV_VERSION, "0.0.2");
      case "a published status" -> body.put(ModelNodeNames.BIBO_STATUS, "bibo:published");
      case "a predecessor" -> body.put(ModelNodeNames.PAV_PREVIOUS_VERSION,
          cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
      default -> { }
    }
    int writes = (int) SEEN.stream().filter(seen -> !seen.method().equals("GET")).count();

    HttpResponse<String> response = send("POST", "/templates?folder_id=" + encode(homeFolderId.getId()),
        body.toString(), null, userAuthHeader);

    Assertions.assertEquals(400, response.statusCode(), response.body());
    Assertions.assertEquals(refusal.equals("a DOI") ? "doiCanNotBeSet" : "invalidData",
        JsonMapper.STRICT_MAPPER.readTree(response.body()).path("errorKey").asText(), response.body());
    Assertions.assertEquals(writes, SEEN.stream().filter(seen -> !seen.method().equals("GET")).count(),
        "a refused create reached the artifact server");
  }

  /**
   * The relay removes a document that a create stored and never registered, and fences the create
   * before it does, so that the create cannot register a document that is about to be removed. A create
   * that reaches its graph step after the fence is refused there. Its document is removed, and nothing
   * is left at its identity in either store.
   */
  @Test
  public void aCreateFencedByItsCleanupIsNotRegisteredAndLeavesNoDocument() throws Exception {
    String id = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    FAULTS.add((exchange, request) -> {
      if (!request.method().equals("PUT") || !id.equals(request.id()) || DOCUMENTS.containsKey(id)) {
        return false;
      }
      Stored created = store(id, (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(request.body()), "PUT", null, false);
      run("MATCH (j:CedarArtifactCreateCleanup {resourceId:$id}) SET j.cleanupStarted = true", id);
      respond(exchange, 201, created.body().toString(), created.etag());
      return true;
    });

    HttpResponse<String> response = send("PUT", "/templates/" + encode(id) + "?folder_id=" + encode(homeFolderId.getId()),
        templateDocument(id, "Create the cleanup fenced", "0.0.1", null).toString(), null, userAuthHeader);

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertFalse(DOCUMENTS.containsKey(id), "the fenced create left its document behind");
    Assertions.assertNull(nodeOf(id), "the fenced create was registered");
    Assertions.assertEquals(0, count("MATCH (j:CedarArtifactCreateCleanup {resourceId:$id}) RETURN count(j) AS n", id),
        "the cleanup did not finish");
  }

  /**
   * A deletion refused before its DELETE is sent changes neither store and leaves no deletion for the
   * relay to finish later. The read before the deletion must name exactly one revision, since that is
   * what the deletion and every retry of it are conditional on.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"no If-Match", "a stale If-Match", "a read answered without an ETag",
      "a read answered with an ETag that names no revision"})
  public void aDeletionRefusedBeforeItsDeleteChangesNeitherStore(String refusal) throws Exception {
    String id = createTemplate("Deletion refused for " + refusal);
    Stored before = DOCUMENTS.get(id);
    if (refusal.startsWith("a read")) {
      String etag = refusal.endsWith("without an ETag") ? null : "\"not-a-revision\"";
      FAULTS.add((exchange, request) -> {
        if (!request.method().equals("GET") || !id.equals(request.id())) {
          return false;
        }
        respond(exchange, 200, before.body().toString(), etag);
        return true;
      });
    }
    String ifMatch = switch (refusal) {
      case "no If-Match" -> null;
      case "a stale If-Match" -> RevisionPreconditionParser.format(before.revision() - 1);
      default -> before.etag();
    };

    HttpResponse<String> response = send("DELETE", "/templates/" + encode(id), null, ifMatch, userAuthHeader);

    int expected = switch (refusal) {
      case "no If-Match" -> 428;
      case "a stale If-Match" -> 412;
      default -> 502;
    };
    Assertions.assertEquals(expected, response.statusCode(), response.body());
    if (expected == 412) {
      JsonNode error = JsonMapper.STRICT_MAPPER.readTree(response.body());
      Assertions.assertEquals("artifactHasMovedOn", error.path("errorKey").asText(), response.body());
      Assertions.assertEquals(before.etag(), error.path("parameters").path("currentETag").asText(), response.body());
    }
    Assertions.assertTrue(writesTo(id).isEmpty(), "a refused deletion reached the artifact server");
    Assertions.assertEquals(before, DOCUMENTS.get(id));
    Assertions.assertNotNull(nodeOf(id), "a refused deletion removed the graph node");
    Assertions.assertEquals(0, count("MATCH (j:CedarArtifactDeletionOutbox {resourceId:$id}) RETURN count(j) AS n", id),
        "a refused deletion was left for the relay to finish");
  }

  /**
   * The graph judges a publication again when it records it, and can refuse one the document allowed,
   * here because the graph holds a later version than the document. The publication is answered with
   * 409. Its document is put back through the restore outbox, conditional on the ETag the publication
   * returned, and the graph keeps the draft it holds.
   */
  @Test
  public void aPublicationTheGraphRefusesIsPutBackThroughTheRestoreOutbox() throws Exception {
    String id = createTemplate("Publication the graph refuses", "3.0.0", null);
    replaceDocument(id, DOCUMENTS.get(id).body().deepCopy().put(ModelNodeNames.PAV_VERSION, "1.0.0"));
    Stored before = DOCUMENTS.get(id);

    HttpResponse<String> response = send("POST", "/command/publish-artifact",
        "{\"@id\":\"" + id + "\",\"newVersion\":\"2.0.0\"}", null, userAuthHeader);

    Assertions.assertEquals(409, response.statusCode(), response.body());
    Assertions.assertEquals("versioningOnlyOnLatest",
        JsonMapper.STRICT_MAPPER.readTree(response.body()).path("errorKey").asText(), response.body());
    List<Applied> writes = appliedTo(id);
    Assertions.assertEquals(2, writes.size(), writes.toString());
    Assertions.assertEquals(writes.get(0).etag(), writes.get(1).condition(),
        "the put-back did not name the ETag the publication returned");
    Assertions.assertFalse(writes.get(1).verbatim());
    Assertions.assertEquals(before.body(), DOCUMENTS.get(id).body(), "the draft document was not put back");
    FolderServerSchemaArtifact node = (FolderServerSchemaArtifact) nodeOf(id);
    Assertions.assertEquals("bibo:draft", node.getPublicationStatus().getValue());
    Assertions.assertEquals("3.0.0", node.getVersion().getValue());
    Assertions.assertNull(restoreJobOf(id), "a restore is still pending after it succeeded");
  }

  /**
   * A publication answered without an ETag leaves nothing to make its put-back conditional on, so none is
   * sent, with or without a restore outbox. With no outbox, the request's put-back is the only one, and
   * one that is refused or unanswered is never sent again without the publication's ETag. The caller is
   * told of the graph's refusal either way.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"the publication answered without an ETag", "the put-back answered 503",
      "the put-back got no answer"})
  public void aPublicationIsNeverPutBackUnconditionally(String answer) throws Exception {
    String id = createTemplate("Publication put back after " + answer, "3.0.0", null);
    replaceDocument(id, DOCUMENTS.get(id).body().deepCopy().put(ModelNodeNames.PAV_VERSION, "1.0.0"));
    if (answer.endsWith("without an ETag")) {
      AtomicBoolean armed = new AtomicBoolean(true);
      FAULTS.add((exchange, request) -> {
        if (!request.method().equals("PUT") || !id.equals(request.id()) || !armed.getAndSet(false)) {
          return false;
        }
        Stored published = store(id, (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(request.body()), "PUT",
            request.condition(), false);
        respond(exchange, 200, published.body().toString(), null);
        return true;
      });
    } else {
      Fault refusal = refusing("PUT", id, answer.endsWith("503") ? 503 : null);
      FAULTS.add((exchange, request) -> appliedTo(id).size() == 1 && refusal.answer(exchange, request));
    }
    Call publish = () -> send("POST", "/command/publish-artifact",
        "{\"@id\":\"" + id + "\",\"newVersion\":\"2.0.0\"}", null, userAuthHeader);

    HttpResponse<String> response = answer.endsWith("without an ETag") ? publish.run() : withoutTheRestoreOutbox(publish);

    Assertions.assertEquals(409, response.statusCode(), response.body());
    List<Seen> puts = SEEN.stream().filter(seen -> seen.method().equals("PUT") && id.equals(seen.id())).toList();
    if (answer.endsWith("without an ETag")) {
      Assertions.assertEquals(1, puts.size(), "a put-back was sent with nothing to make it conditional on: " + puts);
      Assertions.assertNull(restoreJobOf(id), "a put-back with no condition was recorded");
    } else {
      String publishedEtag = appliedTo(id).get(0).etag();
      Assertions.assertTrue(puts.size() > 1, "no put-back was attempted");
      Assertions.assertTrue(puts.stream().skip(1).allMatch(putBack -> publishedEtag.equals(putBack.condition())),
          "a put-back did not name the publication's ETag: " + puts);
    }
    Assertions.assertEquals(1, appliedTo(id).size(), "a refused put-back changed the document");
  }

  /**
   * The artifact server's refusal of a draft is answered with the refusal's own status, whatever its body.
   * An answer that did not come from CEDAR's own error handling, such as a container's error page, carries
   * no JSON. Nothing is created in either store, and the published source is left as it was.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(ints = {400, 502, 503})
  public void aDraftRefusedWithABodyThatIsNotJsonIsAnsweredWithTheRefusalsStatus(int status) throws Exception {
    String id = createTemplate("Draft refused with an error page", "1.0.0", null);
    folderSession.updateArtifactById(artifactId(id), CedarResourceType.TEMPLATE,
        Map.of(org.metadatacenter.server.neo4j.cypher.NodeProperty.PUBLICATION_STATUS, "bibo:published",
            org.metadatacenter.server.neo4j.cypher.NodeProperty.VERSION, "1.0.0"));
    replaceDocument(id, DOCUMENTS.get(id).body().deepCopy().put(ModelNodeNames.BIBO_STATUS, "bibo:published"));
    Stored before = DOCUMENTS.get(id);
    int documents = DOCUMENTS.size();
    FAULTS.add((exchange, request) -> {
      if (!request.method().equals("POST")) {
        return false;
      }
      exchange.getResponseHeaders().set("Content-Type", "text/html");
      byte[] page = ("<html><body><h1>" + status + "</h1></body></html>").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, page.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(page);
      }
      return true;
    });

    HttpResponse<String> response = send("POST", "/command/create-draft-artifact",
        "{\"@id\":\"" + id + "\",\"newVersion\":\"1.0.1\",\"folderId\":\"" + homeFolderId.getId()
            + "\",\"propagateSharing\":false,\"newFolderName\":\"\"}", null, userAuthHeader);

    Assertions.assertEquals(status, response.statusCode(), response.body());
    Assertions.assertEquals(before, DOCUMENTS.get(id));
    Assertions.assertEquals(documents, DOCUMENTS.size(), "a refused draft left a document behind");
    Assertions.assertEquals(1, folderSession.getVersionHistory(org.metadatacenter.id.CedarTemplateId.build(id)).size(),
        "a refused draft reached the graph");
    Assertions.assertTrue(((FolderServerSchemaArtifact) nodeOf(id)).isLatestVersion());
  }

  /** The edit every compensation case makes: the stored document under a new name, or a stated provenance. */
  private static HttpResponse<String> edit(String id, Stored before, boolean verbatim) throws Exception {
    return send("PUT", "/templates/" + encode(id) + (verbatim ? "?verbatim=true" : ""),
        editOf(before, verbatim).toString(), before.etag(), verbatim ? adminAuthHeader : userAuthHeader);
  }

  /** A verbatim edit states its own provenance, which is what a repair sets out to keep. */
  private static ObjectNode editOf(Stored before, boolean verbatim) {
    ObjectNode edited = before.body().deepCopy().put(ModelNodeNames.SCHEMA_ORG_NAME, "An edit that does not reach the graph");
    if (verbatim) {
      edited.put(ModelNodeNames.OSLC_MODIFIED_BY, TestAuthUtil.getTestUser2(cedarConfig).getId());
      edited.put(ModelNodeNames.PAV_LAST_UPDATED_ON, "2017-12-29T08:48:17-08:00");
    }
    return edited;
  }

  /**
   * The document is back to what it was before the edit and the graph still describes it. The put-back
   * was the second write the document received: conditional on the edit's ETag, and verbatim as the
   * edit was.
   */
  private static void assertPutBack(String id, Stored before, boolean verbatim) {
    Assertions.assertEquals(before.body(), DOCUMENTS.get(id).body(), "the document was not put back");
    Assertions.assertEquals(before.body().path(ModelNodeNames.SCHEMA_ORG_NAME).asText(), nodeOf(id).getName(),
        "the graph describes the edit that failed");
    List<Applied> writes = appliedTo(id);
    Assertions.assertEquals(2, writes.size(), writes.toString());
    Applied edit = writes.get(0);
    Applied putBack = writes.get(1);
    Assertions.assertEquals(verbatim, edit.verbatim());
    Assertions.assertEquals(edit.etag(), putBack.condition(), "the put-back did not name the replacement's ETag");
    Assertions.assertEquals(verbatim, putBack.verbatim(),
        verbatim ? "the put-back of a verbatim edit was not verbatim" : "the put-back of an ordinary edit was verbatim");
  }

  /** Stores the next replacement of the document, then answers with something that is not the document. */
  private static Fault unreadableAnswerToTheNextReplacementOf(String id) {
    AtomicBoolean armed = new AtomicBoolean(true);
    return (exchange, request) -> {
      Stored current = DOCUMENTS.get(id);
      if (!request.method().equals("PUT") || !id.equals(request.id()) || current == null
          || !current.etag().equals(request.condition()) || !armed.getAndSet(false)) {
        return false;
      }
      Stored replaced = store(id, (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(request.body()), "PUT",
          request.condition(), request.verbatim());
      respond(exchange, 200, "not a document", replaced.etag());
      return true;
    };
  }

  /** Refuses every request of the method naming the document, with the status, or with no answer when it is null. */
  private static Fault refusing(String method, String id, Integer status) {
    return (exchange, request) -> {
      if (!request.method().equals(method) || !id.equals(request.id())) {
        return false;
      }
      if (status == null) {
        exchange.close();
      } else {
        respond(exchange, status, "{\"message\":\"The artifact server refuses for now\"}", null);
      }
      return true;
    };
  }

  private interface Call {
    HttpResponse<String> run() throws Exception;
  }

  /** Runs a call with no restore outbox, as a write whose put-back could not be recorded runs. */
  private static HttpResponse<String> withoutTheRestoreOutbox(Call call) throws Exception {
    ArtifactRestoreCompletionService original = AbstractResourceServerResource.artifactRestoreCompletionService;
    try {
      AbstractResourceServerResource.injectArtifactRestoreCompletionService(null);
      return call.run();
    } finally {
      AbstractResourceServerResource.injectArtifactRestoreCompletionService(original);
    }
  }

  private static String createTemplate(String name) throws Exception {
    return createTemplate(name, "0.0.1", null);
  }

  /** A draft template the test user owns, present in both stores at the version given. */
  private static String createTemplate(String name, String version, String doi) throws Exception {
    String id = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(id);
    template.setName(name);
    template.setDescription("Cross-store compensation fixture");
    template.setVersion(version);
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    if (doi != null) {
      template.setDOI(doi);
    }
    Assertions.assertNotNull(folderSession.createResourceAsChildOfId(template, homeFolderId));
    replaceDocument(id, templateDocument(id, name, version, doi));
    return id;
  }

  private static ObjectNode templateDocument(String id, String name, String version, String doi) {
    TemplateSchemaArtifact.Builder builder = TemplateSchemaArtifact.builder()
        .withName(name)
        .withDescription("Cross-store compensation fixture")
        .withVersion(Version.fromString(version))
        .withStatus(Status.DRAFT);
    if (id != null) {
      builder.withJsonLdId(URI.create(id));
    }
    if (doi != null) {
      builder.withAnnotations(Annotations.builder()
          .withIriAnnotation(ModelNodeNames.DATACITE_DOI_URI, URI.create(doi))
          .build());
    }
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(builder.build());
  }

  /** Puts a document in place as a case's starting point, at a fresh revision, without recording a write. */
  private static void replaceDocument(String id, ObjectNode document) {
    DOCUMENTS.put(id, new Stored(document, REVISIONS.incrementAndGet()));
  }

  private static FolderServerArtifact nodeOf(String id) {
    return folderSession.findArtifactById(artifactId(id));
  }

  private static CedarArtifactId artifactId(String id) {
    return CedarArtifactId.build(id, CedarResourceType.TEMPLATE);
  }

  private static List<Applied> appliedTo(String id) {
    return APPLIED.stream().filter(write -> id.equals(write.id())).toList();
  }

  private static List<Seen> writesTo(String id) {
    return SEEN.stream().filter(seen -> !seen.method().equals("GET") && id.equals(seen.id())).toList();
  }

  /** The restore outbox's job for an artifact, as its properties, or null when there is none. */
  private static Map<String, Object> restoreJobOf(String id) {
    try (var driver = graphDriver(); var session = driver.session()) {
      var found = session.run("MATCH (e:CedarArtifactRestoreOutbox {resourceId:$id}) RETURN properties(e) AS job",
          Map.of("id", id));
      return found.hasNext() ? found.single().get("job").asMap() : null;
    }
  }

  private static ArtifactRestoreJob jobOf(Map<String, Object> job) {
    return new ArtifactRestoreJob((String) job.get("jobId"), (String) job.get("resourceId"),
        CedarResourceType.forValue((String) job.get("resourceType")), (String) job.get("preImage"),
        (String) job.get("conditionEtag"), Boolean.TRUE.equals(job.get("verbatim")));
  }

  private static long count(String query, String id) {
    try (var driver = graphDriver(); var session = driver.session()) {
      return session.run(query, Map.of("id", id)).single().get("n").asLong();
    }
  }

  private static void run(String query, String id) {
    try (var driver = graphDriver(); var session = driver.session()) {
      session.run(query, Map.of("id", id)).consume();
    }
  }

  private static org.neo4j.driver.Driver graphDriver() {
    Neo4jConfig neo = Neo4jConfig.fromCedarConfig(cedarConfig);
    return GraphDatabase.driver(neo.getUri(), AuthTokens.basic(neo.getUserName(), neo.getUserPassword()));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static HttpResponse<String> send(String method, String path, String body, String ifMatch,
                                           String authorization) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authorization)
        .header("Content-Type", "application/json");
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return TestHttpClient.send(request.build());
  }

  /**
   * The stub versions every document it holds and refuses a write or a deletion whose If-Match does not
   * name the current revision. A PUT without If-Match at an identifier nothing holds creates there, as the
   * artifact server's does. An armed fault answers first.
   */
  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getQuery();
    String id = null;
    for (CedarResourceType type : List.of(CedarResourceType.TEMPLATE, CedarResourceType.ELEMENT,
        CedarResourceType.FIELD, CedarResourceType.INSTANCE)) {
      String collection = "/" + type.getPrefix() + "/";
      if (path.startsWith(collection) && path.length() > collection.length()) {
        id = cedarConfig.getLinkedDataUtil().resolveResourceId(type,
            URLDecoder.decode(path.substring(collection.length()), StandardCharsets.UTF_8));
      }
    }
    String condition = exchange.getRequestHeaders().getFirst("If-Match");
    Seen request = new Seen(method, id, condition, query != null && query.contains("verbatim=true"), body);
    SEEN.add(request);
    for (Fault fault : FAULTS) {
      if (fault.answer(exchange, request)) {
        return;
      }
    }

    if (method.equals("POST") && id == null) {
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(body);
      String minted = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
      created.put(ModelNodeNames.JSON_LD_ID, minted);
      Stored stored = store(minted, created, "POST", null, false);
      exchange.getResponseHeaders().set("Location", minted);
      respond(exchange, 201, stored.body().toString(), stored.etag());
      return;
    }
    Stored current = id == null ? null : DOCUMENTS.get(id);
    if (method.equals("PUT") && current == null && condition == null && id != null) {
      Stored stored = store(id, (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(body), "PUT", null, request.verbatim());
      exchange.getResponseHeaders().set("Location", id);
      respond(exchange, 201, stored.body().toString(), stored.etag());
      return;
    }
    if (current == null) {
      respond(exchange, 404, "{\"message\":\"Not found\"}", null);
      return;
    }
    if (method.equals("GET")) {
      respond(exchange, 200, current.body().toString(), current.etag());
      return;
    }
    if (condition == null || !RevisionPreconditionParser.parse(condition).matches(current.revision())) {
      respond(exchange, condition == null ? 428 : 412, "{\"message\":\"The artifact has moved on\"}", null);
      return;
    }
    if (method.equals("DELETE")) {
      DOCUMENTS.remove(id);
      APPLIED.add(new Applied("DELETE", id, condition, false, null));
      exchange.getResponseHeaders().set("Connection", "close");
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    if (method.equals("PUT")) {
      Stored stored = store(id, (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(body), "PUT", condition, request.verbatim());
      respond(exchange, 200, stored.body().toString(), stored.etag());
      return;
    }
    respond(exchange, 405, "{}", null);
  }

  private static Stored store(String id, ObjectNode document, String method, String condition, boolean verbatim) {
    Stored stored = new Stored(document, REVISIONS.incrementAndGet());
    DOCUMENTS.put(id, stored);
    APPLIED.add(new Applied(method, id, condition, verbatim, stored.etag()));
    return stored;
  }

  private static void respond(HttpExchange exchange, int status, String body, String etag) throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (etag != null) {
      exchange.getResponseHeaders().set("ETag", etag);
    }
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
