package utils;

import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;
import play.Configuration;
import play.Play;

import static org.metadatacenter.constant.ConfigConstants.MONGODB_DATABASE_NAME;
import static org.metadatacenter.constant.ConfigConstants.USERS_COLLECTION_NAME;

public class DataServices {

  private static DataServices instance = new DataServices();
  private static UserService userService;

  public static DataServices getInstance() {
    return instance;
  }

  private DataServices() {
    Configuration config = Play.application().configuration();
    userService = new UserServiceMongoDB(
        config.getString(MONGODB_DATABASE_NAME),
        config.getString(USERS_COLLECTION_NAME));

  }

  public UserService getUserService() {
    return userService;
  }
}
