package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.ConstraintChange;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate08 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("08");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert only one destructive ConstraintChange exists
    assertEquals("Should contain exactly one destructive change", 1, destructive.size());
    Change change = destructive.get(0);
    assertTrue("Change should be a ConstraintChange", change instanceof ConstraintChange);
    assertEquals("Field 2", change.getFieldName());

    ConstraintChange constraintChange = (ConstraintChange) change;
    assertTrue("Change should be destructive", constraintChange.isDestructive());
    assertTrue("Change description should mention defaultValue", constraintChange.getOldConstraint().contains("defaultValue"));
    assertTrue("Change description should mention minLength", constraintChange.getOldConstraint().contains("minLength"));
    assertTrue("Change description should mention maxLength", constraintChange.getOldConstraint().contains("maxLength"));

    // Assert no non-destructive changes
    assertTrue("Should not contain non-destructive changes", nonDestructive.isEmpty());
  }
}
