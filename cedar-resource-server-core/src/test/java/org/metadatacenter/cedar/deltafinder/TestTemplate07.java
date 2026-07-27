package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTemplate07 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("07");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert that there's exactly one deletion: Field 2
    boolean hasField2Deletion = destructive.stream().anyMatch(c ->
        c instanceof Deletion &&
            c.getFieldName().equals("Field 2") &&
            ((Deletion) c).getArtifactType().equals("field"));
    assertTrue(hasField2Deletion, "Expected deletion of field: Field 2");

    // Assert that there's exactly one addition: Field 4
    boolean hasField4Addition = nonDestructive.stream().anyMatch(c ->
        c instanceof Addition &&
            c.getFieldName().equals("Field 4") &&
            ((Addition) c).getArtifactType().equals("field"));
    assertTrue(hasField4Addition, "Expected addition of field: Field 4");
  }
}
