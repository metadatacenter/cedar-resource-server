package org.metadatacenter.cedar.deltafinder.change;

public class Addition extends Change {
  private final String type;

  public Addition(String name, String type) {
    super(name);
    this.type = type;
  }

  @Override
  public boolean isDestructive() {
    return false;
  }

  public String getArtifactType() {
    return type;
  }

  @Override
  public String toString() {
    return "Addition on " + type + ": " + getFieldName();
  }
}
