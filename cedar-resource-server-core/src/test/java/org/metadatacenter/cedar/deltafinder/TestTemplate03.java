package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTemplate03 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("03");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no destructive changes
    assertTrue(destructive.isEmpty(), "Expected no destructive changes");

    // Assert there is an addition of Text Field 0
    boolean hasExpectedAddition = nonDestructive.stream().anyMatch(c ->
        c instanceof Addition &&
            c.getFieldName().equals("Text Field 0") &&
            ((Addition) c).getArtifactType().equals("field"));
    assertTrue(hasExpectedAddition, "Expected Addition on field: Text Field 0");
  }
}
