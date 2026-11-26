package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation model for an ownership-transfer command body. */
@Schema(name = "TransferOwnershipRequest",
    description = "The resource or category and user involved in an ownership transfer.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class TransferOwnershipRequest {


  @Schema(description = "Identifier of the user who will become the owner.", requiredMode = Schema.RequiredMode.REQUIRED)
  private String newOwnerId;


  public String getNewOwnerId() {
    return newOwnerId;
  }

  public void setNewOwnerId(String newOwnerId) {
    this.newOwnerId = newOwnerId;
  }
}
