package org.metadatacenter.cedar.resource.resources;

import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.RouteSurface;
import org.metadatacenter.util.test.TestAuthUtil;

import java.util.List;
import java.util.Map;

/**
 * Probes every authenticated endpoint registered by the booted application. Every route is expected to answer
 * 401 (the authentication assertion fires before anything else on all of them); a 404 or 405
 * would mean the route vanished or changed verb. The class inventory comes from Jersey's runtime
 * registrations, so registering a resource automatically puts its endpoints under this check.
 */
public class ArtifactRoutesRespondTest {

  static {
    // Must run before the test support boots the server. Alternate ports, distinct from the
    // dev server and from the other booting test classes; Redis on a dead port, since queue
    // writes are best-effort.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19008",
        "CEDAR_RESOURCE_ADMIN_PORT", "19108",
        "CEDAR_RESOURCE_STOP_PORT", "19208",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @Test
  public void everyRegisteredAuthenticatedRouteRejectsUnauthenticatedRequests() {
    org.glassfish.jersey.server.ResourceConfig resourceConfig =
        SERVER.getEnvironment().jersey().getResourceConfig();
    List<Object> registeredComponents = new java.util.ArrayList<>();
    registeredComponents.addAll(resourceConfig.getInstances());
    registeredComponents.addAll(resourceConfig.getSingletons());
    registeredComponents.addAll(resourceConfig.getClasses());
    registeredComponents.addAll(resourceConfig.getResources());
    List<Class<?>> registeredResources = RouteSurface.registeredResourceClasses(
        registeredComponents,
        "org.metadatacenter.cedar.resource.resources").stream()
        .filter(resourceClass -> !resourceClass.getSimpleName().equals("IndexResource"))
        .toList();

    Assertions.assertTrue(registeredResources.size() > 4,
        "the runtime-derived inventory should include resources beyond the four artifact classes: "
            + registeredResources);
    RouteSurface.assertEveryRouteAnswers(
        "http://localhost:" + SERVER.getLocalPort(),
        RouteSurface.endpoints(registeredResources),
        401);
  }

}
