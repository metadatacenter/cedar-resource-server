package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.search.util.RegenerateSearchIndexTask;
import org.metadatacenter.server.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexRegenerator {

  private static final Logger log = LoggerFactory.getLogger(IndexRegenerator.class);

  public static void regenerate(CedarConfig cedarConfig, UserService userService) {
    CedarRequestContext c = CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
    RegenerateSearchIndexTask task = new RegenerateSearchIndexTask(cedarConfig);
    try {
      task.regenerateSearchIndex(false, c);
    } catch (CedarException e) {
      log.error("There was an error while regenerating the search index", e);
    }
  }

}
