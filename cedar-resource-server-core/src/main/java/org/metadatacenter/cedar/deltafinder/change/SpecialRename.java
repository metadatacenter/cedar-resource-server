package org.metadatacenter.cedar.deltafinder.change;

public class SpecialRename extends Change {
  private final String swappedWith;

  public SpecialRename(String fieldName, String swappedWith) {
    super(fieldName);
    this.swappedWith = swappedWith;
  }

  public String getSwappedWith() {
    return swappedWith;
  }

  @Override
  public boolean isDestructive() {
    return false;
  }

  @Override
  public String toString() {
    return "SpecialRename: " + getFieldName() + " swapped with " + swappedWith;
  }
}

