package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation-only model for the body that updates a category.
 *
 * <p>The path names the category, so the body carries only what an update may change. Moving a
 * category to another parent is a separate command, and the {@code Category} schema this used to
 * name listed both {@code @id} and {@code parentCategoryId}, neither of which the handler reads.</p>
 */
@Schema(name = "CategoryUpdateRequest", description = "The category properties to change.",
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class CategoryUpdateRequest {

  @Schema(name = "schema:name", description = "Name of the category.")
  private String name;

  @Schema(name = "schema:description", description = "Description of the category.")
  private String description;

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

  public String getIdentifier() {
    return identifier;
  }

  public void setIdentifier(String identifier) {
    this.identifier = identifier;
  }
}
