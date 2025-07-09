package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.Deletion;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate14 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("14");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Should contain one destructive deletion of a field
    boolean hasDeletion = destructive.stream().anyMatch(c ->
        c instanceof Deletion &&
            c.getFieldName().equals("Field 2 in element") &&
            ((Deletion) c).getArtifactType().equals("field")
    );
    assertTrue("Should contain deletion of field: Field 2 in element", hasDeletion);

    // Should not contain any non-destructive changes
    assertTrue("Should not contain non-destructive changes", nonDestructive.isEmpty());
  }
}
