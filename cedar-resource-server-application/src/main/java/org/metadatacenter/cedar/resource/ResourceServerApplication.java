package org.metadatacenter.cedar.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.core.CedarAssertionExceptionMapper;
import org.metadatacenter.cedar.resource.health.ResourceServerHealthCheck;
import org.metadatacenter.cedar.resource.resources.*;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.AuthorizationKeycloakAndApiKeyResolver;
import org.metadatacenter.server.security.IAuthorizationResolver;
import org.metadatacenter.server.security.KeycloakDeploymentProvider;
import org.metadatacenter.server.service.TemplateElementService;
import org.metadatacenter.server.service.TemplateFieldService;
import org.metadatacenter.server.service.TemplateInstanceService;
import org.metadatacenter.server.service.TemplateService;
import org.metadatacenter.server.service.mongodb.TemplateElementServiceMongoDB;
import org.metadatacenter.server.service.mongodb.TemplateFieldServiceMongoDB;
import org.metadatacenter.server.service.mongodb.TemplateInstanceServiceMongoDB;
import org.metadatacenter.server.service.mongodb.TemplateServiceMongoDB;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import java.util.EnumSet;

import static org.eclipse.jetty.servlets.CrossOriginFilter.*;

public class ResourceServerApplication extends Application<ResourceServerConfiguration> {

  protected static CedarConfig cedarConfig;

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

  }

  @Override
  public void run(ResourceServerConfiguration configuration, Environment environment) {
    final IndexResource index = new IndexResource();
    environment.jersey().register(index);

    final FolderResource folders = new FolderResource(cedarConfig, templateFieldService);
    environment.jersey().register(folders);

    final FolderContentsResource folderContents = new FolderContentsResource(cedarConfig, templateFieldService);
    environment.jersey().register(folderContents);

    final UserResource user = new UserResource(cedarConfig, templateFieldService);
    environment.jersey().register(user);

    final CommandResource command = new CommandResource(cedarConfig, templateFieldService);
    environment.jersey().register(command);

    final SearchResource search = new SearchResource(cedarConfig, templateFieldService);
    environment.jersey().register(search);

    final TemplateFieldsResource fields = new TemplateFieldsResource(cedarConfig, templateFieldService);
    environment.jersey().register(fields);

    final TemplateElementsResource elements = new TemplateElementsResource(cedarConfig, templateElementService,
        templateFieldService);
    environment.jersey().register(elements);

    final TemplatesResource templates = new TemplatesResource(cedarConfig, templateService, templateFieldService);
    environment.jersey().register(templates);

    final TemplateInstancesResource instances = new TemplateInstancesResource(cedarConfig, templateInstanceService);
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
