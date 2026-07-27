package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only model for a CEDAR template element.
 *
 * <p>CEDAR template elements are open JSON-LD documents with no fixed Java type on the wire (the
 * resource methods proxy raw JSON), so this thin bean exists purely to reproduce the
 * {@code TemplateElement} schema that the hand-authored spec exposed. It mirrors that schema exactly:
 * a single {@code @id} property. Extend it here if/when the template element schema is documented further.</p>
 */
@Schema(name = "TemplateElement", description = "A CEDAR template element (open JSON-LD document).")
public class TemplateElement {

  @Schema(name = "@id", description = "Unique URL identifier representing a specific template element.")
  private String id;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }
}
