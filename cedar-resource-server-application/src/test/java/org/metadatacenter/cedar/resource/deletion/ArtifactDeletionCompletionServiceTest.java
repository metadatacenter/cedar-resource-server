package org.metadatacenter.cedar.resource.deletion;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.neo4j.Neo4jConfig;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A deletion is recorded before its artifact is deleted, so a process that stops between the two leaves
 * a recorded deletion for the server's relay to finish. The relay sends the deletion again, conditional
 * on the ETag the request had read, and the artifact server's answer decides what follows. A deletion, or
 * an artifact that is already gone, finishes in the graph. An artifact edited since then abandons the
 * deletion, and so do instances that reference a template, which the artifact server also answers with
 * 412. A refusal parks it for a person, and an outage leaves it to be tried again. The graph node goes
 * only in the first two cases, and only an outage is asked again.
 */
@Tag("integration")
class ArtifactDeletionCompletionServiceTest {

  private static final String LABEL = "CedarArtifactDeletionOutbox";

  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  /** The status the artifact server answers a deletion of each artifact with, by the artifact's UUID. */
  private static final Map<String, Integer> ANSWERS = new ConcurrentHashMap<>();
  /** The body the artifact server answers a deletion of an artifact with, where it gives one. */
  private static final Map<String, String> BODIES = new ConcurrentHashMap<>();
  /** The If-Match of every deletion the artifact server received, by the artifact's UUID. */
  private static final Map<String, List<String>> DELETIONS = new ConcurrentHashMap<>();
  /** Every artifact this class recorded a deletion for. */
  private static final List<String> RECORDED = new CopyOnWriteArrayList<>();

  private static HttpServer artifactServer;
  private static CedarConfig config;
  private static FolderServiceSession folders;
  private static CedarFolderId home;
  private static Driver driver;
  private static Neo4jArtifactDeletionOutbox outbox;

