package org.metadatacenter.cedar.resource.search;

import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.server.search.util.RegenerateSearchIndexTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexCreator {

  private static final Logger log = LoggerFactory.getLogger(IndexCreator.class);

  public static void ensureSearchIndexExists(CedarConfig cedarConfig) {
    RegenerateSearchIndexTask task = new RegenerateSearchIndexTask(cedarConfig);
    try {
      task.ensureSearchIndexExists();
    } catch (CedarException e) {
      log.error("There was an error while creating the search index", e);
    }
  }

}
