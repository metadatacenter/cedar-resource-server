package org.metadatacenter.cedar.resource;

import io.dropwizard.setup.Environment;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.health.ResourceServerHealthCheck;
import org.metadatacenter.cedar.resource.resources.*;
import org.metadatacenter.cedar.resource.search.IndexRegenerator;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceApplication;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.server.cache.util.CacheService;
import org.metadatacenter.server.search.elasticsearch.service.*;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ResourceServerApplication extends CedarMicroserviceApplication<ResourceServerConfiguration> {

  public static void main(String[] args) throws Exception {
    new ResourceServerApplication().run(args);
  }

  @Override
  protected ServerName getServerName() {
    return ServerName.RESOURCE;
  }

  @Override
  public void initializeApp() {
    CedarDataServices.initializeFolderServices(cedarConfig);

    ElasticsearchServiceFactory esServiceFactory = ElasticsearchServiceFactory.getInstance(cedarConfig);
    NodeIndexingService nodeIndexingService = esServiceFactory.nodeIndexingService();
    NodeSearchingService nodeSearchingService = esServiceFactory.nodeSearchingService();
    ContentIndexingService contentIndexingService = esServiceFactory.contentIndexingService();
    ContentSearchingService contentSearchingService = esServiceFactory.contentSearchingService();
    UserPermissionIndexingService userPermissionIndexingService = esServiceFactory.userPermissionsIndexingService();
    GroupPermissionIndexingService groupPermissionIndexingService = esServiceFactory.groupPermissionsIndexingService();

    SearchPermissionEnqueueService searchPermissionEnqueueService = new SearchPermissionEnqueueService(
        new CacheService(cedarConfig.getCacheConfig().getPersistent()));

    CommandResource.injectUserService(userService);
    SearchResource.injectServices(nodeIndexingService, nodeSearchingService, contentIndexingService,
        contentSearchingService, searchPermissionEnqueueService, userPermissionIndexingService,
        groupPermissionIndexingService);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      IndexRegenerator.regenerate(cedarConfig, userService);
    });
  }

  @Override
  public void runApp(ResourceServerConfiguration configuration, Environment environment) {
    final IndexResource index = new IndexResource(cedarConfig);
    environment.jersey().register(index);

    final FoldersResource folders = new FoldersResource(cedarConfig);
    environment.jersey().register(folders);

    final FolderContentsResource folderContents = new FolderContentsResource(cedarConfig);
    environment.jersey().register(folderContents);

    final UsersResource user = new UsersResource(cedarConfig);
    environment.jersey().register(user);

    final CommandResource command = new CommandResource(cedarConfig);
    environment.jersey().register(command);

    final SearchResource search = new SearchResource(cedarConfig);
    environment.jersey().register(search);

    final SearchDeepResource searchDeep = new SearchDeepResource(cedarConfig);
    environment.jersey().register(searchDeep);

    // TODO: we do not handle fields for now
    //final TemplateFieldsResource fields = new TemplateFieldsResource(cedarConfig);
    //environment.jersey().register(fields);

    final TemplateElementsResource elements = new TemplateElementsResource(cedarConfig);
    environment.jersey().register(elements);

    final TemplatesResource templates = new TemplatesResource(cedarConfig);
    environment.jersey().register(templates);

    final TemplateInstancesResource instances = new TemplateInstancesResource(cedarConfig);
    environment.jersey().register(instances);

    final ResourceServerHealthCheck healthCheck = new ResourceServerHealthCheck();
    environment.healthChecks().register("message", healthCheck);
  }
}
