package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.ConstraintChange;
import org.metadatacenter.cedar.deltafinder.change.TypeChange;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestTemplate11 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("11");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Check there are exactly 2 destructive changes
    assertEquals("There should be 2 destructive changes", 2, destructive.size());

    // Check for TypeChange
    boolean hasTypeChange = destructive.stream().anyMatch(c ->
        c instanceof TypeChange &&
            c.getFieldName().equals("Field 2") &&
            ((TypeChange) c).getOldType().equals("TextFieldRecord") &&
            ((TypeChange) c).getNewType().equals("TemporalFieldRecord")
    );
    assertTrue("Should contain destructive TypeChange on Field 2", hasTypeChange);

    // Check for ConstraintChange
    boolean hasConstraintChange = destructive.stream().anyMatch(c ->
        c instanceof ConstraintChange &&
            c.getFieldName().equals("Field 2")
    );
    assertTrue("Should contain destructive ConstraintChange on Field 2", hasConstraintChange);

    // There should be no non-destructive changes
    assertTrue("Should not contain non-destructive changes", nonDestructive.isEmpty());
  }
}
