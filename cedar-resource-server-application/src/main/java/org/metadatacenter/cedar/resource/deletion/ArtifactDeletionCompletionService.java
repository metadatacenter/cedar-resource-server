package org.metadatacenter.cedar.resource.deletion;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.artifact.ArtifactServerRefusal;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarSchemaArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.ArtifactServiceClient;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Completes durable artifact-deletion jobs synchronously and after process restart. */
public final class ArtifactDeletionCompletionService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ArtifactDeletionCompletionService.class);
  private static final int BATCH_SIZE = 25;
  /**
   * How many times a deletion is retried before it is parked. The relay runs every five seconds, so
   * this is a few minutes of trying -- long enough to sit out a restart of the artifact server, and
   * short enough that a job which will never finish stops asking.
   */
  private static final long MAX_ATTEMPTS = 60;
  private final CedarConfig cedarConfig;
  private final UserService userService;
  private final NodeIndexingService nodeIndexingService;
  private final Neo4jArtifactDeletionOutbox outbox;
  private ScheduledExecutorService executor;

  public ArtifactDeletionCompletionService(CedarConfig cedarConfig,
                                           UserService userService,
                                           NodeIndexingService nodeIndexingService) {
    this(cedarConfig, userService, nodeIndexingService, new Neo4jArtifactDeletionOutbox(cedarConfig));
  }

  ArtifactDeletionCompletionService(CedarConfig cedarConfig,
                                    UserService userService,
                                    NodeIndexingService nodeIndexingService,
                                    Neo4jArtifactDeletionOutbox outbox) {
    this.cedarConfig = cedarConfig;
    this.userService = userService;
    this.nodeIndexingService = nodeIndexingService;
    this.outbox = outbox;
  }

  public ArtifactDeletionJob prepare(CedarArtifactId id,
                                     FolderServerArtifact artifact,
                                     String artifactEtag,
                                     String previousVersionId,
                                     boolean artifactDeleted) throws CedarProcessingException {
    try {
      return outbox.prepare(id.getId(), artifact.getType(), artifactEtag,
          JsonMapper.STRICT_MAPPER.writeValueAsString(artifact), previousVersionId, artifactDeleted);
    } catch (Exception e) {
      throw new CedarProcessingException("The artifact deletion could not be recorded durably", e);
    }
  }

  public void markArtifactDeleted(ArtifactDeletionJob job) {
    outbox.markArtifactDeleted(job.jobId());
  }

  public void abandon(ArtifactDeletionJob job) {
    outbox.remove(job.jobId());
  }

  public void completeAfterArtifactDeletion(ArtifactDeletionJob prepared,
                                            CedarRequestContext context) throws CedarProcessingException {
    ArtifactDeletionJob job = prepared.artifactDeleted() ? prepared : prepared.withArtifactDeleted();
    try {
      complete(job, context);
    } catch (Exception e) {
      outbox.defer(job.jobId());
      if (e instanceof CedarProcessingException processingException) {
        throw processingException;
      }
      throw new CedarProcessingException("The durable artifact deletion is pending completion", e);
    }
  }

  /**
   * Finishes the graph and the projections of a deletion whose artifact is gone. A failure is left to the
   * caller, which counts it as one attempt.
   */
  private void complete(ArtifactDeletionJob job, CedarRequestContext context) throws Exception {
    if (!job.graphDeleted()) {
      finishGraph(job, context);
      outbox.markGraphDeleted(job.jobId());
      job = job.withGraphDeleted();
    }
    finishProjections(job, context);
    outbox.remove(job.jobId());
  }

  private void finishGraph(ArtifactDeletionJob job, CedarRequestContext context) {
    FolderServiceSession folders = CedarDataServices.getInstance().getFolderServiceSession(context);
    CedarArtifactId id = CedarArtifactId.build(job.resourceId(), job.resourceType());
    if (folders.findArtifactById(id) != null && !folders.deleteResourceById(id)) {
      throw new IllegalStateException("The artifact graph node could not be deleted: " + id);
    }

  }

  private void finishProjections(ArtifactDeletionJob job, CedarRequestContext context) throws Exception {
    CedarArtifactId id = CedarArtifactId.build(job.resourceId(), job.resourceType());
    nodeIndexingService.removeDocumentFromIndex(id);
  }

  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "artifact-deletion-outbox-relay");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(this::resumeSafely, 5, 5, TimeUnit.SECONDS);
  }

  private void resumeSafely() {
    try {
      CedarRequestContext admin = CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
      for (ArtifactDeletionJob original : outbox.pending(BATCH_SIZE)) {
        ArtifactDeletionJob job = original;
        try {
          if (!job.artifactDeleted()) {
            String url = cedarConfig.getMicroserviceUrlUtil().getArtifact().getArtifactTypeWithId(
                job.resourceType(), CedarArtifactId.build(job.resourceId(), job.resourceType()));
            try (ClassicHttpResponse response = new ArtifactServiceClient(cedarConfig).delete(url, admin, job.artifactEtag())) {
              int status = response.getCode();
              String body = response.getEntity() == null ? null
                  : EntityUtils.toString(response.getEntity(), java.nio.charset.StandardCharsets.UTF_8);
              if (status == HttpStatus.SC_PRECONDITION_FAILED) {
                // Both 412s end the deletion, for different reasons: a later write means the caller asked
                // to delete a revision that is gone, and instances that reference a template keep it.
                if (ArtifactServerRefusal.refusesForInstances(status, body)) {
                  log.warn("Abandoning deletion {}: instances reference the template, and the artifact server "
                      + "refuses to delete it", job.resourceId());
                } else {
                  log.warn("Abandoning deletion {} because the artifact advanced beyond {}",
                      job.resourceId(), job.artifactEtag());
                }
                outbox.remove(job.jobId());
                continue;
              }
              if (status != HttpStatus.SC_NO_CONTENT && status != HttpStatus.SC_NOT_FOUND) {
                if (status >= 400 && status < 500) {
                  // The artifact server refused this deletion on its merits, as it does for a
                  // template that stored instances still reference. Sending the identical request
                  // again cannot change that answer, so stop rather than ask every five seconds
                  // for as long as the process lives.
                  log.error("Abandoning deletion {}: the artifact server refused it with {}",
                      job.resourceId(), status);
                  outbox.park(job.jobId(), "Artifact server refused the deletion with " + status);
                  continue;
                }
                throw new IllegalStateException("Artifact server returned " + status);
              }
            }
            outbox.markArtifactDeleted(job.jobId());
            job = job.withArtifactDeleted();
          }
          // Not completeAfterArtifactDeletion, which defers a failed job itself. The catch below defers it
          // too, and deferring it twice spent two attempts of its budget on every pass.
          complete(job, admin);
        } catch (Exception e) {
          long attempts = outbox.defer(job.jobId());
          if (attempts >= MAX_ATTEMPTS) {
            log.error("Parking deletion {} after {} attempts; it needs attention rather than another"
                + " attempt", job.resourceId(), attempts, e);
            outbox.park(job.jobId(), "No progress after " + attempts + " attempts: " + e);
          } else {
            // A retry that is still within its budget is the mechanism working, not a fault, and
            // logging it as an error leaves this service with an error count that never returns
            // to zero.
            log.warn("Artifact deletion {} remains pending and will be retried (attempt {})",
                job.resourceId(), attempts, e);
          }
        }
      }
    } catch (Exception e) {
      log.error("The durable artifact-deletion outbox could not be processed", e);
    }
  }

  public long getPendingCount() {
    return outbox.count();
  }

  @Override
  public synchronized void close() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
    outbox.close();
  }
}
