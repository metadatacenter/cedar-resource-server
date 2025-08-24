package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate15 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("15");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no destructive changes
    assertTrue("Should not contain destructive changes", destructive.isEmpty());

    // Assert one addition of element-151 as an element
    boolean hasAddition = nonDestructive.stream().anyMatch(c ->
        c instanceof Addition &&
            c.getFieldName().equals("element-151") &&
            ((Addition) c).getArtifactType().equals("element")
    );
    assertTrue("Should contain Addition of element: element-151", hasAddition);
  }
}
