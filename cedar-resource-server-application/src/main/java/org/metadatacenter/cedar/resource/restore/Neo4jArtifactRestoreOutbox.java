package org.metadatacenter.cedar.resource.restore;

import org.metadatacenter.server.neo4j.ArtifactRestoreTransaction;
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
 * A durable record that one artifact is ahead of the graph and has to be put back.
 *
 * <p>The record is written before the graph update is attempted, so the compensation survives the
 * process: the window it closes is an artifact written, a graph update not yet committed, and a
 * server that stops in between. Nothing in the request can close that window, because the thing
 * that would do the compensating is what died.
 *
 * <p>It is kept separate from the artifact's own graph node for the same reason the deletion outbox
 * is: a failed graph update is exactly the case where that node cannot be relied on to carry
 * anything.
 */
public final class Neo4jArtifactRestoreOutbox implements AutoCloseable {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Neo4jArtifactRestoreOutbox.class);
  private static final String LABEL = "CedarArtifactRestoreOutbox";
  private static final String JOB_PROJECTION = "e.jobId AS jobId, e.resourceId AS resourceId, "
      + "e.resourceType AS resourceType, e.preImage AS preImage, "
      + "e.conditionEtag AS conditionEtag, e.verbatim AS verbatim";

  private final Driver driver;
  private final long initialDelayMillis;

  public Neo4jArtifactRestoreOutbox(CedarConfig cedarConfig) {
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

  Neo4jArtifactRestoreOutbox(Driver driver) {
    this(driver, 30_000);
  }

  Neo4jArtifactRestoreOutbox(Driver driver, long initialDelayMillis) {
    this.driver = driver;
    this.initialDelayMillis = initialDelayMillis;
    ensureConstraint();
  }

  private void ensureConstraint() {
    try (Session session = driver.session()) {
      session.run("CREATE CONSTRAINT cedar_artifact_restore_resource IF NOT EXISTS "
          + "FOR (e:" + LABEL + ") REQUIRE e.resourceId IS UNIQUE").consume();
      session.run("MATCH (e:" + LABEL + ") WHERE e.protocolVersion IS NULL "
          + "SET e.parked = true, e.parkedReason = 'Legacy job: graph completion is unknown'").consume();
    }
  }

  /**
   * Records that this artifact may need putting back, and returns the job that would do it.
   *
   * <p>One job per artifact. Where one is already outstanding, the stored pre-image stays as it is
   * and only the condition advances: see {@link ArtifactRestoreJob} for why those two go different
   * ways.
   *
   * <p>The first attempt is deferred, because the request that recorded this job is about to try
   * the same thing itself and will remove the job if it succeeds. The relay is what happens when
   * that request never got the chance.
   */
  public ArtifactRestoreJob prepare(String resourceId,
                                    CedarResourceType resourceType,
                                    String preImage,
                                    String conditionEtag,
                                    boolean verbatim) {
    String query = "MERGE (e:" + LABEL + " {resourceId: $resourceId}) "
        + "ON CREATE SET e.jobId = $jobId, e.resourceType = $resourceType, e.preImage = $preImage, "
        + "e.conditionEtag = $conditionEtag, e.verbatim = $verbatim, e.createdAt = timestamp(), "
        + "e.protocolVersion = 2, e.nextAttemptAt = timestamp() + $initialDelayMillis "
        + "ON MATCH SET e.jobId = $jobId, e.restoreStarted = false, e.parked = false, e.attempts = 0, e.conditionEtag = $conditionEtag, e.verbatim = $verbatim, "
        + "e.protocolVersion = 2, e.nextAttemptAt = timestamp() + $initialDelayMillis "
        + "RETURN " + JOB_PROJECTION;
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("jobId", UUID.randomUUID().toString());
    parameters.put("resourceId", resourceId);
    parameters.put("resourceType", resourceType.getValue());
    parameters.put("preImage", preImage);
    parameters.put("conditionEtag", conditionEtag);
    parameters.put("verbatim", verbatim);
    parameters.put("initialDelayMillis", initialDelayMillis);
    try (Session session = driver.session()) {
      return session.writeTransaction(tx -> {
        var existing = tx.run("MATCH (e:" + LABEL + " {resourceId: $resourceId}) "
            + "SET e.lockVersion = coalesce(e.lockVersion, 0) + 1 RETURN e.protocolVersion AS version",
            Map.of("resourceId", resourceId));
        if (existing.hasNext() && existing.single().get("version").asInt(0) != 2) {
          throw new IllegalStateException("A legacy restore needs inspection before preparing another: " + resourceId);
        }
        return fromRecord(tx.run(query, parameters).single());
      });
    }
  }

  /**
   * The jobs due for another attempt.
   *
   * <p>The whole property map is read in one step. Neo4j is read committed rather than snapshot
   * isolated, so a {@link #remove} committing while this query runs is visible to it, and a
   * projection naming each property separately is evaluated one property at a time: a delete
   * landing between two of them returns a row whose earlier fields were read and whose later ones
   * are null. {@code properties(e)} is a single read, so a row is either a whole job or nothing
   * resembling one.
   */
  public List<ArtifactRestoreJob> pending(int limit) {
    String query = "MATCH (e:" + LABEL + ") WHERE coalesce(e.nextAttemptAt, 0) <= timestamp() "
        + "AND e.protocolVersion = 2 AND coalesce(e.parked, false) = false "
        + "WITH e ORDER BY e.createdAt, e.jobId LIMIT $limit "
        + "RETURN properties(e) AS job";
    try (Session session = driver.session()) {
      return session.readTransaction(tx -> {
        List<ArtifactRestoreJob> jobs = new ArrayList<>();
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

  private static boolean identifiesAJob(Map<String, Object> properties) {
    return properties.get("jobId") != null
        && properties.get("resourceId") != null
        && properties.get("resourceType") != null
        && properties.get("preImage") != null
        && properties.get("conditionEtag") != null;
  }

  /**
   * Defers a job to its next attempt and reports how many attempts it has now had. A caller that
   * watches that number rise towards a limit is watching a restore that repetition will not finish.
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
   * Stops retrying a job and records why. The job stays in the outbox, because a restore that could
   * not be made leaves the two stores disagreeing and someone has to be able to find which artifact
   * it was. {@link #pending} skips a parked job.
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

  /** How many restores are parked: artifacts waiting on a person rather than another attempt. */
  public long parkedCount() {
    try (Session session = driver.session()) {
      return session.readTransaction(tx -> tx.run(
              "MATCH (e:" + LABEL + ") WHERE e.parked = true RETURN count(e) AS parked")
          .single().get("parked").asLong());
    }
  }

  /** Hold the same node lock used by graph completion for the entire conditional HTTP restore. */
  public void restoreIfPending(ArtifactRestoreJob job, java.util.function.ToIntFunction<ArtifactRestoreJob> restore) {
    try (Session session = driver.session()) {
      // Persist the decision before HTTP: even if the later transaction rolls back after an
      // uncertain response, the original graph write must never commit over a possible restore.
      boolean started = session.writeTransaction(tx -> {
        if (!ArtifactRestoreTransaction.lock(tx, job.jobId())) {
          return false;
        }
        tx.run("MATCH (e:CedarArtifactRestoreOutbox {jobId: $jobId}) SET e.restoreStarted = true",
            Map.of("jobId", job.jobId())).consume();
        return true;
      });
      if (!started) { return; }
      session.writeTransaction(tx -> {
        if (!ArtifactRestoreTransaction.lock(tx, job.jobId())) {
          return null;
        }
        int status = restore.applyAsInt(job);
        if (status == 200 || status == 201 || status == 404 || status == 412) {
          ArtifactRestoreTransaction.remove(tx, job.jobId());
          log.info("Finished restore job for {} with status {}", job.resourceId(), status);
        } else {
          var result = tx.run("MATCH (e:CedarArtifactRestoreOutbox {jobId: $jobId}) "
              + "SET e.attempts = coalesce(e.attempts, 0) + 1, e.nextAttemptAt = timestamp() + 5000 "
              + "SET e.parked = (e.attempts >= 60 OR ($status >= 400 AND $status < 500)), "
              + "e.parkedReason = $reason RETURN e.parked AS parked, e.attempts AS attempts",
              Map.of("jobId", job.jobId(), "status", status, "reason", "Restore returned " + status)).single();
          if (result.get("parked").asBoolean()) {
            log.error("Parking restore for {} after {} attempts (status {})", job.resourceId(),
                result.get("attempts").asLong(), status);
          }
        }
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

  private static ArtifactRestoreJob fromProperties(Map<String, Object> properties) {
    return new ArtifactRestoreJob(
        (String) properties.get("jobId"),
        (String) properties.get("resourceId"),
        CedarResourceType.forValue((String) properties.get("resourceType")),
        (String) properties.get("preImage"),
        (String) properties.get("conditionEtag"),
        Boolean.TRUE.equals(properties.get("verbatim")));
  }

  private static ArtifactRestoreJob fromRecord(Record record) {
    return new ArtifactRestoreJob(
        record.get("jobId").asString(),
        record.get("resourceId").asString(),
        CedarResourceType.forValue(record.get("resourceType").asString()),
        record.get("preImage").asString(),
        record.get("conditionEtag").asString(),
        record.get("verbatim").asBoolean(false));
  }

  @Override
  public void close() {
    driver.close();
  }
}
