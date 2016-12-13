package org.metadatacenter.cedar.resource;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.health.ResourceServerHealthCheck;
import org.metadatacenter.cedar.resource.resources.*;
import org.metadatacenter.cedar.resource.search.SearchService;
import org.metadatacenter.cedar.resource.search.elasticsearch.ElasticsearchService;
import org.metadatacenter.cedar.util.dw.CedarAssertionExceptionMapper;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.ElasticsearchConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.AuthorizationKeycloakAndApiKeyResolver;
import org.metadatacenter.server.security.IAuthorizationResolver;
import org.metadatacenter.server.security.KeycloakDeploymentProvider;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import java.util.EnumSet;

import static org.eclipse.jetty.servlets.CrossOriginFilter.*;

public class ResourceServerApplication extends Application<ResourceServerConfiguration> {

  private static CedarConfig cedarConfig;
  private static UserService userService;
  private static SearchService searchService;

  public static void main(String[] args) throws Exception {
    new ResourceServerApplication().run(args);
  }

  @Override
  public String getName() {
    return "resource-server";
  }

  @Override
  public void initialize(Bootstrap<ResourceServerConfiguration> bootstrap) {
    // Init Keycloak
    KeycloakDeploymentProvider.getInstance();
    // Init Authorization Resolver
    IAuthorizationResolver authResolver = new AuthorizationKeycloakAndApiKeyResolver();
    Authorization.setAuthorizationResolver(authResolver);
    Authorization.setUserService(CedarDataServices.getUserService());

    cedarConfig = CedarConfig.getInstance();

    userService = new UserServiceMongoDB(
        cedarConfig.getMongoConfig().getDatabaseName(),
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


    CommandResource.injectUserService(userService);
    SearchResource.injectSearchService(searchService);
  }

  @Override
  public void run(ResourceServerConfiguration configuration, Environment environment) {
    final IndexResource index = new IndexResource();
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

    final TemplateFieldsResource fields = new TemplateFieldsResource(cedarConfig);
    environment.jersey().register(fields);

    final TemplateElementsResource elements = new TemplateElementsResource(cedarConfig);
    environment.jersey().register(elements);

    final TemplatesResource templates = new TemplatesResource(cedarConfig);
    environment.jersey().register(templates);

    final TemplateInstancesResource instances = new TemplateInstancesResource(cedarConfig);
    environment.jersey().register(instances);

    final ResourceServerHealthCheck healthCheck = new ResourceServerHealthCheck();
    environment.healthChecks().register("errorMessage", healthCheck);

    environment.jersey().register(new CedarAssertionExceptionMapper());

    // Enable CORS headers
    final FilterRegistration.Dynamic cors = environment.servlets().addFilter("CORS", CrossOriginFilter.class);

    // Configure CORS parameters
    cors.setInitParameter(ALLOWED_ORIGINS_PARAM, "*");
    cors.setInitParameter(ALLOWED_HEADERS_PARAM,
        "X-Requested-With,Content-Type,Accept,Origin,Referer,User-Agent,Authorization");
    cors.setInitParameter(ALLOWED_METHODS_PARAM, "OPTIONS,GET,PUT,POST,DELETE,HEAD,PATCH");

    // Add URL mapping
    cors.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");

  }
}
