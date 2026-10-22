package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTemplate06 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("06");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // The renamed field has a new property IRI. The fixture was once expected to hold no destructive change, when a
    // renamed field's IRI went uncompared. The instance clone moves a renamed field's stored values to its new name
    // only when the IRI is unchanged, so the new IRI leaves them under a name the template no longer declares.
    assertEquals(1, destructive.size(), "Expected exactly one destructive change");
    assertPropertyIriChange(destructive.get(0), "Field 4");

    // Assert rename from Field 2 to Field 4
    boolean hasExpectedRename = nonDestructive.stream().anyMatch(c ->
        c instanceof Rename &&
            c.getFieldName().equals("Field 2") &&
            ((Rename) c).getNewFieldName().equals("Field 4"));
    assertTrue(hasExpectedRename, "Expected Rename from Field 2 to Field 4");
  }
}
