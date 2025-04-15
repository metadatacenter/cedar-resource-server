package org.metadatacenter.cedar.deltafinder.change;

public abstract class Change {
  private final String fieldName;

  public Change(String fieldName) {
    this.fieldName = fieldName;
  }

  public String getFieldName() {
    return fieldName;
  }

  public abstract boolean isDestructive();

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + " on field: " + fieldName;
  }
}
