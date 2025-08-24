package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.OrderChange;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate10 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("10");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no destructive changes
    assertTrue("Should not contain destructive changes", destructive.isEmpty());

    // Assert there is exactly one non-destructive OrderChange
    assertEquals(1, nonDestructive.size());
    assertTrue(nonDestructive.get(0) instanceof OrderChange);

    OrderChange orderChange = (OrderChange) nonDestructive.get(0);
    assertEquals("OrderChange on field: Field order changed", orderChange.toString());
  }
}
