package org.metadatacenter.cedar.resource.util;

import org.metadatacenter.cedar.resource.search.SearchService;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.ElasticsearchConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.CedarApiKeyAuthRequest;
import org.metadatacenter.server.security.CedarApiKeyHttpServletRequest;
import org.metadatacenter.server.security.model.AuthRequest;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;

public class DataServices {

  private static DataServices instance = new DataServices();
  private static UserService userService;
  private static SearchService searchService;
  private static CedarConfig cedarConfig;

  public static DataServices getInstance() {
    return instance;
  }

  private DataServices() {
    cedarConfig = CedarConfig.getInstance();
    userService = new UserServiceMongoDB(cedarConfig.getMongoConfig().getDatabaseName(),
        cedarConfig.getMongoCollectionName(CedarNodeType.USER));

    ElasticsearchConfig esc = cedarConfig.getElasticsearchConfig();

    searchService = new SearchService(new ElasticsearchService(
        esc.getCluster(),
        esc.getHost(),
        esc.getTransportPort(),
        esc.getSize(),
        esc.getScrollKeepAlive(),
        esc.getSettings(),
        esc.getMappings()),
        esc.getIndex(),
        esc.getType(),
        cedarConfig.getServers().getFolder().getBase(),
        cedarConfig.getServers().getTemplate().getBase(),
        cedarConfig.getSearchSettings().getSearchRetrieveSettings().getLimitIndexRegeneration(),
        cedarConfig.getSearchSettings().getSearchRetrieveSettings().getMaxAttempts(),
        cedarConfig.getSearchSettings().getSearchRetrieveSettings().getDelayAttempts()
    );

    String adminUserUUID = cedarConfig.getKeycloakConfig().getAdminUser().getUuid();
    CedarUser adminUser = null;
    try {
      adminUser = userService.findUser(adminUserUUID);
    } catch (Exception ex) {
      play.Logger.error("Error while loading admin user for id:" + adminUserUUID + ":");
    }
    if (adminUser == null) {
      play.Logger.error("Admin user not found for id:" + adminUserUUID + ".");
      play.Logger.error("The requested task was not completed!");
    } else {
      // Regenerate search index if necessary
      String apiKey = adminUser.getFirstActiveApiKey();
      CedarApiKeyHttpServletRequest fakeRequest = new CedarApiKeyHttpServletRequest(apiKey);
      try {
        searchService.regenerateSearchIndex(false, fakeRequest);
      } catch (Exception e) {
        play.Logger.error("Error while regenerating the search index: ", e);
      }
    }
  }

  public UserService getUserService() {
    return userService;
  }

  public SearchService getSearchService() {
    return searchService;
  }
}
