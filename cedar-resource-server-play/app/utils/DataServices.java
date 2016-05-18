package utils;

import org.apache.commons.codec.EncoderException;
import org.metadatacenter.cedar.resource.search.SearchService;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.server.security.CedarApiKeyAuthRequest;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Configuration;
import play.Play;

import java.io.IOException;

import static org.metadatacenter.constant.ConfigConstants.*;
import static org.metadatacenter.constant.ElasticsearchConstants.*;

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
        config.getString(ES_TYPE), config.getString(FOLDER_SERVER_BASE), config.getString(TEMPLATE_SERVER_BASE));

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
    }

    // Regenerate search index if necessary
    String apiKey = adminUser.getFirstActiveApiKey();
    IAuthRequest authRequest = new CedarApiKeyAuthRequest(apiKey);
    try {
      searchService.regenerateSearchIndex(false, authRequest);
    } catch (Exception e) {
      play.Logger.error("Error while regenerating the search index");
    }

  }

  public UserService getUserService() {
    return userService;
  }

  public SearchService getSearchService() {
    return searchService;
  }
}
