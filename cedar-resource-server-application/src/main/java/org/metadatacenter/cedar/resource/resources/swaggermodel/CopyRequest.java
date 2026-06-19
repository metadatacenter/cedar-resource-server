package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for the parameters of a copy operation.
 *
 * <p>The copy command body is read as raw JSON with no fixed Java type on the wire, so this thin
 * bean exists purely to reproduce the {@code CopyRequest} schema that the hand-authored spec
 * exposed. It mirrors that schema exactly. Properties whose JSON names are not legal Java
 * identifiers (e.g. {@code @id}) are mapped via the {@code name} attribute of
 * {@link ApiModelProperty}.</p>
 */
@ApiModel(value = "CopyRequest", description = "Parameters of the copy operation.")
public class CopyRequest {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier representing the source artifact.")
  private String id;

  @ApiModelProperty(name = "targetFolderId",
      value = "Unique URL identifier representing the target folder that the new artifact will be created in.")
  private String targetFolderId;

  @ApiModelProperty(name = "nameTemplate",
      value = "Template that will be used to name the new artifact. The variables in the template will be "
          + "interpolated. Currently only one variable is supported, the name of the source artifact as "
          + "'{{name}}'. If no interpolatioin is needed, the new name should be passed.")
  private String nameTemplate;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTargetFolderId() {
    return targetFolderId;
  }

  public void setTargetFolderId(String targetFolderId) {
    this.targetFolderId = targetFolderId;
  }

  public String getNameTemplate() {
    return nameTemplate;
  }

  public void setNameTemplate(String nameTemplate) {
    this.nameTemplate = nameTemplate;
  }
}
