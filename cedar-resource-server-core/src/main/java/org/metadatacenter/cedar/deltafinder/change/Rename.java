package org.metadatacenter.cedar.deltafinder.change;

public class Rename extends Change {
  private final String newFieldName;

  public Rename(String oldFieldName, String newFieldName) {
    super(oldFieldName);
    this.newFieldName = newFieldName;
  }

  public String getNewFieldName() {
    return newFieldName;
  }

  @Override
  public boolean isDestructive() {
    return false;
  }

  @Override
  public String toString() {
    return "Rename: " + getFieldName() + " -> " + newFieldName;
  }
}

