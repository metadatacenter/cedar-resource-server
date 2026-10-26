package org.metadatacenter.cedar.resource.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import org.metadatacenter.util.http.CedarError;

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
  private final JsonNode artifactServerAnswer;

  private InclusionSubgraphUpdateOutcome(String sourceId, String targetId, Status status, Integer artifactServerStatus,
                                         JsonNode artifactServerAnswer) {
    this.sourceId = sourceId;
    this.targetId = targetId;
    this.status = status;
    this.artifactServerStatus = artifactServerStatus;
    this.artifactServerAnswer = artifactServerAnswer;
  }

  public static InclusionSubgraphUpdateOutcome updated(String sourceId, String targetId, int artifactServerStatus) {
    return new InclusionSubgraphUpdateOutcome(sourceId, targetId, Status.UPDATED, artifactServerStatus, null);
  }

  public static InclusionSubgraphUpdateOutcome unchanged(String sourceId, String targetId) {
    return new InclusionSubgraphUpdateOutcome(sourceId, targetId, Status.UNCHANGED, null, null);
  }

  /**
   * A write the artifact server refused, with its answer as it gave it, so that the caller learns why. A
   * template that instances now reference is refused with a 412 whose answer names that reason, and only
   * the answer tells it apart from a write that lost a race.
   */
  public static InclusionSubgraphUpdateOutcome failed(String sourceId, String targetId, int artifactServerStatus,
                                                      JsonNode artifactServerAnswer) {
    return new InclusionSubgraphUpdateOutcome(sourceId, targetId, Status.FAILED, artifactServerStatus,
        artifactServerAnswer);
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

  /** The artifact server's answer to a refused write, where it gave one as JSON, and null otherwise. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(implementation = CedarError.class, nullable = true,
      description = "The artifact server's answer to a refused write, as it gave it")
  public JsonNode getArtifactServerAnswer() {
    return artifactServerAnswer;
  }
}
