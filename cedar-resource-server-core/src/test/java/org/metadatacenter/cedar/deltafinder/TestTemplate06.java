package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate06 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("06");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no destructive changes
    assertTrue("Expected no destructive changes", destructive.isEmpty());

    // Assert rename from Field 2 to Field 4
    boolean hasExpectedRename = nonDestructive.stream().anyMatch(c ->
        c instanceof Rename &&
            c.getFieldName().equals("Field 2") &&
            ((Rename) c).getNewFieldName().equals("Field 4"));
    assertTrue("Expected Rename from Field 2 to Field 4", hasExpectedRename);
  }
}
