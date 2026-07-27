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
@Schema(name = "CreateDraftArtifactRequest", description = "Info about the creation process.")
public class CreateDraftArtifactRequest {

  @Schema(name = "@id", description = "Unique URL identifier representing the source artifact.")
  private String id;

  @Schema(name = "newVersion", description = "Version string. Must have three positive decimal numbers, separated by period.")
  private String newVersion;

  @Schema(name = "folderId", description = "Unique URL identifier representing the target folder that the new artifact will be created in.")
  private String folderId;

  @Schema(name = "propagateSharing", description = "Copy sharing settings, or leave the new artifact unshared")
  private Boolean propagateSharing;

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
}
