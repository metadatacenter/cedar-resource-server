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
}
