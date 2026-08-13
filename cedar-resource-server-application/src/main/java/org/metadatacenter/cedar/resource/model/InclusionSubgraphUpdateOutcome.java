package org.metadatacenter.cedar.resource.model;

/**
 * What happened to one target of an inclusion subgraph propagation.
 *
 * <p>Propagation writes many artifacts, and the artifact server answers each write separately. Reporting
 * one status for the whole request would hide a target that was rejected among the ones that were not, so
 * every target reports its own.
 */
public class InclusionSubgraphUpdateOutcome {

  public enum Status {
    /** The embedded copy of the source was replaced and the artifact server accepted the write. */
    UPDATED,
    /** The target did not contain the source, so there was nothing to replace and nothing was written. */
    UNCHANGED,
    /** The artifact server refused the write. The artifact is unchanged. */
    FAILED
  }

  private final String sourceId;
  private final String targetId;
  private final Status status;
  private final Integer artifactServerStatus;

  private InclusionSubgraphUpdateOutcome(String sourceId, String targetId, Status status, Integer artifactServerStatus) {
    this.sourceId = sourceId;
    this.targetId = targetId;
    this.status = status;
    this.artifactServerStatus = artifactServerStatus;
  }

  public static InclusionSubgraphUpdateOutcome updated(String sourceId, String targetId, int artifactServerStatus) {
    return new InclusionSubgraphUpdateOutcome(sourceId, targetId, Status.UPDATED, artifactServerStatus);
  }

  public static InclusionSubgraphUpdateOutcome unchanged(String sourceId, String targetId) {
    return new InclusionSubgraphUpdateOutcome(sourceId, targetId, Status.UNCHANGED, null);
  }

  public static InclusionSubgraphUpdateOutcome failed(String sourceId, String targetId, int artifactServerStatus) {
    return new InclusionSubgraphUpdateOutcome(sourceId, targetId, Status.FAILED, artifactServerStatus);
  }

  public String getSourceId() {
    return sourceId;
  }

  public String getTargetId() {
    return targetId;
  }

  public Status getStatus() {
    return status;
  }

  /** The status the artifact server answered, or null where no write was attempted. */
  public Integer getArtifactServerStatus() {
    return artifactServerStatus;
  }
}
