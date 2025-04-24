package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate04 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("04");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert that there's one destructive deletion on Field 3
    boolean hasExpectedDeletion = destructive.stream().anyMatch(c ->
        c instanceof Deletion &&
            c.getFieldName().equals("Field 3") &&
            ((Deletion) c).getArtifactType().equals("field"));
    assertTrue("Expected Deletion on field: Field 3", hasExpectedDeletion);

    // Assert no non-destructive changes
    assertTrue("Expected no non-destructive changes", nonDestructive.isEmpty());
  }
}
