package org.metadatacenter.cedar.deltafinder.change;

public class Deletion extends Change {
  private final String type;

  public Deletion(String name, String type) {
    super(name);
    this.type = type;
  }

  @Override
  public boolean isDestructive() {
    return true;
  }

  public String getArtifactType() {
    return type;
  }

  @Override
  public String toString() {
    return "Deletion on " + type + ": " + getFieldName();
  }

}
