package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for a CEDAR category.
 *
 * <p>CEDAR category create/update bodies are read as raw JSON with no fixed Java type on the wire,
 * so this thin bean exists purely to reproduce the {@code Category} schema that the hand-authored
 * spec exposed. It mirrors that schema exactly. Properties whose JSON names are not legal Java
 * identifiers (e.g. {@code @id}, {@code schema:name}) are mapped via the {@code name} attribute of
 * {@link ApiModelProperty}.</p>
 */
@ApiModel(value = "Category", description = "A CEDAR category.")
public class Category {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier representing a specific category.")
  private String id;

  @ApiModelProperty(name = "schema:name", value = "Name of the category.")
  private String name;

  @ApiModelProperty(name = "schema:description", value = "Description of the category.")
  private String description;

  @ApiModelProperty(name = "parentCategoryId", value = "Unique URL identifier representing a specific parent category.")
  private String parentCategoryId;

  @ApiModelProperty(name = "schema:identifier",
      value = "Identifier, used for identifying this object in outside-to-CEDAR systems.")
  private String identifier;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

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
