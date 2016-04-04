package org.metadatacenter.server.model;

public class CedarResource {

  private String id;
  private String type;
  private CedarResourceType resourceType;
  private String path;
  private String name;
  private String description;
  private ProvenanceTime createdOn;
  private ProvenanceTime lastUpdatedOn;
  private ProvenanceUser createdBy;
  private ProvenanceUser lastUpdatedBy;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public CedarResourceType getResourceType() {
    return resourceType;
  }

  public void setResourceType(CedarResourceType resourceType) {
    this.resourceType = resourceType;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
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

  public ProvenanceTime getCreatedOn() {
    return createdOn;
  }

  public void setCreatedOn(ProvenanceTime createdOn) {
    this.createdOn = createdOn;
  }

  public ProvenanceTime getLastUpdatedOn() {
    return lastUpdatedOn;
  }

  public void setLastUpdatedOn(ProvenanceTime lastUpdatedOn) {
    this.lastUpdatedOn = lastUpdatedOn;
  }

  public ProvenanceUser getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(ProvenanceUser createdBy) {
    this.createdBy = createdBy;
  }

  public ProvenanceUser getLastUpdatedBy() {
    return lastUpdatedBy;
  }

  public void setLastUpdatedBy(ProvenanceUser lastUpdatedBy) {
    this.lastUpdatedBy = lastUpdatedBy;
  }
}
