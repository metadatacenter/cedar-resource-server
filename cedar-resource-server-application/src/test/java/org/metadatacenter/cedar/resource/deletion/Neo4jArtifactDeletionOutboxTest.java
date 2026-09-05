package org.metadatacenter.cedar.resource.deletion;

import org.junit.jupiter.api.Test;
import org.metadatacenter.model.CedarResourceType;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4jBuilders;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Neo4jArtifactDeletionOutboxTest {

  @Test
  void deletionSurvivesRestartAndRetainsEachCompletedStage() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build()) {
      String jobId;
      try (var first = new Neo4jArtifactDeletionOutbox(
          GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
        ArtifactDeletionJob prepared = first.prepare("artifact-1", CedarResourceType.TEMPLATE,
            "\"7\"", "{\"resourceType\":\"template\"}", "artifact-0", false);
        jobId = prepared.jobId();
        assertFalse(prepared.artifactDeleted());
        assertEquals(1, first.count());
        first.markArtifactDeleted(jobId);
      }

      try (var restarted = new Neo4jArtifactDeletionOutbox(
          GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
        ArtifactDeletionJob resumed = restarted.pending(10).get(0);
        assertEquals(jobId, resumed.jobId());
        assertEquals("artifact-1", resumed.resourceId());
        assertEquals("\"7\"", resumed.artifactEtag());
        assertTrue(resumed.artifactDeleted());
        assertFalse(resumed.graphDeleted());

        restarted.markGraphDeleted(jobId);
      }

      try (var restartedAgain = new Neo4jArtifactDeletionOutbox(
          GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
        ArtifactDeletionJob projectionsPending = restartedAgain.pending(10).get(0);
        assertTrue(projectionsPending.graphDeleted());
        restartedAgain.remove(jobId);
        assertEquals(0, restartedAgain.count());
      }
    }
  }

  @Test
  void deferringCountsTheAttemptsSoARetryBudgetCanBeSpent() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactDeletionOutbox(
             GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      String jobId = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, "\"7\"",
          "{\"resourceType\":\"template\"}", null, false).jobId();

      assertEquals(1, outbox.defer(jobId));
      assertEquals(2, outbox.defer(jobId));
      assertEquals(3, outbox.defer(jobId));
    }
  }

  /**
   * A refused deletion leaves the artifact in place, so the job is kept and made visible rather
   * than dropped. What must stop is the asking.
   */
  @Test
  void aParkedJobStopsBeingOfferedButStaysInTheOutbox() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactDeletionOutbox(
             GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      String jobId = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, "\"7\"",
          "{\"resourceType\":\"template\"}", null, false).jobId();
      assertEquals(1, outbox.pending(10).size());

      outbox.park(jobId, "Artifact server refused the deletion with 400");

      assertTrue(outbox.pending(10).isEmpty(), "a parked job must not be retried");
      assertEquals(1, outbox.count(), "the job is kept so the refusal can be found");
      assertEquals(1, outbox.parkedCount());
    }
  }

  @Test
  void parkingOneJobLeavesTheOthersRunning() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactDeletionOutbox(
             GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      String refused = outbox.prepare("artifact-1", CedarResourceType.TEMPLATE, "\"7\"",
          "{\"resourceType\":\"template\"}", null, false).jobId();
      outbox.prepare("artifact-2", CedarResourceType.TEMPLATE, "\"8\"",
          "{\"resourceType\":\"template\"}", null, false);

      outbox.park(refused, "Artifact server refused the deletion with 400");

      assertEquals(1, outbox.pending(10).size());
      assertEquals("artifact-2", outbox.pending(10).get(0).resourceId());
      assertEquals(1, outbox.parkedCount());
    }
  }

  @Test
  void concurrentPreparationUsesOneJobPerArtifact() {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactDeletionOutbox(
             GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      ArtifactDeletionJob first = outbox.prepare("artifact-2", CedarResourceType.INSTANCE,
          "\"3\"", "{\"resourceType\":\"instance\"}", null, false);
      ArtifactDeletionJob repeated = outbox.prepare("artifact-2", CedarResourceType.INSTANCE,
          "\"4\"", "{\"resourceType\":\"instance\"}", null, false);
      assertEquals(first.jobId(), repeated.jobId());
      assertEquals("\"3\"", repeated.artifactEtag(), "the first accepted deletion controls the saga");
      assertEquals(1, outbox.count());
    }
  }

  @Test
  void concurrentRemovalNeverReturnsAPropertylessDeletionJob() throws Exception {
    try (var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
         var outbox = new Neo4jArtifactDeletionOutbox(
             GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none()), 0)) {
      var executor = Executors.newFixedThreadPool(2);
      try {
        for (int iteration = 0; iteration < 100; iteration++) {
          ArtifactDeletionJob prepared = outbox.prepare("racing-artifact", CedarResourceType.ELEMENT,
              "\"9\"", "{\"resourceType\":\"element\"}", null, true);
          var pending = executor.submit(() -> outbox.pending(10));
          var removed = executor.submit(() -> outbox.remove(prepared.jobId()));
          for (ArtifactDeletionJob job : pending.get()) {
            assertEquals(prepared.jobId(), job.jobId());
            assertEquals("racing-artifact", job.resourceId());
            assertEquals(CedarResourceType.ELEMENT, job.resourceType());
          }
          removed.get();
        }
      } finally {
        executor.shutdownNow();
      }
      assertEquals(0, outbox.count());
    }
  }
}
