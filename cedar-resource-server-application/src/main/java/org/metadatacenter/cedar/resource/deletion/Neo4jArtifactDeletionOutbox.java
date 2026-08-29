package org.metadatacenter.cedar.resource.deletion;

import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.CedarTestRuntime;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.server.neo4j.Neo4jConfig;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A durable saga record for cross-store artifact deletion.
 *
 * <p>The record is independent of the workspace resource node: deleting that node cannot delete the
 * evidence that search and value-recommender cleanup still have to run.</p>
 */
public final class Neo4jArtifactDeletionOutbox implements AutoCloseable {

  private static final String LABEL = "CedarArtifactDeletionOutbox";
  private final Driver driver;
  private final long initialDelayMillis;

  public Neo4jArtifactDeletionOutbox(CedarConfig cedarConfig) {
    Neo4jConfig neo4j = Neo4jConfig.fromCedarConfig(cedarConfig);
    Config.ConfigBuilder driverConfig = Config.builder();
    CedarTestRuntime.dependencyTimeoutMillis().ifPresent(timeout -> driverConfig
        .withConnectionTimeout(timeout, TimeUnit.MILLISECONDS)
        .withConnectionAcquisitionTimeout(timeout, TimeUnit.MILLISECONDS)
        .withMaxTransactionRetryTime(timeout, TimeUnit.MILLISECONDS));
    driver = GraphDatabase.driver(neo4j.getUri(),
        AuthTokens.basic(neo4j.getUserName(), neo4j.getUserPassword()), driverConfig.build());
    initialDelayMillis = 30_000;
    ensureConstraint();
  }

  Neo4jArtifactDeletionOutbox(Driver driver) {
    this(driver, 30_000);
  }

  Neo4jArtifactDeletionOutbox(Driver driver, long initialDelayMillis) {
    this.driver = driver;
    this.initialDelayMillis = initialDelayMillis;
    ensureConstraint();
  }

  private void ensureConstraint() {
    try (Session session = driver.session()) {
      session.run("CREATE CONSTRAINT cedar_artifact_deletion_resource IF NOT EXISTS "
          + "FOR (e:" + LABEL + ") REQUIRE e.resourceId IS UNIQUE").consume();
    }
  }

  public ArtifactDeletionJob prepare(String resourceId,
                                     CedarResourceType resourceType,
                                     String artifactEtag,
                                     String graphSnapshotJson,
                                     String previousVersionId,
                                     boolean artifactDeleted) {
    String query = "MERGE (e:" + LABEL + " {resourceId: $resourceId}) "
        + "ON CREATE SET e.jobId = $jobId, e.resourceType = $resourceType, e.artifactEtag = $artifactEtag, "
        + "e.graphSnapshotJson = $graphSnapshotJson, e.previousVersionId = $previousVersionId, "
        + "e.artifactDeleted = $artifactDeleted, e.graphDeleted = false, e.createdAt = timestamp(), "
        + "e.nextAttemptAt = timestamp() + $initialDelayMillis "
        + "RETURN e";
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("jobId", UUID.randomUUID().toString());
    parameters.put("resourceId", resourceId);
    parameters.put("resourceType", resourceType.getValue());
    parameters.put("artifactEtag", artifactEtag);
    parameters.put("graphSnapshotJson", graphSnapshotJson);
    parameters.put("previousVersionId", previousVersionId);
    parameters.put("artifactDeleted", artifactDeleted);
    parameters.put("initialDelayMillis", initialDelayMillis);
    try (Session session = driver.session()) {
      return session.writeTransaction(tx -> fromRecord(tx.run(query, parameters).single()));
    }
  }

  public List<ArtifactDeletionJob> pending(int limit) {
    String query = "MATCH (e:" + LABEL + ") WHERE coalesce(e.nextAttemptAt, 0) <= timestamp() "
        + "RETURN e ORDER BY e.createdAt, e.jobId LIMIT $limit";
    try (Session session = driver.session()) {
      return session.readTransaction(tx -> {
        List<ArtifactDeletionJob> jobs = new ArrayList<>();
        for (Record record : tx.run(query, Map.of("limit", limit)).list()) {
          jobs.add(fromRecord(record));
        }
        return jobs;
      });
    }
  }

  public void markArtifactDeleted(String jobId) {
    update(jobId, "SET e.artifactDeleted = true, e.nextAttemptAt = timestamp() + $delay", initialDelayMillis);
  }

  public void markGraphDeleted(String jobId) {
    update(jobId, "SET e.graphDeleted = true, e.nextAttemptAt = timestamp() + $delay", initialDelayMillis);
  }

  public void defer(String jobId) {
    update(jobId, "SET e.nextAttemptAt = timestamp() + $delay", 5_000);
  }

  private void update(String jobId, String setter, long delay) {
    try (Session session = driver.session()) {
      session.writeTransaction(tx -> {
        tx.run("MATCH (e:" + LABEL + " {jobId: $jobId}) " + setter,
            Map.of("jobId", jobId, "delay", delay)).consume();
        return null;
      });
    }
  }

  public void remove(String jobId) {
    try (Session session = driver.session()) {
      session.writeTransaction(tx -> {
        tx.run("MATCH (e:" + LABEL + " {jobId: $jobId}) DELETE e", Map.of("jobId", jobId)).consume();
        return null;
      });
    }
  }

  public long count() {
    try (Session session = driver.session()) {
      return session.readTransaction(tx -> tx.run(
          "MATCH (e:" + LABEL + ") RETURN count(e) AS pending").single().get("pending").asLong());
    }
  }

  private static ArtifactDeletionJob fromRecord(Record record) {
    var node = record.get("e").asNode();
    return new ArtifactDeletionJob(
        node.get("jobId").asString(),
        node.get("resourceId").asString(),
        CedarResourceType.forValue(node.get("resourceType").asString()),
        node.get("artifactEtag").isNull() ? null : node.get("artifactEtag").asString(),
        node.get("graphSnapshotJson").asString(),
        node.get("previousVersionId").isNull() ? null : node.get("previousVersionId").asString(),
        node.get("artifactDeleted").asBoolean(false),
        node.get("graphDeleted").asBoolean(false));
  }

  @Override
  public void close() {
    driver.close();
  }
}
