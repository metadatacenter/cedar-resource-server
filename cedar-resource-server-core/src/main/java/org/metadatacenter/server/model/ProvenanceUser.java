package org.metadatacenter.server.model;

public class ProvenanceUser {
  private String id;
  private String name;

  public ProvenanceUser(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
