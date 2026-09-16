package org.metadatacenter.cedar.resource.restore;

import org.junit.jupiter.api.Test;
import org.metadatacenter.model.CedarResourceType;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4jBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compensating write has to outlive the request that needed it, and the process that was
 * serving it. These cover the states it can be found in afterwards.
 */
class Neo4jArtifactRestoreOutboxTest {

  private static final String PRE_IMAGE = "{\"schema:name\":\"before\"}";

  @Test
  void aPendingRestoreSurvivesRestart() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build()) {
      String jobId;
      try (var first = new Neo4jArtifactRestoreOutbox(
          GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
        ArtifactRestoreJob prepared =
            first.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", true);
        jobId = prepared.jobId();
        assertEquals(1, first.count());
      }

      try (var restarted = new Neo4jArtifactRestoreOutbox(
          GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
        ArtifactRestoreJob resumed = restarted.pending(10).get(0);
        assertEquals(jobId, resumed.jobId());
        assertEquals("artifact-1", resumed.resourceId());
        assertEquals(CedarResourceType.TEMPLATE, resumed.resourceType());
        assertEquals(PRE_IMAGE, resumed.preImage());
        assertEquals("\"8\"", resumed.conditionEtag());
        assertTrue(resumed.verbatim(), "a verbatim write has to be put back verbatim");

        restarted.remove(jobId);
        assertEquals(0, restarted.count());
      }
    }
  }

  /**
   * A second failed update moves the document on. The condition has to name where it is now, or the
   * restore can never apply; the content has to stay the oldest one, because that is what the graph
   * still describes.
   */
  @Test
  void asecondFailedUpdateAdvancesTheConditionAndKeepsTheOldestContent() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactRestoreOutbox(
             GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", false);
      outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, "{\"schema:name\":\"middle\"}", "\"9\"", false);

      assertEquals(1, outbox.count(), "one artifact has one outstanding restore");
      ArtifactRestoreJob job = outbox.pending(10).get(0);
      assertEquals(PRE_IMAGE, job.preImage());
      assertEquals("\"9\"", job.conditionEtag());
    }
  }

  @Test
  void deferringCountsTheAttemptsSoARetryBudgetCanBeSpent() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactRestoreOutbox(
             GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      String jobId = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", false).jobId();

      assertEquals(1, outbox.defer(jobId));
      assertEquals(2, outbox.defer(jobId));
    }
  }

  @Test
  void aParkedRestoreStopsBeingRetriedAndStaysFindable() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactRestoreOutbox(
             GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      String jobId = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", false).jobId();

      outbox.park(jobId, "the artifact server refused the restore with 400");

      assertTrue(outbox.pending(10).isEmpty(), "a parked restore is waiting on a person");
      assertEquals(1, outbox.parkedCount());
      assertEquals(1, outbox.count(), "and it stays recorded, because the stores still disagree");
    }
  }
  @Test
  void committedGraphUpdateRetiresEvenAPreviouslyFetchedRestore() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
         var outbox = new Neo4jArtifactRestoreOutbox(driver, 0)) {
      var job = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", true);
      var fetched = outbox.pending(10).get(0);
      try (var session = driver.session()) {
        session.writeTransaction(tx -> {
          assertTrue(org.metadatacenter.server.neo4j.ArtifactRestoreTransaction.lockForGraph(tx, job.jobId()));
          tx.run("CREATE (:ReviewArtifact {name: 'after'})").consume();
          org.metadatacenter.server.neo4j.ArtifactRestoreTransaction.remove(tx, job.jobId());
          return null;
        });
      }
      outbox.restoreIfPending(fetched, ignored -> { throw new AssertionError("A committed save must not be restored"); });
      assertEquals(0, outbox.count());
    }
  }

  @Test
  void rolledBackGraphTransactionLeavesTheRestoreAvailable() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
         var outbox = new Neo4jArtifactRestoreOutbox(driver, 0)) {
      var job = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", true);
      try (var session = driver.session(); var tx = session.beginTransaction()) {
        assertTrue(org.metadatacenter.server.neo4j.ArtifactRestoreTransaction.lockForGraph(tx, job.jobId()));
        org.metadatacenter.server.neo4j.ArtifactRestoreTransaction.remove(tx, job.jobId());
        tx.rollback();
      }
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      outbox.restoreIfPending(job, pending -> {
        assertEquals(PRE_IMAGE, pending.preImage());
        calls.incrementAndGet();
        return 200;
      });
      assertEquals(1, calls.get());
      assertEquals(0, outbox.count());
    }
  }

  @Test
  void anOldRequestCannotRestoreOrRemoveANewerJob() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactRestoreOutbox(GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      var first = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", false);
      var next = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, "middle", "\"9\"", false);
      outbox.remove(first.jobId());
      outbox.restoreIfPending(first, ignored -> { throw new AssertionError("Stale job must not run"); });
      assertEquals(next.jobId(), outbox.pending(10).get(0).jobId());
      outbox.restoreIfPending(next, pending -> { assertEquals(PRE_IMAGE, pending.preImage()); return 200; });
      assertEquals(0, outbox.count());
    }
  }

  @Test
  void legacyJobsAreParkedBecauseGraphCompletionIsUnknown() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none())) {
      try (var session = driver.session()) {
        session.run("CREATE (:CedarArtifactRestoreOutbox {jobId: 'old', resourceId: 'artifact-1'})").consume();
      }
      try (var outbox = new Neo4jArtifactRestoreOutbox(driver, 0)) {
        assertEquals(1, outbox.parkedCount());
        assertTrue(outbox.pending(10).isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", false));
        assertEquals(1, outbox.count());
      }
    }
  }

  @Test
  void anUncertainRestorePreventsTheOriginalGraphWriteFromCommitting() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none());
         var outbox = new Neo4jArtifactRestoreOutbox(driver, 0)) {
      var job = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, PRE_IMAGE, "\"8\"", true);
      outbox.restoreIfPending(job, ignored -> 503);
      try (var session = driver.session()) {
        boolean admitted = session.writeTransaction(tx ->
            org.metadatacenter.server.neo4j.ArtifactRestoreTransaction.lockForGraph(tx, job.jobId()));
        org.junit.jupiter.api.Assertions.assertFalse(admitted);
      }
      assertEquals(1, outbox.count());
    }
  }

}
