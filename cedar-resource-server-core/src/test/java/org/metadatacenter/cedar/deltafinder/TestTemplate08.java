package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.ConstraintChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTemplate08 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("08");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert only one destructive ConstraintChange exists
    assertEquals(1, destructive.size(), "Should contain exactly one destructive change");
    Change change = destructive.get(0);
    assertTrue(change instanceof ConstraintChange, "Change should be a ConstraintChange");
    assertEquals("Field 2", change.getFieldName());

    ConstraintChange constraintChange = (ConstraintChange) change;
    assertTrue(constraintChange.isDestructive(), "Change should be destructive");
    assertTrue(constraintChange.getOldConstraint().contains("defaultValue"), "Change description should mention defaultValue");
    assertTrue(constraintChange.getOldConstraint().contains("minLength"), "Change description should mention minLength");
    assertTrue(constraintChange.getOldConstraint().contains("maxLength"), "Change description should mention maxLength");

    // Assert no non-destructive changes
    assertTrue(nonDestructive.isEmpty(), "Should not contain non-destructive changes");
  }
}
