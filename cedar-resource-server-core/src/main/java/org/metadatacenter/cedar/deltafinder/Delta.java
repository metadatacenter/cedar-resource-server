package org.metadatacenter.cedar.deltafinder;

import org.metadatacenter.cedar.deltafinder.change.Change;

import java.util.ArrayList;
import java.util.List;

public class Delta {
  private final List<Change> destructiveChanges;
  private final List<Change> nonDestructiveChanges;

  public Delta() {
    this.destructiveChanges = new ArrayList<>();
    this.nonDestructiveChanges = new ArrayList<>();
  }

  public Delta(List<Change> destructiveChanges, List<Change> nonDestructiveChanges) {
    this.destructiveChanges = destructiveChanges;
    this.nonDestructiveChanges = nonDestructiveChanges;
  }

  public void addDestructiveChange(Change change) {
    destructiveChanges.add(change);
  }

  public void addNonDestructiveChange(Change change) {
    nonDestructiveChanges.add(change);
  }

  public List<Change> getDestructiveChanges() {
    return destructiveChanges;
  }

  public List<Change> getNonDestructiveChanges() {
    return nonDestructiveChanges;
  }

  public boolean hasDestructiveChanges() {
    return !destructiveChanges.isEmpty();
  }

  public boolean hasNonDestructiveChanges() {
    return !nonDestructiveChanges.isEmpty();
  }

  public boolean isSafeChange() {
    return destructiveChanges.isEmpty();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Delta Report:\n");

    if (!destructiveChanges.isEmpty()) {
      sb.append("Destructive Changes:\n");
      for (Change change : destructiveChanges) {
        sb.append("  - ").append(change).append("\n");
      }
    } else {
      sb.append("No Destructive Changes.\n");
    }

    if (!nonDestructiveChanges.isEmpty()) {
      sb.append("Non-Destructive Changes:\n");
      for (Change change : nonDestructiveChanges) {
        sb.append("  - ").append(change).append("\n");
      }
    } else {
      sb.append("No Non-Destructive Changes.\n");
    }

    return sb.toString();
  }
}

