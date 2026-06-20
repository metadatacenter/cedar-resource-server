package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model indicating whether to force an operation.
 *
 * <p>The regenerate-index command body is read as raw JSON with no fixed Java type on the wire, so
 * this thin bean exists purely to reproduce the {@code ForceRequest} schema that the hand-authored
 * spec exposed. It mirrors that schema exactly.</p>
 */
@ApiModel(value = "ForceRequest", description = "Force or not.")
public class ForceRequest {

  @ApiModelProperty(name = "force", value = "Force the regeneration, or not")
  private Boolean force;

  public Boolean getForce() {
    return force;
  }

  public void setForce(Boolean force) {
    this.force = force;
  }
}
