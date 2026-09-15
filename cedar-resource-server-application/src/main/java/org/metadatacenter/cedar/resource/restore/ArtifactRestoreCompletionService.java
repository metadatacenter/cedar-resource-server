package org.metadatacenter.cedar.resource.restore;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.ArtifactServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.metadatacenter.constant.CedarQueryParameters.QP_VERBATIM;

/**
 * Puts an artifact back when the graph update it belonged to did not commit.
 *
 * <p>Two stores describe every artifact, and an update writes them one after the other. When the
 * second write fails the first has to be undone, and that undoing was done in the request, once,
 * with no retry: its own failure was the one that left the two stores disagreeing about an artifact,
 * with nothing recording which one. This service is that same compensation made durable, so it
 * survives the artifact server being briefly away and the resource server being restarted.
 *
 * <p>Every restore carries {@code If-Match} on the ETag of the replacement it is undoing, so
 * repeating one is safe. A restore that already succeeded, or a document another writer has since
 * changed, answers 412, and this stops rather than overwriting content newer than what it holds.
 */
public final class ArtifactRestoreCompletionService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ArtifactRestoreCompletionService.class);
  private static final int BATCH_SIZE = 25;

  /**
   * How many times a restore is tried before it is parked. The relay runs every five seconds, so
   * this is a few minutes of trying -- long enough to sit out a restart of the artifact server, and
   * short enough that a restore which will never succeed stops asking and starts being visible.
   */
  private static final long MAX_ATTEMPTS = 60;

  private final CedarConfig cedarConfig;
  private final UserService userService;
  private final Neo4jArtifactRestoreOutbox outbox;
  private ScheduledExecutorService executor;

  public ArtifactRestoreCompletionService(CedarConfig cedarConfig, UserService userService) {
    this(cedarConfig, userService, new Neo4jArtifactRestoreOutbox(cedarConfig));
  }

  ArtifactRestoreCompletionService(CedarConfig cedarConfig, UserService userService,
                                   Neo4jArtifactRestoreOutbox outbox) {
    this.cedarConfig = cedarConfig;
    this.userService = userService;
    this.outbox = outbox;
  }

  /**
   * Records, before the graph update is attempted, what putting this artifact back would take.
   *
   * <p>Called on the write path, so it must not throw: an artifact that has already been replaced
   * cannot be un-replaced by refusing to record how to undo it, and failing the request here would
   * report a write that did happen as one that did not. A failure to record leaves the compensation
   * where it was before any of this -- in the request alone -- and says so.
   */
  public ArtifactRestoreJob prepare(CedarArtifactId id,
                                    CedarResourceType resourceType,
                                    String preImage,
                                    String replacementEtag,
                                    boolean verbatim) {
    if (preImage == null || replacementEtag == null || replacementEtag.isBlank()) {
      return null;
    }
    try {
      return outbox.prepare(id.getId(), resourceType, preImage, replacementEtag, verbatim);
    } catch (Exception e) {
      log.error("The compensating write for {} could not be recorded durably; it is best effort for"
          + " this request only", id, e);
      return null;
    }
  }

  /** The graph update committed, so there is nothing to undo. */
  public void abandon(ArtifactRestoreJob job) {
    if (job == null) {
      return;
    }
    try {
      outbox.remove(job.jobId());
    } catch (Exception e) {
      // The relay's first attempt will find the artifact and the graph already agreeing: the
      // restore's If-Match no longer matches, which answers 412 and removes the job.
      log.warn("The completed update for {} left its compensation record in place", job.resourceId(), e);
    }
  }

  /** The restore succeeded in the request, so the relay has nothing left to do. */
  public void completed(ArtifactRestoreJob job) {
    abandon(job);
  }

  /** The restore did not succeed in the request. It stays recorded, and the relay carries it. */
  public void deferred(ArtifactRestoreJob job) {
    if (job == null) {
      return;
    }
    try {
      outbox.defer(job.jobId());
    } catch (Exception e) {
      log.warn("The pending compensation for {} could not be deferred", job.resourceId(), e);
    }
  }

  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "artifact-restore-outbox-relay");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(this::resumeSafely, 5, 5, TimeUnit.SECONDS);
  }

  private void resumeSafely() {
    try {
      CedarRequestContext admin = CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
      for (ArtifactRestoreJob job : outbox.pending(BATCH_SIZE)) {
        attempt(job, admin);
      }
    } catch (Exception e) {
      log.error("The durable artifact-restore outbox could not be processed", e);
    }
  }

  private void attempt(ArtifactRestoreJob job, CedarRequestContext admin) {
    try {
      int status = restore(job, admin);
      if (status == HttpStatus.SC_OK || status == HttpStatus.SC_CREATED) {
        log.info("Restored {} after a graph update that did not commit", job.resourceId());
        outbox.remove(job.jobId());
        return;
      }
      if (status == HttpStatus.SC_PRECONDITION_FAILED) {
        // Either this restore already succeeded and the ETag moved with it, or another writer has
        // replaced the document since. Both mean the stored content is newer than what this job
        // holds, and overwriting it would be the worse outcome.
        log.info("Abandoning the restore of {}: the document has moved beyond {}",
            job.resourceId(), job.conditionEtag());
        outbox.remove(job.jobId());
        return;
      }
      if (status == HttpStatus.SC_NOT_FOUND) {
        log.info("Abandoning the restore of {}: the artifact no longer exists", job.resourceId());
        outbox.remove(job.jobId());
        return;
      }
      if (status >= 400 && status < 500) {
        // The artifact server refused this restore on its merits. The identical request cannot get
        // a different answer, so stop asking every five seconds and make it findable instead.
        log.error("Parking the restore of {}: the artifact server refused it with {}",
            job.resourceId(), status);
        outbox.park(job.jobId(), "Artifact server refused the restore with " + status);
        return;
      }
      throw new IllegalStateException("Artifact server returned " + status);
    } catch (Exception e) {
      long attempts = outbox.defer(job.jobId());
      if (attempts >= MAX_ATTEMPTS) {
        log.error("Parking the restore of {} after {} attempts; the artifact and the graph still"
            + " disagree and it needs attention rather than another attempt", job.resourceId(), attempts, e);
        outbox.park(job.jobId(), "No progress after " + attempts + " attempts: " + e);
      } else {
        // A retry still within its budget is the mechanism working, not a fault. Logging it as an
        // error would leave this service with an error count that never returns to zero.
        log.warn("The restore of {} remains pending and will be retried (attempt {})",
            job.resourceId(), attempts, e);
      }
    }
  }

  private int restore(ArtifactRestoreJob job, CedarRequestContext admin) throws Exception {
    CedarArtifactId id = CedarArtifactId.build(job.resourceId(), job.resourceType());
    String url = cedarConfig.getMicroserviceUrlUtil().getArtifact()
        .getArtifactTypeWithId(job.resourceType(), id);
    if (job.verbatim()) {
      url += (url.contains("?") ? "&" : "?") + QP_VERBATIM + "=true";
    }
    try (ClassicHttpResponse response = new ArtifactServiceClient(cedarConfig)
        .put(url, admin, job.preImage(), job.conditionEtag())) {
      int status = response.getCode();
      EntityUtils.consume(response.getEntity());
      return status;
    }
  }

  /** How many artifacts are known to be ahead of the graph and waiting to be put back. */
  public long getPendingCount() {
    return outbox.count();
  }

  /** How many have stopped being retried and need a person. */
  public long getParkedCount() {
    return outbox.parkedCount();
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
