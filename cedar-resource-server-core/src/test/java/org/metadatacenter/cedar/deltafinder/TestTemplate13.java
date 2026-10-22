package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
    assertTrue(hasAddition, "Should contain addition of field: Field 2 in element");

    // The element's property IRI differs between the two versions, and a stored instance maps the
    // element's name to the IRI it was written with
    assertEquals(1, destructive.size(), "Should contain exactly one destructive change");
    assertPropertyIriChange(destructive.get(0), "element-13-v1");
  }
}
