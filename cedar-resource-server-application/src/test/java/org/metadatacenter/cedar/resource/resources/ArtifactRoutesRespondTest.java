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
import org.metadatacenter.cedar.util.dw.CedarMicroserviceIndexResource;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.RouteSurface;
import org.metadatacenter.util.test.TestAuthUtil;
import org.metadatacenter.util.test.TestHttpClient;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Probes every authenticated endpoint registered by the booted application. Every route is expected to answer
 * 401 (the authentication assertion fires before anything else on all of them); a 404 or 405
 * would mean the route vanished or changed verb. The class inventory comes from Jersey's runtime
 * registrations, so registering a resource automatically puts its endpoints under this check.
 *
 * <p>The same inventory is probed again with a value no identifier can take in each path parameter.
 * Without credentials every route still answers 401. With them, every route that names an artifact or
 * a folder answers 400.
 */
public class ArtifactRoutesRespondTest {

  static {
    // Must run before the test support boots the server. OS-assigned ports, distinct from the
    // dev server and from the other booting test classes; Redis on a dead port, since queue
    // writes are best-effort. The artifact server is on a dead port too, so a probe that passes a
    // route's checks fails rather than reaching a running artifact server.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_HTTP_PORT", "1",
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1",
        "CEDAR_OPENSEARCH_REST_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static String authHeaderUser1;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);
    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @Test
  public void everyRegisteredAuthenticatedRouteRejectsUnauthenticatedRequests() {
    List<Class<?>> registeredResources = registeredAuthenticatedResources();
    Assertions.assertTrue(registeredResources.size() > 4,
        "the runtime-derived inventory should include resources beyond the four artifact classes: "
            + registeredResources);
    RouteSurface.assertEveryRouteAnswers(
        "http://localhost:" + SERVER.getLocalPort(),
        RouteSurface.endpoints(registeredResources),
        401);
  }

  /**
   * A route decides authentication before it resolves the identifier in its path. Each route that takes
   * a path parameter is sent a value no identifier can take, and without credentials it answers 401, not
   * the 400 an authenticated caller receives.
   */
  @Test
  public void everyRouteDecidesAuthenticationBeforeItResolvesItsIdentifier() throws Exception {
    List<RouteSurface.Endpoint> routes = RouteSurface.endpoints(registeredAuthenticatedResources()).stream()
        .filter(endpoint -> endpoint.fullPath.contains("{"))
        .toList();
    Assertions.assertTrue(routes.stream().anyMatch(endpoint -> endpoint.key().equals("PUT /templates/{template_id}")),
        "the routes with a path parameter should include the template write: " + routes);
    List<String> divergences = new ArrayList<>();
    for (RouteSurface.Endpoint endpoint : routes) {
      HttpResponse<String> response = sendWithUnresolvableIdentifier(endpoint, null);
      if (response.statusCode() != 401) {
        divergences.add(endpoint + ": expected 401 but got " + response.statusCode() + " " + response.body());
      }
    }
    Assertions.assertEquals(List.of(), divergences);
  }

  /**
   * An authenticated caller who names an artifact or folder by a value no identifier can take is refused
   * with 400 invalidInput.
   */
  @Test
  public void anAuthenticatedCallersUnresolvableIdentifierIsRefusedWith400() throws Exception {
    List<RouteSurface.Endpoint> routes = RouteSurface.endpoints(registeredAuthenticatedResources()).stream()
        .filter(endpoint -> PATH_VARIABLE.matcher(endpoint.fullPath).results()
            .anyMatch(variable -> RESOURCE_IDENTIFIERS.contains(variable.group(1))))
        .toList();
    for (String route : List.of("PUT /templates/{template_id}", "GET /folders/{folder_id}/contents",
        "GET /folders/{folder_id}/contents-extract", "GET /folders/{folder_id}/details")) {
      Assertions.assertTrue(routes.stream().anyMatch(endpoint -> endpoint.key().equals(route)), route);
    }
    List<String> divergences = new ArrayList<>();
    for (RouteSurface.Endpoint endpoint : routes) {
      HttpResponse<String> response = sendWithUnresolvableIdentifier(endpoint, authHeaderUser1);
      String errorKey = response.statusCode() == 400
          ? JsonMapper.STRICT_MAPPER.readTree(response.body()).path("errorKey").asText() : "";
      if (response.statusCode() != 400 || !errorKey.equals("invalidInput")) {
        divergences.add(endpoint + ": expected 400 invalidInput but got " + response.statusCode() + " "
            + response.body());
      }
    }
    Assertions.assertEquals(List.of(), divergences);
  }

  private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

  /** The path parameters that name an artifact or a folder. */
  private static final Set<String> RESOURCE_IDENTIFIERS = Set.of("folder_id", "template_id", "template_element_id",
      "template_field_id", "template_instance_id");

  /**
   * Sends one request at a route with every path parameter set to a value no identifier can take, with
   * the given credentials or none.
   */
  private static HttpResponse<String> sendWithUnresolvableIdentifier(RouteSurface.Endpoint endpoint,
                                                                     String authorization) throws Exception {
    String path = PATH_VARIABLE.matcher(endpoint.fullPath).replaceAll("not-an-identifier");
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVER.getLocalPort() + path));
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    if (Set.of("POST", "PUT", "PATCH").contains(endpoint.verb)) {
      builder.header("Content-Type", RouteSurface.contentTypeFor(endpoint));
      builder.method(endpoint.verb, HttpRequest.BodyPublishers.ofString("{}"));
    } else {
      builder.method(endpoint.verb, HttpRequest.BodyPublishers.noBody());
    }
    return TestHttpClient.send(builder.build());
  }

  private static List<Class<?>> registeredAuthenticatedResources() {
    org.glassfish.jersey.server.ResourceConfig resourceConfig =
        SERVER.getEnvironment().jersey().getResourceConfig();
    List<Object> registeredComponents = new java.util.ArrayList<>();
    registeredComponents.addAll(resourceConfig.getInstances());
    registeredComponents.addAll(resourceConfig.getSingletons());
    registeredComponents.addAll(resourceConfig.getClasses());
    registeredComponents.addAll(resourceConfig.getResources());
    return RouteSurface.registeredResourceClasses(
        registeredComponents,
        "org.metadatacenter.cedar.resource.resources").stream()
        .filter(resourceClass -> !CedarMicroserviceIndexResource.class.isAssignableFrom(resourceClass))
        // Anonymous reads have their own complete openness matrix, including supplied owner credentials.
        .filter(resourceClass -> resourceClass != OpenArtifactsResource.class)
        .toList();
  }

}
