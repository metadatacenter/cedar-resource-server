package org.metadatacenter.cedar.deltafinder.change;

public class Addition extends Change {
  public Addition(String fieldName) {
    super(fieldName);
  }

  @Override
  public boolean isDestructive() {
    return false;
  }
}

