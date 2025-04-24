package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.OrderChange;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate09 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("09");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no destructive changes
    assertTrue("Should not contain destructive changes", destructive.isEmpty());

    // Assert there is one Addition and one OrderChange
    boolean hasAddition = nonDestructive.stream()
        .anyMatch(c -> c instanceof Addition && c.getFieldName().equals("Field 0"));
    boolean hasOrderChange = nonDestructive.stream()
        .anyMatch(c -> c instanceof OrderChange);

    assertTrue("Should contain an Addition on Field 0", hasAddition);
    assertTrue("Should contain an OrderChange", hasOrderChange);
  }
}
