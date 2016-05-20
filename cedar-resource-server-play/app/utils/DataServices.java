package utils;

import org.metadatacenter.cedar.resource.search.SearchService;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.server.security.CedarApiKeyAuthRequest;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;
import play.Configuration;
import play.Play;

import static org.metadatacenter.constant.ConfigConstants.*;
import static org.metadatacenter.constant.ElasticsearchConstants.*;
import static org.metadatacenter.constant.ResourceConstants.*;

public class DataServices {

  private static DataServices instance = new DataServices();
  private static UserService userService;
  private static SearchService searchService;

  public static DataServices getInstance() {
    return instance;
  }

  private DataServices() {
    Configuration config = Play.application().configuration();
    userService = new UserServiceMongoDB(
        config.getString(MONGODB_DATABASE_NAME),
        config.getString(USERS_COLLECTION_NAME));
    searchService = new SearchService(new ElasticsearchService(config.getString(ES_CLUSTER),
        config.getString(ES_HOST), config.getInt(ES_TRANSPORT_PORT), config.getInt(ES_SIZE),
        config.getInt(ES_SCROLL_KEEP_ALIVE)), config.getString(ES_INDEX),
        config.getString(ES_TYPE), config.getString(FOLDER_SERVER_BASE), config.getString(TEMPLATE_SERVER_BASE),
        config.getInt(RETRIEVE_LIMIT), config.getInt(RETRIEVE_MAX_ATTEMPS), config.getInt(RETRIEVE_DELAY_ATTEMPS));

    CedarUser adminUser = null;
    String userId = null;
    try {
      userId = config.getString(USER_ADMIN_USER_UUID);
      adminUser = userService.findUser(userId);
    } catch (Exception ex) {
      play.Logger.error("Error while loading admin user for id:" + userId + ":");
    }
    if (adminUser == null) {
      play.Logger.error("Admin user not found for id:" + userId + ":");
      play.Logger.error("Unable to regenerate search index!");
    } else {
      // Regenerate search index if necessary
      String apiKey = adminUser.getFirstActiveApiKey();
      IAuthRequest authRequest = new CedarApiKeyAuthRequest(apiKey);
      try {
        searchService.regenerateSearchIndex(false, authRequest);
      } catch (Exception e) {
        play.Logger.error("Error while regenerating the search index", e);
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
