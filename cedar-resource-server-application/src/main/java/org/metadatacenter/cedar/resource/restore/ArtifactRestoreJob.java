package org.metadatacenter.cedar.resource.restore;

import org.metadatacenter.model.CedarResourceType;

/**
 * Durable state needed to put an artifact back after the graph update it belonged to did not
 * commit, once the initiating HTTP request has gone away.
 *
 * <p>{@code conditionEtag} is the ETag the artifact server returned for the replacement, so the
 * restore is conditional on the document still being the one this job was recorded for. It is the
 * newest such ETag rather than the first: a second failed update moves the document on, and the
 * condition has to name where it is now for the restore to apply at all.
 *
 * <p>{@code preImage} is the oldest one, and deliberately not refreshed. It is the content the
 * graph still describes. The content of a later failed update is a state the graph never agreed
 * with, so restoring that would settle the disagreement at the wrong end.
 */
public record ArtifactRestoreJob(String jobId,
                                 String resourceId,
                                 CedarResourceType resourceType,
                                 String preImage,
                                 String conditionEtag,
                                 boolean verbatim) {
}
