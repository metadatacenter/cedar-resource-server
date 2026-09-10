package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only model for the body that creates a category.
 *
 * <p>A category create names the parent, the name and the description, and may carry an outside
 * identifier. The server mints the category's own {@code @id}, so the body does not carry one: the
 * {@code Category} schema this used to name lists {@code @id} as well, and the handler refuses it.</p>
 */
@Schema(name = "CategoryCreateRequest", description = "The category to create.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
    requiredProperties = {"schema:name", "schema:description", "parentCategoryId"})
public class CategoryCreateRequest {

  @Schema(name = "schema:name", description = "Name of the category.")
  private String name;

  @Schema(name = "schema:description", description = "Description of the category.")
  private String description;

  @Schema(name = "parentCategoryId", description = "Unique URL identifier representing a specific parent category.")
  private String parentCategoryId;

  @Schema(name = "schema:identifier", description = "Identifier, used for identifying this object in outside-to-CEDAR systems.")
  private String identifier;

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

  public String getParentCategoryId() {
    return parentCategoryId;
  }

  public void setParentCategoryId(String parentCategoryId) {
    this.parentCategoryId = parentCategoryId;
  }

  public String getIdentifier() {
    return identifier;
  }

  public void setIdentifier(String identifier) {
    this.identifier = identifier;
  }
}
