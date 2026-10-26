package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTemplate07 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("07");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Field 4 is Field 2 renamed and given a default, length bounds and a new property IRI. Its stored values move
    // to the new name, where a minimum length can refuse one. The instance clone moves them only by an unchanged
    // IRI, so the new IRI is reported too.
    assertEquals(1, nonDestructive.size(), "Should contain exactly one non-destructive change");
    Change rename = nonDestructive.get(0);
    assertTrue(rename instanceof Rename, "Expected Rename from Field 2 to Field 4");
    assertEquals("Field 2", rename.getFieldName());
    assertEquals("Field 4", ((Rename) rename).getNewFieldName());

    assertEquals(1, destructive.size(), "Should contain exactly one destructive change");
    Change change = destructive.get(0);
    assertTrue(change instanceof ConstraintChange, "Change should be a ConstraintChange");
    assertEquals("Field 4", change.getFieldName());
    ConstraintChange constraintChange = (ConstraintChange) change;
    assertTrue(constraintChange.getOldConstraint().contains("defaultValue"), "Change description should mention defaultValue");
    assertTrue(constraintChange.getOldConstraint().contains("minLength"), "Change description should mention minLength");
    assertTrue(constraintChange.getOldConstraint().contains("maxLength"), "Change description should mention maxLength");
    assertTrue(constraintChange.getOldConstraint().contains("propertyUri"), "Change description should mention propertyUri");
  }
}