  @BeforeAll
  static void start() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    artifactServer.createContext("/", ArtifactDeletionCompletionServiceTest::handleArtifact);
    artifactServer.start();
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0", "CEDAR_RESOURCE_ADMIN_PORT", "0", "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1", "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(artifactServer.getAddress().getPort()),
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1", "CEDAR_OPENSEARCH_REST_PORT", "1"));
    SERVER.before();
    config = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(config);
    EmbeddedCedarNeo4j.seed(config);
    folders = CedarDataServices.getInstance().getFolderServiceSession(
        CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(config)));
    home = folders.findHomeFolderOf().getResourceId();
    Neo4jConfig neo4j = Neo4jConfig.fromCedarConfig(config);
    driver = GraphDatabase.driver(neo4j.getUri(), AuthTokens.basic(neo4j.getUserName(), neo4j.getUserPassword()));
    // This writes to the outbox the server reads, without the delay a request's own record is given, so
    // the server's relay takes these jobs up on its next pass.
    outbox = new Neo4jArtifactDeletionOutbox(driver, 0);
  }

  @AfterAll
  static void stop() {
    SERVER.after();
    if (outbox != null) {
      // The graph is shared by every class in this JVM, and a later class's server would carry on
      // sending these deletions to its own artifact server.
      try (Session session = driver.session()) {
        session.run("MATCH (e:" + LABEL + ") WHERE e.resourceId IN $ids DELETE e", Map.of("ids", RECORDED)).consume();
      }
      outbox.close();
    }
    if (artifactServer != null) {
      artifactServer.stop(0);
    }
  }

  @Test
  void theArtifactServersAnswerDecidesWhetherARecordedDeletionFinishesStopsOrWaits() throws Exception {
    ch.qos.logback.classic.Logger relayLog = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(ArtifactDeletionCompletionService.class);
    var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logged.start();
    relayLog.addAppender(logged);
    String deleted = recordedDeletion("Deleted when the relay asks", 204);
    String alreadyGone = recordedDeletion("Already gone when the relay asks", 404);
    String editedSince = recordedDeletion("Edited since its deletion was recorded", 412);
    String usedByInstances = recordedDeletion("Used by instances when the relay asks", 412,
        "{\"errorKey\":\"templateNotDeleted\",\"errorReasonKey\":\"templateReferencedInInstances\"}");
    String refused = recordedDeletion("Refused by the artifact server", 400);
    String unavailable = recordedDeletion("Asked while the artifact server is away", 503);

    // Two attempts at the unavailable one prove that the relay has passed over the others again.
    long deadline = System.currentTimeMillis() + 40_000;
    while (attempts(unavailable) < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
    }
    assertTrue(attempts(unavailable) >= 2, "the relay did not retry the deletion the artifact server was away for");
    // The index this server is given cannot be reached, so a deletion the artifact server answered stays
    // recorded until its index entry is removed. A pass is one attempt at it, as it is at the deletion the
    // artifact server was away for, so both are parked after the same number of passes. The relay takes
    // them in the order they were recorded, so the counts are read once both have had the same passes.
    for (String id : List.of(deleted, alreadyGone)) {
      assertEquals(attempts(unavailable), attempts(id), "a pass counted more than one attempt at " + id);
    }

    relayLog.detachAppender(logged);
    for (String id : List.of(deleted, alreadyGone, editedSince, usedByInstances, refused, unavailable)) {
      assertTrue(DELETIONS.containsKey(uuidOf(id)), "the relay never asked to delete " + id);
      for (String condition : DELETIONS.get(uuidOf(id))) {
        assertEquals(etagFor(ANSWERS.get(uuidOf(id))), condition, "a deletion was sent without the recorded ETag");
      }
    }
    for (String id : List.of(deleted, alreadyGone, editedSince, usedByInstances, refused)) {
      assertEquals(1, DELETIONS.get(uuidOf(id)).size(), "an answered deletion was asked again: " + id);
    }

    for (String id : List.of(deleted, alreadyGone)) {
      assertNull(folders.findArtifactById(CedarTemplateId.build(id)), "the graph node outlived its artifact");
      Map<String, Object> job = job(id);
      assertTrue(job == null || Boolean.TRUE.equals(job.get("artifactDeleted")), String.valueOf(job));
    }

    assertNotNull(folders.findArtifactById(CedarTemplateId.build(editedSince)), "an edited artifact lost its node");
    assertNull(job(editedSince), "a deletion overtaken by an edit was kept");
    assertNotNull(folders.findArtifactById(CedarTemplateId.build(usedByInstances)), "a template instances use lost its node");
    assertNull(job(usedByInstances), "a deletion instances block was kept");
    // The two 412s end the deletion alike, and the log says which of them it was.
    List<String> abandoned = logged.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .filter(message -> message.startsWith("Abandoning deletion")).toList();
    assertTrue(abandoned.stream().anyMatch(message -> message.contains(usedByInstances)
        && message.contains("instances reference the template")), abandoned.toString());
    assertTrue(abandoned.stream().anyMatch(message -> message.contains(editedSince)
        && message.contains("the artifact advanced")), abandoned.toString());

    assertNotNull(folders.findArtifactById(CedarTemplateId.build(refused)), "a refused artifact lost its node");
    Map<String, Object> parked = job(refused);
    assertNotNull(parked, "a refused deletion was dropped rather than kept for a person");
    assertEquals(Boolean.TRUE, parked.get("parked"), parked.toString());
    assertTrue(String.valueOf(parked.get("parkedReason")).contains("400"), parked.toString());

    assertNotNull(folders.findArtifactById(CedarTemplateId.build(unavailable)), "an outage cost the artifact its node");
    Map<String, Object> waiting = job(unavailable);
    assertNotEquals(Boolean.TRUE, waiting.get("parked"), waiting.toString());
    assertNotEquals(Boolean.TRUE, waiting.get("artifactDeleted"), waiting.toString());
  }

  /**
   * A draft template in the test user's home folder, with a deletion recorded for it as a request records
   * one before it deletes the artifact.
   */
  private static String recordedDeletion(String name, int answer) throws Exception {
    return recordedDeletion(name, answer, null);
  }

  private static String recordedDeletion(String name, int answer, String body) throws Exception {
    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName(name);
    template.setVersion("1.0.0");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    FolderServerArtifact created = folders.createResourceAsChildOfId(template, home);
    assertNotNull(created);
    ANSWERS.put(uuidOf(created.getId()), answer);
    if (body != null) {
      BODIES.put(uuidOf(created.getId()), body);
    }
    RECORDED.add(created.getId());
    outbox.prepare(created.getId(), CedarResourceType.TEMPLATE, etagFor(answer),
        JsonMapper.STRICT_MAPPER.writeValueAsString(created), null, false);
    return created.getId();
  }

  /** The last segment of an identifier, which is all of it that an artifact server address may carry. */
  private static String uuidOf(String id) {
    return id.substring(id.lastIndexOf('/') + 1);
  }

  private static String etagFor(int answer) {
    return "\"read-before-" + answer + "\"";
  }

  private static Map<String, Object> job(String resourceId) {
    try (Session session = driver.session()) {
      var result = session.run("MATCH (e:" + LABEL + " {resourceId: $id}) RETURN properties(e) AS job",
          Map.of("id", resourceId));
      return result.hasNext() ? result.next().get("job").asMap() : null;
    }
  }

  private static long attempts(String resourceId) {
    Map<String, Object> job = job(resourceId);
    return job == null || job.get("attempts") == null ? 0 : ((Number) job.get("attempts")).longValue();
  }

  private static void handleArtifact(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    String path = exchange.getRequestURI().getRawPath();
    String id = uuidOf(URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8));
    Integer answer = ANSWERS.get(id);
    if (!"DELETE".equals(exchange.getRequestMethod()) || answer == null) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    DELETIONS.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>())
        .add(exchange.getRequestHeaders().getFirst("If-Match"));
    String body = BODIES.get(id);
    if (body == null) {
      exchange.sendResponseHeaders(answer, -1);
    } else {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(answer, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }
}
