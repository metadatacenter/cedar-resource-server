package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for the info about the publishing process.
 *
 * <p>The publish command body is read as raw JSON with no fixed Java type on the wire, so this thin
 * bean exists purely to reproduce the {@code PublishArtifactRequest} schema that the hand-authored
 * spec exposed. It mirrors that schema exactly. Properties whose JSON names are not legal Java
 * identifiers (e.g. {@code @id}) are mapped via the {@code name} attribute of
 * {@link ApiModelProperty}.</p>
 */
@ApiModel(value = "PublishArtifactRequest", description = "Info about the publishing process.")
public class PublishArtifactRequest {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier of the artifact.")
  private String id;

  @ApiModelProperty(name = "newVersion",
      value = "Version string. Must have three positive decimal numbers, separated by period.")
  private String newVersion;

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
}
