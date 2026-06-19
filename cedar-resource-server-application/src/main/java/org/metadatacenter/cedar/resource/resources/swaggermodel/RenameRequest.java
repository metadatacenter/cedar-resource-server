package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for the parameters of a rename operation.
 *
 * <p>The rename command body is read as raw JSON with no fixed Java type on the wire, so this thin
 * bean exists purely to reproduce the {@code RenameRequest} schema that the hand-authored spec
 * exposed. It mirrors that schema exactly. Properties whose JSON names are not legal Java
 * identifiers (e.g. {@code @id}, {@code schema:name}) are mapped via the {@code name} attribute of
 * {@link ApiModelProperty}.</p>
 */
@ApiModel(value = "RenameRequest", description = "Parameters of the rename operation.")
public class RenameRequest {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier representing the resource.")
  private String id;

  @ApiModelProperty(name = "schema:name", value = "New name of the resource.")
  private String name;

  @ApiModelProperty(name = "schema:description", value = "New description of the resource.")
  private String description;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
