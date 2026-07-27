package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.OrderChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTemplate09 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("09");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no destructive changes
    assertTrue(destructive.isEmpty(), "Should not contain destructive changes");

    // Assert there is one Addition and one OrderChange
    boolean hasAddition = nonDestructive.stream()
        .anyMatch(c -> c instanceof Addition && c.getFieldName().equals("Field 0"));
    boolean hasOrderChange = nonDestructive.stream()
        .anyMatch(c -> c instanceof OrderChange);

    assertTrue(hasAddition, "Should contain an Addition on Field 0");
    assertTrue(hasOrderChange, "Should contain an OrderChange");
  }
}
