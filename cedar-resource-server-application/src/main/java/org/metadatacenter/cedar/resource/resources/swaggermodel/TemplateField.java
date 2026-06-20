package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for a CEDAR template field.
 *
 * <p>CEDAR template fields are open JSON-LD documents with no fixed Java type on the wire (the
 * resource methods proxy raw JSON), so this thin bean exists purely to reproduce the
 * {@code TemplateField} schema that the hand-authored spec exposed. It mirrors that schema exactly:
 * a single {@code @id} property. Extend it here if/when the template field schema is documented further.</p>
 */
@ApiModel(value = "TemplateField", description = "A CEDAR template field (open JSON-LD document).")
public class TemplateField {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier representing a specific template field.")
  private String id;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }
}
