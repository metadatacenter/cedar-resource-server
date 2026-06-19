package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for a CEDAR folder.
 *
 * <p>CEDAR folder resources are proxied as raw JSON with no fixed Java type on the wire, so this
 * thin bean exists purely to reproduce the {@code Folder} schema that the hand-authored spec
 * exposed. It mirrors that schema exactly: a single {@code id} property. Extend it here if/when
 * the folder schema is documented further.</p>
 */
@ApiModel(value = "Folder", description = "A CEDAR folder.")
public class Folder {

  @ApiModelProperty(name = "id", value = "Unique URL identifier representing a specific folder.")
  private String id;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }
}
