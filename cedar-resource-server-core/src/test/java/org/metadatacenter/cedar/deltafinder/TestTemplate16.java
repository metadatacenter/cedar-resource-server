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

    // Assert exactly two destructive changes
    assertEquals(2, destructive.size(), "Should have exactly two destructive changes");
    assertTrue(destructive.get(0) instanceof Deletion, "The change should be a Deletion");

    Deletion deletion = (Deletion) destructive.get(0);
    assertEquals("element-151", deletion.getFieldName());
    assertEquals("element", deletion.getArtifactType());

    // The element's property IRI differs between the two versions, and a stored instance maps the
    // element's name to the IRI it was written with
    assertPropertyIriChange(destructive.get(1), "element-15");

    // Assert no non-destructive changes
    assertTrue(nonDestructive.isEmpty(), "Should have no non-destructive changes");
  }
}
