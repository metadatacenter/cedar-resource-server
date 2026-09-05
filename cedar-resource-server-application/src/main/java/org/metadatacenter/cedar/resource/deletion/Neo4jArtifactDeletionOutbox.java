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
import org.neo4j.driver.Result;
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
  private static final String JOB_PROJECTION = "e.jobId AS jobId, e.resourceId AS resourceId, "
      + "e.resourceType AS resourceType, e.artifactEtag AS artifactEtag, "
      + "e.graphSnapshotJson AS graphSnapshotJson, e.previousVersionId AS previousVersionId, "
      + "e.artifactDeleted AS artifactDeleted, e.graphDeleted AS graphDeleted";
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
        + "RETURN " + JOB_PROJECTION;
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

  /**
   * The jobs due for another attempt.
   *
   * <p>The whole property map is read in one step, and a row that does not carry the fields that
   * identify a job is dropped. Neo4j is read committed rather than snapshot isolated, so a
   * {@link #remove} committing while this query runs is visible to it, and a projection that names
   * each property separately is evaluated one property at a time: a delete landing between two of
   * them returned a row whose earlier fields were read and whose later ones were null. A caller then
   * received a job with a null {@code resourceId} for an artifact that no longer had a deletion to
   * finish. {@code properties(e)} is a single read, so a row is either the whole job or nothing that
   * resembles one, and a node being deleted is by definition not pending.
   */
  public List<ArtifactDeletionJob> pending(int limit) {
    String query = "MATCH (e:" + LABEL + ") WHERE coalesce(e.nextAttemptAt, 0) <= timestamp() "
        + "AND coalesce(e.parked, false) = false "
        + "WITH e ORDER BY e.createdAt, e.jobId LIMIT $limit "
        + "RETURN properties(e) AS job";
    try (Session session = driver.session()) {
      return session.readTransaction(tx -> {
        List<ArtifactDeletionJob> jobs = new ArrayList<>();
        for (Record record : tx.run(query, Map.of("limit", limit)).list()) {
          if (record.get("job").isNull()) {
            continue;
          }
          Map<String, Object> properties = record.get("job").asMap();
          if (identifiesAJob(properties)) {
            jobs.add(fromProperties(properties));
          }
        }
        return jobs;
      });
    }
  }

  /**
   * Whether a property map describes a job rather than the remains of one being deleted. These three
   * are set unconditionally by {@link #prepare}; {@code artifactEtag} and {@code previousVersionId}
   * are nullable by design, and {@code properties} omits a key whose value is null.
   */
  private static boolean identifiesAJob(Map<String, Object> properties) {
    return properties.get("jobId") != null
        && properties.get("resourceId") != null
        && properties.get("resourceType") != null;
  }

  public void markArtifactDeleted(String jobId) {
    update(jobId, "SET e.artifactDeleted = true, e.nextAttemptAt = timestamp() + $delay", initialDelayMillis);
  }

  public void markGraphDeleted(String jobId) {
    update(jobId, "SET e.graphDeleted = true, e.nextAttemptAt = timestamp() + $delay", initialDelayMillis);
  }

  /**
   * Defers a job to its next attempt and reports how many attempts it has now had. A caller that
   * watches that number stop rising towards a limit is watching a job that repetition will not
   * finish, which is the difference between a dependency that is briefly away and one that has
   * given a considered answer.
   */
  public long defer(String jobId) {
    String query = "MATCH (e:" + LABEL + " {jobId: $jobId}) "
        + "SET e.attempts = coalesce(e.attempts, 0) + 1, e.nextAttemptAt = timestamp() + $delay "
        + "RETURN e.attempts AS attempts";
    try (Session session = driver.session()) {
      return session.writeTransaction(tx -> {
        Result result = tx.run(query, Map.of("jobId", jobId, "delay", 5_000));
        return result.hasNext() ? result.next().get("attempts").asLong(0L) : 0L;
      });
    }
  }

  /**
   * Stops retrying a job and records why. The job stays in the outbox rather than being removed,
   * because a deletion that was refused leaves the artifact in place and someone has to be able to
   * find it. {@link #pending} skips a parked job, so the relay stops re-sending a request that has
   * already been answered.
   */
  public void park(String jobId, String reason) {
    try (Session session = driver.session()) {
      session.writeTransaction(tx -> {
        tx.run("MATCH (e:" + LABEL + " {jobId: $jobId}) "
                + "SET e.parked = true, e.parkedReason = $reason, e.parkedAt = timestamp()",
            Map.of("jobId", jobId, "reason", reason)).consume();
        return null;
      });
    }
  }

  /** How many deletions are parked: jobs waiting on a person rather than on another attempt. */
  public long parkedCount() {
    try (Session session = driver.session()) {
      return session.readTransaction(tx -> tx.run(
          "MATCH (e:" + LABEL + ") WHERE e.parked = true RETURN count(e) AS parked")
          .single().get("parked").asLong());
    }
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

  private static ArtifactDeletionJob fromProperties(Map<String, Object> properties) {
    return new ArtifactDeletionJob(
        (String) properties.get("jobId"),
        (String) properties.get("resourceId"),
        CedarResourceType.forValue((String) properties.get("resourceType")),
        (String) properties.get("artifactEtag"),
        (String) properties.get("graphSnapshotJson"),
        (String) properties.get("previousVersionId"),
        Boolean.TRUE.equals(properties.get("artifactDeleted")),
        Boolean.TRUE.equals(properties.get("graphDeleted")));
  }

  private static ArtifactDeletionJob fromRecord(Record record) {
    return new ArtifactDeletionJob(
        record.get("jobId").asString(),
        record.get("resourceId").asString(),
        CedarResourceType.forValue(record.get("resourceType").asString()),
        record.get("artifactEtag").isNull() ? null : record.get("artifactEtag").asString(),
        record.get("graphSnapshotJson").asString(),
        record.get("previousVersionId").isNull() ? null : record.get("previousVersionId").asString(),
        record.get("artifactDeleted").asBoolean(false),
        record.get("graphDeleted").asBoolean(false));
  }

  @Override
  public void close() {
    driver.close();
  }
}
