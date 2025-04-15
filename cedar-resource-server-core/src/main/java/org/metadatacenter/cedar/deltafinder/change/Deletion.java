package org.metadatacenter.cedar.deltafinder.change;

public class Deletion extends Change {
  public Deletion(String fieldName) {
    super(fieldName);
  }

  @Override
  public boolean isDestructive() {
    return true;
  }
}

