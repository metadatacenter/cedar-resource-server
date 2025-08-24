package org.metadatacenter.cedar.deltafinder;

import org.junit.Test;
import org.metadatacenter.cedar.deltafinder.change.Addition;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.cedar.deltafinder.change.Deletion;
import org.metadatacenter.cedar.deltafinder.change.Rename;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class TestTemplate17 extends SimpleTemplateTest {

  @Test
  public void testSimpleTemplate() throws Exception {
    Delta delta = testSimpleTemplate("17");

    List<Change> destructive = delta.getDestructiveChanges();
    List<Change> nonDestructive = delta.getNonDestructiveChanges();

    // Assert no deletions
    assertTrue("Should not contain deletions", destructive.stream().noneMatch(c -> c instanceof Deletion));

    // Assert no additions
    assertTrue("Should not contain additions", nonDestructive.stream().noneMatch(c -> c instanceof Addition));

    // Assert a rename occurred
    boolean hasRename = nonDestructive.stream().anyMatch(c ->
        c instanceof Rename &&
            c.getFieldName().equals("Test Field for Inclusion") &&
            ((Rename) c).getNewFieldName().equals("Test Field for Inclusion Renamed")
    );
    assertTrue("Should contain Rename from Test Field for Inclusion to Test Field for Inclusion Renamed", hasRename);
  }
}
