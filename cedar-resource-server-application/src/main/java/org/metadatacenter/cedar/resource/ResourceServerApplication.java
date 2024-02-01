package org.metadatacenter.cedar.resource;

import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.metadatacenter.cedar.resource.health.ResourceServerHealthCheck;
import org.metadatacenter.cedar.resource.resources.*;
import org.metadatacenter.cedar.resource.search.IndexCreator;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceApplication;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.ServerName;
import org.metadatacenter.server.cache.user.UserSummaryCache;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;

public class ResourceServerApplication extends CedarMicroserviceApplication<ResourceServerConfiguration> {

  public static void main(String[] args) throws Exception {
    new ResourceServerApplication().run(args);
  }

  @Override
  protected ServerName getServerName() {
    return ServerName.RESOURCE;
  }

  @Override
  protected void initializeWithBootstrap(Bootstrap<ResourceServerConfiguration> bootstrap, CedarConfig cedarConfig) {
  }

  @Override
  public void initializeApp() {
    UserSummaryCache.init(cedarConfig, userService);

    IndexUtils indexUtils = new IndexUtils(cedarConfig);
    NodeIndexingService nodeIndexingService = indexUtils.getNodeIndexingService();
    NodeSearchingService nodeSearchingService = indexUtils.getNodeSearchingService();

    SearchPermissionEnqueueService searchPermissionEnqueueService = new SearchPermissionEnqueueService(cedarConfig);

    ValuerecommenderReindexQueueService valuerecommenderReindexQueueService =
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent());

    CommandGenericResource.injectUserService(userService);
    CommandSearchResource.injectUserService(userService);
    SearchResource.injectServices(nodeIndexingService, nodeSearchingService,
        searchPermissionEnqueueService, valuerecommenderReindexQueueService);

    IndexCreator.ensureSearchIndexExists(cedarConfig);
    IndexCreator.ensureRulesIndexExists(cedarConfig);
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

    final CommandGenericResource commandGeneric = new CommandGenericResource(cedarConfig);
    environment.jersey().register(commandGeneric);

    final CommandFileSystemResource commandFileSystem = new CommandFileSystemResource(cedarConfig);
    environment.jersey().register(commandFileSystem);

    final CommandAnnotationsResource commandAnnotations = new CommandAnnotationsResource(cedarConfig);
    environment.jersey().register(commandAnnotations);

    final CommandOpenResource commandOpen = new CommandOpenResource(cedarConfig);
    environment.jersey().register(commandOpen);

    final CommandVersionResource commandVersion = new CommandVersionResource(cedarConfig);
    environment.jersey().register(commandVersion);

    final CommandSearchResource commandSearch = new CommandSearchResource(cedarConfig);
    environment.jersey().register(commandSearch);

    final CommandCategoriesResource commandCategories = new CommandCategoriesResource(cedarConfig);
    environment.jersey().register(commandCategories);

    final CommandInclusionSubgraphResource commandInclusionSubgraph = new CommandInclusionSubgraphResource(cedarConfig);
    environment.jersey().register(commandInclusionSubgraph);

    final SearchResource search = new SearchResource(cedarConfig);
    environment.jersey().register(search);

    final SearchDeepResource searchDeep = new SearchDeepResource(cedarConfig);
    environment.jersey().register(searchDeep);

    final TemplateFieldsResource fields = new TemplateFieldsResource(cedarConfig);
    environment.jersey().register(fields);

    final TemplateElementsResource elements = new TemplateElementsResource(cedarConfig);
    environment.jersey().register(elements);

    final TemplatesResource templates = new TemplatesResource(cedarConfig);
    environment.jersey().register(templates);

    final TemplateInstancesResource instances = new TemplateInstancesResource(cedarConfig);
    environment.jersey().register(instances);

    final CategoriesResource categories = new CategoriesResource(cedarConfig);
    environment.jersey().register(categories);

    final RecommendResource recommend = new RecommendResource(cedarConfig);
    environment.jersey().register(recommend);

    final ResourceServerHealthCheck healthCheck = new ResourceServerHealthCheck();
    environment.healthChecks().register("message", healthCheck);
  }
}
