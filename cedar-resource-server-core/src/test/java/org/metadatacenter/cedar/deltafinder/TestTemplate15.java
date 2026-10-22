package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTemplate15 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("15");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // The element's property IRI differs between the two versions, and a stored instance maps the
    // element's name to the IRI it was written with
    assertEquals(1, destructive.size(), "Should contain exactly one destructive change");
    assertPropertyIriChange(destructive.get(0), "element-15");

    // Assert one addition of element-151 as an element
    boolean hasAddition = nonDestructive.stream().anyMatch(c ->
        c instanceof Addition &&
            c.getFieldName().equals("element-151") &&
            ((Addition) c).getArtifactType().equals("element")
    );
    assertTrue(hasAddition, "Should contain Addition of element: element-151");
  }
}
