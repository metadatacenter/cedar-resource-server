package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.Deletion;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTemplate16 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("16");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert exactly one destructive change
    assertEquals(1, destructive.size(), "Should have exactly one destructive change");
    assertTrue(destructive.get(0) instanceof Deletion, "The change should be a Deletion");

    Deletion deletion = (Deletion) destructive.get(0);
    assertEquals("element-151", deletion.getFieldName());
    assertEquals("element", deletion.getArtifactType());

    // Assert no non-destructive changes
    assertTrue(nonDestructive.isEmpty(), "Should have no non-destructive changes");
  }
}
