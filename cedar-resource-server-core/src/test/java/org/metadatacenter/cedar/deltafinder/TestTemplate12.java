package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.Deletion;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate12 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("12");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Check for deletion of element-12-v1
    boolean hasDeletion = destructive.stream().anyMatch(c ->
        c instanceof Deletion &&
            c.getFieldName().equals("element-12-v1") &&
            ((Deletion) c).getArtifactType().equals("element")
    );
    assertTrue("Should contain deletion of element-12-v1", hasDeletion);

    // Check for the addition of element-12-v2
    boolean hasAddition = nonDestructive.stream().anyMatch(c ->
        c instanceof Addition &&
            c.getFieldName().equals("element-12-v2") &&
            ((Addition) c).getArtifactType().equals("element")
    );
    assertTrue("Should contain addition of element-12-v2", hasAddition);
  }
}
