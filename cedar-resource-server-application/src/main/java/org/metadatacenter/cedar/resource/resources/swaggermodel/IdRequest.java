package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model carrying the id of an artifact.
 *
 * <p>The open-view command body is read as raw JSON with no fixed Java type on the wire, so this
 * thin bean exists purely to reproduce the {@code IdRequest} schema that the hand-authored spec
 * exposed. It mirrors that schema exactly. Properties whose JSON names are not legal Java
 * identifiers (e.g. {@code @id}) are mapped via the {@code name} attribute of
 * {@link ApiModelProperty}.</p>
 */
@ApiModel(value = "IdRequest", description = "Id of the artifact.")
public class IdRequest {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier representing an artifact.")
  private String id;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }
}
