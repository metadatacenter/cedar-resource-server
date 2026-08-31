package org.metadatacenter.cedar.resource.config;

import org.junit.jupiter.api.Assertions;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.TrustedFoldersConfig;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.test.AbstractCedarConfigTest;

public class CedarConfigResourceTest extends AbstractCedarConfigTest {

  @Override
  protected SystemComponent getSystemComponent() {
    return SystemComponent.SERVER_RESOURCE;
  }

  /**
   * The API key salt is the resource server's alone, and the trusted folders are shared only with
   * the worker. The trusted folders get one assertion beyond resolution: the string is JSON that
   * {@code TrustedFoldersConfig} parses into the map the server actually reads, and a value that
   * arrives but does not parse leaves an empty map and no trusted folder, silently.
   */
  @Override
  protected void assertServerSpecificConfig(CedarConfig config) {
    assertResolved("blueprintUserProfile.defaultAPIKey.salt",
        config.getBlueprintUserProfile().getDefaultAPIKey().getSalt());

    TrustedFoldersConfig trustedFolders = config.getTrustedFolders();
    assertResolved("trustedFolders.foldersStr", trustedFolders.getFoldersStr());
    Assertions.assertFalse(trustedFolders.getFoldersMap().isEmpty(),
        "the resource server parsed no trusted folder out of " + trustedFolders.getFoldersStr());
  }

}
