package org.metadatacenter.cedar.resource.restore;

import org.apache.hc.core5.http.ClassicHttpResponse;
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

  private void attempt(ArtifactRestoreJob job, CedarRequestContext context) {
    outbox.restoreIfPending(job, pending -> {
      try {
        return restore(pending, context);
      } catch (Exception failure) {
        log.warn("The restore of {} remains pending", pending.resourceId(), failure);
        return 503;
      }
    });
  }

  /** The request and relay use the same durable job and lock; neither can undo a committed graph. */
  public void restoreNow(ArtifactRestoreJob job, CedarRequestContext context) {
    if (job != null) {
      try {
        attempt(job, context);
      } catch (Exception failure) {
        log.warn("The restore of {} remains recorded for the relay", job.resourceId(), failure);
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
