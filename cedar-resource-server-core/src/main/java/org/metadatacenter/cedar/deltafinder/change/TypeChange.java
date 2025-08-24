package org.metadatacenter.cedar.deltafinder.change;

public class TypeChange extends Change {
  private final String oldType;
  private final String newType;
  private final boolean destructive;

  public TypeChange(String fieldName, String oldType, String newType, boolean destructive) {
    super(fieldName);
    this.oldType = oldType;
    this.newType = newType;
    this.destructive = destructive;
  }

  public String getOldType() {
    return oldType;
  }

  public String getNewType() {
    return newType;
  }

  @Override
  public boolean isDestructive() {
    return destructive;
  }

  @Override
  public String toString() {
    return "TypeChange: " + getFieldName() + " (" + oldType + " -> " + newType + ")";
  }
}

