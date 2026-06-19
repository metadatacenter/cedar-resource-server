package org.metadatacenter.cedar.resource.resources.swaggermodel;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * Documentation-only model for a CEDAR user.
 *
 * <p>CEDAR user resources are proxied as raw JSON with no fixed Java type on the wire, so this
 * thin bean exists purely to reproduce the {@code User} schema that the hand-authored spec
 * exposed. It mirrors that schema exactly. Properties whose JSON names are not legal Java
 * identifiers (e.g. {@code @id}, {@code schema:name}) are mapped via the {@code name} attribute of
 * {@link ApiModelProperty}.</p>
 */
@ApiModel(value = "User", description = "A CEDAR user.")
public class User {

  @ApiModelProperty(name = "@id", value = "Unique URL identifier representing a specific user.")
  private String id;

  @ApiModelProperty(name = "firstName", value = "First name.")
  private String firstName;

  @ApiModelProperty(name = "lastName", value = "Last name.")
  private String lastName;

  @ApiModelProperty(name = "email", value = "Email.")
  private String email;

  @ApiModelProperty(name = "pav:createdOn", value = "Creation time in xsd:dateTime format.")
  private String createdOn;

  @ApiModelProperty(name = "createdOnTS", value = "Creation time as Unix timestamp.")
  private Number createdOnTS;

  @ApiModelProperty(name = "pav:lastUpdatedOn", value = "Last update time in xsd:dateTime format.")
  private String lastUpdatedOn;

  @ApiModelProperty(name = "lastUpdatedOnTS", value = "Last update time as Unix timestamp.")
  private String lastUpdatedOnTS;

  @ApiModelProperty(name = "sourceHash", value = "Reserved for further use. Currently null.")
  private String sourceHash;

  @ApiModelProperty(name = "schema:identifier", value = "Reserved for further use. Currently null.")
  private String identifier;

  @ApiModelProperty(name = "schema:name", value = "Display name.")
  private String name;

  @ApiModelProperty(name = "schema:description", value = "Reserved for further use. Currently null.")
  private String description;

  @ApiModelProperty(name = "resourceType", value = "The value \"user\"")
  private String resourceType;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getCreatedOn() {
    return createdOn;
  }

  public void setCreatedOn(String createdOn) {
    this.createdOn = createdOn;
  }

  public Number getCreatedOnTS() {
    return createdOnTS;
  }

  public void setCreatedOnTS(Number createdOnTS) {
    this.createdOnTS = createdOnTS;
  }

  public String getLastUpdatedOn() {
    return lastUpdatedOn;
  }

  public void setLastUpdatedOn(String lastUpdatedOn) {
    this.lastUpdatedOn = lastUpdatedOn;
  }

  public String getLastUpdatedOnTS() {
    return lastUpdatedOnTS;
  }

  public void setLastUpdatedOnTS(String lastUpdatedOnTS) {
    this.lastUpdatedOnTS = lastUpdatedOnTS;
  }

  public String getSourceHash() {
    return sourceHash;
  }

  public void setSourceHash(String sourceHash) {
    this.sourceHash = sourceHash;
  }

  public String getIdentifier() {
    return identifier;
  }

  public void setIdentifier(String identifier) {
    this.identifier = identifier;
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

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }
}
