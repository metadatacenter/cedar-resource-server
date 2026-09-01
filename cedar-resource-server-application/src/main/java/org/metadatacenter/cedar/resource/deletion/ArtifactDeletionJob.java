package org.metadatacenter.cedar.resource.deletion;

import org.metadatacenter.model.CedarResourceType;

/** Durable state needed to finish a deletion after the initiating HTTP request has gone away. */
public record ArtifactDeletionJob(String jobId,
                                  String resourceId,
                                  CedarResourceType resourceType,
                                  String artifactEtag,
                                  String graphSnapshotJson,
                                  String previousVersionId,
                                  boolean artifactDeleted,
                                  boolean graphDeleted) {

  ArtifactDeletionJob withArtifactDeleted() {
    return new ArtifactDeletionJob(jobId, resourceId, resourceType, artifactEtag, graphSnapshotJson,
        previousVersionId, true, graphDeleted);
  }

  ArtifactDeletionJob withGraphDeleted() {
    return new ArtifactDeletionJob(jobId, resourceId, resourceType, artifactEtag, graphSnapshotJson,
        previousVersionId, artifactDeleted, true);
  }
}
