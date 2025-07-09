package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.*;

import java.util.List;

import static org.junit.Assert.*;

public class TestTemplate01 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("01");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no destructive changes
    assertTrue("Expected no destructive changes", destructive.isEmpty());

    // Assert Rename from Text Field Old to Text Field New
    boolean hasExpectedRename = nonDestructive.stream().anyMatch(c ->
        c instanceof Rename &&
            c.getFieldName().equals("Text Field Old") &&
            ((Rename) c).getNewFieldName().equals("Text Field New")
    );
    assertTrue("Expected Rename from Text Field Old to Text Field New", hasExpectedRename);
  }
}
