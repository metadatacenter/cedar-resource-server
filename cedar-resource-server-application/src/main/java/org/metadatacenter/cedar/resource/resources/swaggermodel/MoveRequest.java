package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for the parameters of a move operation.
 *
 * <p>The move command body is read as raw JSON with no fixed Java type on the wire, so this thin
 * bean exists purely to reproduce the {@code MoveRequest} schema that the hand-authored spec
 * exposed. It mirrors that schema exactly. Properties whose JSON names are not legal Java
 * identifiers (e.g. {@code @id}) are mapped via the {@code name} attribute of
 * {@link ApiModelProperty}.</p>
 */
@ApiModel(value = "MoveRequest", description = "Parameters of the move operation.")
public class MoveRequest {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier representing the source resource.")
  private String id;

  @ApiModelProperty(name = "targetFolderId",
      value = "Unique URL identifier representing the target folder where the resource will be moved.")
  private String targetFolderId;

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
}
