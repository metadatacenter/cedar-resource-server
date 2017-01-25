package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarObjectNotFoundException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.service.UserService;

public class IndexRegenerator {

  public static void regenerate(CedarConfig cedarConfig, SearchService searchService, UserService userService) {
    try {
      String adminUserUUID = cedarConfig.getKeycloakConfig().getAdminUser().getUuid();
      CedarUser adminUser = null;
      try {
        adminUser = userService.findUser(adminUserUUID);
      } catch (Exception e) {
        throw new CedarProcessingException("Error while loading admin user by UUID:" + adminUserUUID, e);
      }
      if (adminUser == null) {
        throw new CedarObjectNotFoundException("Admin user not found by UUID:" + adminUserUUID);
      }

      CedarRequestContext c = CedarRequestContextFactory.fromUser(adminUser);
      searchService.regenerateSearchIndex(false, c);
    } catch (CedarException e) {
      System.out.println("There was an error while regenerating the search index");
      e.printStackTrace();
    }
  }
}
