package org.metadatacenter.cedar.deltafinder;

import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.Deletion;
import org.metadatacenter.cedar.deltafinder.change.Rename;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestTemplate18 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("18");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no deletions
    assertTrue(destructive.stream().noneMatch(c -> c instanceof Deletion), "Should not contain deletions");

    // Assert no additions
    assertTrue(nonDestructive.stream().noneMatch(c -> c instanceof Addition), "Should not contain additions");

    // Assert a rename occurred
    boolean hasRename = nonDestructive.stream().anyMatch(c ->
        c instanceof Rename &&
            c.getFieldName().equals("element-18") &&
            ((Rename) c).getNewFieldName().equals("element-18-renamed")
    );
    assertTrue(hasRename, "Should contain Rename from element-18 to element-18-renamed");

  }
}