package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;
import org.metadatacenter.util.artifact.SchemaArtifactDocument;

/** Documentation model for the body of the publish-create-draft-template command. */
@Schema(name = "PublishCreateDraftTemplateRequest",
    description = "The template definition to apply to the new draft, and how to create the draft.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class PublishCreateDraftTemplateRequest {

  @Schema(description = "The template definition the new draft takes.", requiredMode = Schema.RequiredMode.REQUIRED,
      implementation = SchemaArtifactDocument.class)
  private Object template;

  @Schema(description = "Name of a new folder to copy the source template's instances into, as instances of the "
      + "draft. Optional. Without it, no instance is copied.")
  private String newFolderName;

  @Schema(description = "The full identifier the new draft is to have, chosen by the caller so that it can read "
      + "the outcome by it if the answer is lost. Optional. Example: "
      + "https://repo.metadatacenter.org/templates/8bc64ab5-df6b-48c8-8c61-6c016245918e. Without it, the server "
      + "chooses one.")
  private String draftId;

  public Object getTemplate() {
    return template;
  }

  public void setTemplate(Object template) {
    this.template = template;
  }

  public String getNewFolderName() {
    return newFolderName;
  }

  public void setNewFolderName(String newFolderName) {
    this.newFolderName = newFolderName;
  }

  public String getDraftId() {
    return draftId;
  }

  public void setDraftId(String draftId) {
    this.draftId = draftId;
  }
}
