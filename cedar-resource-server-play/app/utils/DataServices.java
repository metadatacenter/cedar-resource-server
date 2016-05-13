package utils;

import org.metadatacenter.cedar.resource.search.SearchService;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;
import play.Configuration;
import play.Play;

import static org.metadatacenter.constant.ConfigConstants.MONGODB_DATABASE_NAME;
import static org.metadatacenter.constant.ConfigConstants.USERS_COLLECTION_NAME;

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
        config.getString(ES_HOST), config.getInt(ES_TRANSPORT_PORT), config.getInt(ES_SIZE)),
        config.getString(ES_INDEX),
        config.getString(ES_TYPE));

  }

  public UserService getUserService() {
    return userService;
  }

  public SearchService getSearchService() {
    return searchService;
  }
}
