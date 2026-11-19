package org.metadatacenter.cedar.resource.deletion;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.resource.ScratchNeo4j;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFilesystemResourceId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.util.test.TestAuthUtil;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One pass of the deletion relay over jobs whose artifact and graph node are already gone, so that
 * only the search-index cleanup is left. A cleanup that succeeds ends the job; one that keeps failing is
 * retried until its last attempt and then parked for a person, rather than asked again for as long as the
 * process lives. The pass runs directly against a scratch graph, without the server's scheduler.
 */
@Tag("integration")
class ArtifactDeletionRelayPassTest {

  private static CedarConfig config;
  /** Reads the outbox as the test sees it; each outbox opens and closes a driver of its own. */
  private static Driver reader;

  @BeforeAll
  static void blankDatabase() {
    ScratchNeo4j.blank();
    config = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    reader = GraphDatabase.driver(ScratchNeo4j.boltURI(), AuthTokens.none());
  }

  @AfterAll
  static void closeReader() {
    if (reader != null) {
      reader.close();
    }
  }

  @BeforeEach
  void clearDatabase() {
    ScratchNeo4j.clear();
  }

  @Test
  void aCleanupThatSucceedsEndsTheJob() {
    List<String> removed = new CopyOnWriteArrayList<>();
    NoOpNodeIndexingService index = new NoOpNodeIndexingService(config) {
      @Override
      public long removeDocumentFromIndex(CedarFilesystemResourceId resourceId) {
        removed.add(resourceId.getId());
        return 1;
      }
    };
    try (var outbox = new Neo4jArtifactDeletionOutbox(driver(), 0)) {
      String id = awaitingCleanup(outbox, "artifact-cleaned");

      service(index, outbox).resumeSafely();

      assertEquals(List.of(id), removed, "the pass should remove the artifact's index document");
      assertEquals(0, outbox.count(), "a finished cleanup should end its job");
    }
  }

  @Test
  void aCleanupThatKeepsFailingIsParkedAfterItsLastAttempt() {
    NoOpNodeIndexingService index = new NoOpNodeIndexingService(config) {
      @Override
      public long removeDocumentFromIndex(CedarFilesystemResourceId resourceId) {
        throw new IllegalStateException("the index is unavailable");
      }
    };
    try (var outbox = new Neo4jArtifactDeletionOutbox(driver(), 0)) {
      ArtifactDeletionCompletionService relay = service(index, outbox);
      awaitingCleanup(outbox, "artifact-uncleaned");

      relay.resumeSafely();
      assertEquals(Map.of("attempts", 1L, "parked", false), state(), "a first failure should be deferred");

      // Fast-forward to the job's last attempt: fifty-nine failures recorded, and due now.
      try (Session session = reader.session()) {
        session.run("MATCH (e:CedarArtifactDeletionOutbox) SET e.attempts = 59, e.nextAttemptAt = 0").consume();
      }
      relay.resumeSafely();

      Map<String, Object> parked = state();
      assertEquals(60L, parked.get("attempts"));
      assertEquals(true, parked.get("parked"), "the sixtieth failure should park the job");
      assertEquals(1, outbox.count(), "a parked job stays in the outbox for a person to find");
      assertEquals(List.of(), outbox.pending(10), "a parked job is not offered again");
      try (Session session = reader.session()) {
        String reason = session.run("MATCH (e:CedarArtifactDeletionOutbox) RETURN e.parkedReason AS reason")
            .single().get("reason").asString();
        assertTrue(reason.contains("60 attempts"), reason);
        assertNotEquals("", reason);
      }
    }
  }

  /** A recorded deletion whose artifact and graph node are gone, due now. */
  private static String awaitingCleanup(Neo4jArtifactDeletionOutbox outbox, String name) {
    String id = config.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE) + "-" + name;
    ArtifactDeletionJob job = outbox.prepare(id, CedarResourceType.TEMPLATE, "\"3\"",
        "{\"resourceType\":\"template\"}", null, true);
    outbox.markGraphDeleted(job.jobId());
    return id;
  }

  private static ArtifactDeletionCompletionService service(NoOpNodeIndexingService index,
                                                           Neo4jArtifactDeletionOutbox outbox) {
    return new ArtifactDeletionCompletionService(config, TestAuthUtil.getInMemoryUserService(config), index, outbox);
  }

  private static Map<String, Object> state() {
    try (Session session = reader.session()) {
      var record = session.run("MATCH (e:CedarArtifactDeletionOutbox) "
          + "RETURN coalesce(e.attempts, 0) AS attempts, coalesce(e.parked, false) AS parked").single();
      return Map.of("attempts", record.get("attempts").asLong(), "parked", record.get("parked").asBoolean());
    }
  }

  /** A driver for an outbox, which closes it. */
  private static Driver driver() {
    return GraphDatabase.driver(ScratchNeo4j.boltURI(), AuthTokens.none());
  }
}
