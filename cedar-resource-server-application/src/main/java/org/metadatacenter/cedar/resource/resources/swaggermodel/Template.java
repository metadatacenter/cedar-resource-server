package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for a CEDAR template.
 *
 * <p>CEDAR templates are open JSON-LD documents with no fixed Java type on the wire (the
 * resource methods proxy raw JSON), so this thin bean exists purely to reproduce the
 * {@code Template} schema that the hand-authored spec exposed. It mirrors that schema exactly:
 * a single {@code @id} property. Extend it here if/when the template schema is documented further.</p>
 */
@ApiModel(value = "Template", description = "A CEDAR template (open JSON-LD document).")
public class Template {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier representing a specific template.")
  private String id;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }
}
