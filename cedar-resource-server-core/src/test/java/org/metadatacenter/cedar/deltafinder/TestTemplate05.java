package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate05 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("05");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert that there's one destructive deletion on Field 1
    boolean hasExpectedDeletion = destructive.stream().anyMatch(c ->
        c instanceof Deletion &&
            c.getFieldName().equals("Field 1") &&
            ((Deletion) c).getArtifactType().equals("field"));
    assertTrue("Expected Deletion on field: Field 1", hasExpectedDeletion);

    // Assert no non-destructive changes
    assertTrue("Expected no non-destructive changes", nonDestructive.isEmpty());
  }
}
