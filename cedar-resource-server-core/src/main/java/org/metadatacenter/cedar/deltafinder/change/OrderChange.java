package org.metadatacenter.cedar.deltafinder.change;

public class OrderChange extends Change {
  public OrderChange(String fieldName) {
    super(fieldName);
  }

  @Override
  public boolean isDestructive() {
    return false;
  }
}

