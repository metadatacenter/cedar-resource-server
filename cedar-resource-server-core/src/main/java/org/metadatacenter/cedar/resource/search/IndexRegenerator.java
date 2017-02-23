package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarObjectNotFoundException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.search.util.RegenerateSearchIndexTask;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexRegenerator {

  private static final Logger log = LoggerFactory.getLogger(IndexRegenerator.class);

  public static void regenerate(CedarConfig cedarConfig, UserService userService) {
    try {
      String adminUserUUID = cedarConfig.getAdminUserConfig().getUuid();
      CedarUser adminUser;
      try {
        adminUser = userService.findUser(adminUserUUID);
      } catch (Exception e) {
        throw new CedarProcessingException("Error while loading admin user by UUID:" + adminUserUUID, e);
      }
      if (adminUser == null) {
        throw new CedarObjectNotFoundException("Admin user not found by UUID:" + adminUserUUID);
      }

      CedarRequestContext c = CedarRequestContextFactory.fromUser(adminUser);
      RegenerateSearchIndexTask task = new RegenerateSearchIndexTask(cedarConfig);
      task.regenerateSearchIndex(false, c);
    } catch (CedarException e) {
      log.error("There was an error while regenerating the search index", e);
    }
  }

}
