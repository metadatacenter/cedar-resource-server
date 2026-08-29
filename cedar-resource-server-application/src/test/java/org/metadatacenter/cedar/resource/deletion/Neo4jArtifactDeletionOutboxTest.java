package org.metadatacenter.cedar.resource.deletion;

import org.junit.jupiter.api.Test;
import org.metadatacenter.model.CedarResourceType;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4jBuilders;

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
}
