package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.Deletion;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate16 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("16");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert exactly one destructive change
    assertEquals("Should have exactly one destructive change", 1, destructive.size());
    assertTrue("The change should be a Deletion", destructive.get(0) instanceof Deletion);

    Deletion deletion = (Deletion) destructive.get(0);
    assertEquals("element-151", deletion.getFieldName());
    assertEquals("element", deletion.getArtifactType());

    // Assert no non-destructive changes
    assertTrue("Should have no non-destructive changes", nonDestructive.isEmpty());
  }
}
