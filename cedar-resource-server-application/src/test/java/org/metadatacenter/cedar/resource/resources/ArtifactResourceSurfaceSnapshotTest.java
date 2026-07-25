package org.metadatacenter.cedar.resource.resources;

import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Pins the declared REST surface of the four artifact-type resource classes against a
 * checked-in snapshot, without booting the application. Any change to a verb, path, media
 * type, parameter, or swagger operation title shows up as a readable line diff.
 *
 * Regeneration is a deliberate act: run with -Dsurface.snapshot.regenerate=true to rewrite
 * the snapshot from the current code; the test then fails on purpose, so the new snapshot
 * gets diffed and committed consciously rather than silently.
 */
public class ArtifactResourceSurfaceSnapshotTest {

  private static final String SNAPSHOT_RESOURCE = "/artifact-resource-surface.txt";

  // Surefire runs the forked JVM with the module directory as its working directory
  private static final Path SNAPSHOT_SOURCE_FILE = Paths.get("src", "test", "resources", "artifact-resource-surface.txt");

  private static final String REGENERATE_PROPERTY = "surface.snapshot.regenerate";

  @Test
  public void declaredSurfaceMatchesSnapshot() throws Exception {
    String current = String.join("\n", ArtifactResourceSurface.describeLines()) + "\n";

    if (Boolean.getBoolean(REGENERATE_PROPERTY)) {
      Files.createDirectories(SNAPSHOT_SOURCE_FILE.getParent());
      Files.writeString(SNAPSHOT_SOURCE_FILE, current, StandardCharsets.UTF_8);
      Assert.fail("Regenerated the surface snapshot at " + SNAPSHOT_SOURCE_FILE.toAbsolutePath()
          + ". Diff it against git, make sure every surface change is intentional, then rerun without -D"
          + REGENERATE_PROPERTY + "=true.");
    }

    String expected;
    try (InputStream in = getClass().getResourceAsStream(SNAPSHOT_RESOURCE)) {
      Assert.assertNotNull("The surface snapshot " + SNAPSHOT_RESOURCE + " is missing from the test resources. "
          + "Generate it deliberately with -D" + REGENERATE_PROPERTY + "=true and commit it.", in);
      expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    Assert.assertEquals("The declared REST surface of the artifact-type resources no longer matches the "
        + "checked-in snapshot (" + SNAPSHOT_RESOURCE + "). If the change is intentional, regenerate the "
        + "snapshot with -D" + REGENERATE_PROPERTY + "=true, diff it against git, and commit the new "
        + "snapshot together with the resource change.", expected, current);
  }

}
