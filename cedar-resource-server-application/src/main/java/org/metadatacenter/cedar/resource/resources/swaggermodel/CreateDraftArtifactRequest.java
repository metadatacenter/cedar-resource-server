package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only model for the info about the draft creation process.
 *
 * <p>The create-draft command body is read as raw JSON with no fixed Java type on the wire, so this
 * thin bean exists purely to reproduce the {@code CreateDraftArtifactRequest} schema that the
 * hand-authored spec exposed. It mirrors that schema exactly. Properties whose JSON names are not
 * legal Java identifiers (e.g. {@code @id}) are mapped via the {@code name} attribute of
 * {@link ApiModelProperty}.</p>
 */
@Schema(name = "CreateDraftArtifactRequest", description = "Info about the creation process.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class CreateDraftArtifactRequest {

  @Schema(name = "@id", description = "Unique URL identifier representing the source artifact.")
  private String id;

  @Schema(name = "newVersion", description = "Version string. Must have three positive decimal numbers, separated by period.")
  private String newVersion;

  @Schema(name = "folderId", description = "Unique URL identifier representing the target folder that the new artifact will be created in.")
  private String folderId;

  @Schema(name = "propagateSharing", description = "Copy sharing settings, or leave the new artifact unshared")
  private Boolean propagateSharing;

  @Schema(name = "draftId", description = "The full identifier the draft is to have, in the source artifact's "
      + "collection. Optional. A caller that may lose the answer chooses it, so that it can read the outcome by "
      + "it rather than draft again: a repeat finds the identifier taken and answers 412. Example: "
      + "https://repo.metadatacenter.org/templates/8bc64ab5-df6b-48c8-8c61-6c016245918e. Without it, the "
      + "server chooses one.")
  private String draftId;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getNewVersion() {
    return newVersion;
  }

  public void setNewVersion(String newVersion) {
    this.newVersion = newVersion;
  }

  public String getFolderId() {
    return folderId;
  }

  public void setFolderId(String folderId) {
    this.folderId = folderId;
  }

  public Boolean getPropagateSharing() {
    return propagateSharing;
  }

  public void setPropagateSharing(Boolean propagateSharing) {
    this.propagateSharing = propagateSharing;
  }

  public String getDraftId() {
    return draftId;
  }

  public void setDraftId(String draftId) {
    this.draftId = draftId;
  }
}
