package org.metadatacenter.cedar.deltafinder.change;

public class ConstraintChange extends Change {
  private final String oldConstraint;
  private final String newConstraint;
  private final boolean destructive;

  public ConstraintChange(String fieldName, String oldConstraint, String newConstraint, boolean destructive) {
    super(fieldName);
    this.oldConstraint = oldConstraint;
    this.newConstraint = newConstraint;
    this.destructive = destructive;
  }

  public String getOldConstraint() {
    return oldConstraint;
  }

  public String getNewConstraint() {
    return newConstraint;
  }

  @Override
  public boolean isDestructive() {
    return destructive;
  }

  @Override
  public String toString() {
    return "ConstraintChange: " + getFieldName() + " (" + oldConstraint + " -> " + newConstraint + ")";
  }
}

