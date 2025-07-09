package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate13 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("13");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Should contain one non-destructive addition of a field
    boolean hasAddition = nonDestructive.stream().anyMatch(c ->
        c instanceof Addition &&
            c.getFieldName().equals("Field 2 in element") &&
            ((Addition) c).getArtifactType().equals("field")
    );
    assertTrue("Should contain addition of field: Field 2 in element", hasAddition);

    // Should not contain any destructive changes
    assertTrue("Should not contain destructive changes", destructive.isEmpty());
  }
}
