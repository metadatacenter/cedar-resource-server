package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.ConstraintChange;
import org.metadatacenter.cedar.deltafinder.change.TypeChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestTemplate11 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("11");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Check there are exactly 2 destructive changes
    assertEquals(2, destructive.size(), "There should be 2 destructive changes");

    // Check for TypeChange
    boolean hasTypeChange = destructive.stream().anyMatch(c ->
        c instanceof TypeChange &&
            c.getFieldName().equals("Field 2") &&
            ((TypeChange) c).getOldType().equals("TextFieldRecord") &&
            ((TypeChange) c).getNewType().equals("TemporalFieldRecord")
    );
    assertTrue(hasTypeChange, "Should contain destructive TypeChange on Field 2");

    // Check for ConstraintChange
    boolean hasConstraintChange = destructive.stream().anyMatch(c ->
        c instanceof ConstraintChange &&
            c.getFieldName().equals("Field 2")
    );
    assertTrue(hasConstraintChange, "Should contain destructive ConstraintChange on Field 2");

    // There should be no non-destructive changes
    assertTrue(nonDestructive.isEmpty(), "Should not contain non-destructive changes");
  }
}
