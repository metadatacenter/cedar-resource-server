package org.metadatacenter.cedar.resource.deletion;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarSchemaArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.resource.ArtifactCopyOperations;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.server.valuerecommender.model.ValuerecommenderReindexMessageActionType;
import org.metadatacenter.util.http.ProxyUtil;
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
  private final CedarConfig cedarConfig;
  private final UserService userService;
  private final NodeIndexingService nodeIndexingService;
  private final ValuerecommenderReindexQueueService valuerecommenderQueueService;
  private final Neo4jArtifactDeletionOutbox outbox;
  private ScheduledExecutorService executor;

  public ArtifactDeletionCompletionService(CedarConfig cedarConfig,
                                           UserService userService,
                                           NodeIndexingService nodeIndexingService,
                                           ValuerecommenderReindexQueueService valuerecommenderQueueService) {
    this(cedarConfig, userService, nodeIndexingService, valuerecommenderQueueService,
        new Neo4jArtifactDeletionOutbox(cedarConfig));
  }

  ArtifactDeletionCompletionService(CedarConfig cedarConfig,
                                    UserService userService,
                                    NodeIndexingService nodeIndexingService,
                                    ValuerecommenderReindexQueueService valuerecommenderQueueService,
                                    Neo4jArtifactDeletionOutbox outbox) {
    this.cedarConfig = cedarConfig;
    this.userService = userService;
    this.nodeIndexingService = nodeIndexingService;
    this.valuerecommenderQueueService = valuerecommenderQueueService;
    this.outbox = outbox;
  }

  public ArtifactDeletionJob prepare(CedarArtifactId id,
                                     FolderServerArtifact artifact,
                                     String artifactEtag,
                                     String previousVersionId,
                                     boolean artifactDeleted) throws CedarProcessingException {
    try {
      return outbox.prepare(id.getId(), artifact.getType(), artifactEtag,
          JsonMapper.MAPPER.writeValueAsString(artifact), previousVersionId, artifactDeleted);
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
      if (!job.graphDeleted()) {
        finishGraph(job, context);
        outbox.markGraphDeleted(job.jobId());
        job = job.withGraphDeleted();
      }
      finishProjections(job, context);
      outbox.remove(job.jobId());
    } catch (Exception e) {
      outbox.defer(job.jobId());
      if (e instanceof CedarProcessingException processingException) {
        throw processingException;
      }
      throw new CedarProcessingException("The durable artifact deletion is pending completion", e);
    }
  }

  private void finishGraph(ArtifactDeletionJob job, CedarRequestContext context) {
    FolderServiceSession folders = CedarDataServices.getInstance().getFolderServiceSession(context);
    CedarArtifactId id = CedarArtifactId.build(job.resourceId(), job.resourceType());
    if (folders.findArtifactById(id) != null && !folders.deleteResourceById(id)) {
      throw new IllegalStateException("The artifact graph node could not be deleted: " + id);
    }
    if (job.previousVersionId() != null) {
      CedarSchemaArtifactId previous = CedarSchemaArtifactId.build(job.previousVersionId(), job.resourceType());
      folders.setLatestVersion(previous);
      folders.setLatestPublishedVersion(previous);
    }
  }

  private void finishProjections(ArtifactDeletionJob job, CedarRequestContext context) throws Exception {
    CedarArtifactId id = CedarArtifactId.build(job.resourceId(), job.resourceType());
    FolderServerArtifact deleted = JsonMapper.MAPPER.readValue(job.graphSnapshotJson(), FolderServerArtifact.class);
    nodeIndexingService.removeDocumentFromIndex(id);
    if (!ArtifactCopyOperations.enqueueValuerecommenderUpdateWithResult(valuerecommenderQueueService, deleted,
        ValuerecommenderReindexMessageActionType.DELETED)) {
      throw new IllegalStateException("The value-recommender deletion event could not be persisted");
    }
    if (job.previousVersionId() != null) {
      CedarSchemaArtifactId previousId = CedarSchemaArtifactId.build(job.previousVersionId(), job.resourceType());
      FolderServerArtifact previous = CedarDataServices.getInstance().getFolderServiceSession(context)
          .findArtifactById(previousId);
      if (previous != null) {
        nodeIndexingService.indexDocument(previous, context);
      }
    }
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
            try (ClassicHttpResponse response = ProxyUtil.proxyDelete(url, admin, job.artifactEtag())) {
              int status = response.getCode();
              EntityUtils.consume(response.getEntity());
              if (status == HttpStatus.SC_PRECONDITION_FAILED) {
                log.warn("Abandoning deletion {} because the artifact advanced beyond {}",
                    job.resourceId(), job.artifactEtag());
                outbox.remove(job.jobId());
                continue;
              }
              if (status != HttpStatus.SC_NO_CONTENT && status != HttpStatus.SC_NOT_FOUND) {
                throw new IllegalStateException("Artifact server returned " + status);
              }
            }
            outbox.markArtifactDeleted(job.jobId());
            job = job.withArtifactDeleted();
          }
          completeAfterArtifactDeletion(job, admin);
        } catch (Exception e) {
          outbox.defer(job.jobId());
          log.error("Artifact deletion {} remains pending and will be retried", job.resourceId(), e);
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
