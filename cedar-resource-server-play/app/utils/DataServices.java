package utils;

import controllers.FolderController;
import controllers.SearchController;
import org.metadatacenter.server.IResourceService;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.ResourceServiceMongoDB;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;
import play.Configuration;
import play.Play;

import static org.metadatacenter.constant.ConfigConstants.*;

public class DataServices {

  private static DataServices instance = new DataServices();
  private static IResourceService resourceService;
  private static UserService userService;

  public static DataServices getInstance() {
    return instance;
  }

  private DataServices() {
    Configuration config = Play.application().configuration();
    userService = new UserServiceMongoDB(
        config.getString(MONGODB_DATABASE_NAME),
        config.getString(USERS_COLLECTION_NAME));
    resourceService = new ResourceServiceMongoDB(
        config.getString(MONGODB_DATABASE_NAME),
        config.getString(RESOURCES_COLLECTION_NAME));

    FolderController.injectResourceService(resourceService);
    SearchController.injectResourceService(resourceService);
  }

  public UserService getUserService() {
    return userService;
  }
}
