package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation model for the body of the DOI annotation command. */
@Schema(name = "SetDoiRequest", description = "The artifact and the DOI to annotate it with.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class SetDoiRequest {

  @Schema(name = "@id", description = "Identifier of the artifact.", requiredMode = Schema.RequiredMode.REQUIRED)
  private String id;

  @Schema(description = "The DOI to record. An artifact that already carries a different DOI is refused.",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private String doi;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getDoi() {
    return doi;
  }

  public void setDoi(String doi) {
    this.doi = doi;
  }
}
