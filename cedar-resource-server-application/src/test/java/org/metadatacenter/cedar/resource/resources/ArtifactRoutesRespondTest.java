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
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes every endpoint of the four artifact-type resource classes on the booted application,
 * unauthenticated. Every route is expected to answer
 * 401 (the authentication assertion fires before anything else on all of them); a 404 or 405
 * would mean the route vanished or changed verb, which is exactly the regression this test
 * exists to catch before the parameterization refactor.
 *
 * The route list is driven by the same reflection helper the snapshot test uses, so the two
 * tests can never disagree about what the surface is.
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

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static final Pattern PATH_TEMPLATE_VARIABLE = Pattern.compile("\\{([^}]+)}");

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
  public void everyDeclaredRouteRejectsUnauthenticatedRequests() throws Exception {
    StringBuilder failures = new StringBuilder();

    for (ArtifactResourceSurface.Endpoint endpoint : ArtifactResourceSurface.endpoints()) {
      String key = endpoint.key();
      int status = probe(endpoint);

      if (status == 404 || status == 405) {
        failures.append(key).append(": got ").append(status)
            .append(" - the route vanished or changed verb\n");
        continue;
      }
      if (status != 401) {
        failures.append(key).append(": expected 401 but got ").append(status).append('\n');
      }
    }

    Assertions.assertEquals(0, failures.length(), "Route responses diverged from the authentication contract:\n" + failures);
  }

  private int probe(ArtifactResourceSurface.Endpoint endpoint) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + substitutePathParameters(endpoint.fullPath)));
    if (endpoint.verb.equals("POST") || endpoint.verb.equals("PUT")) {
      builder.header("Content-Type", "application/json");
      builder.method(endpoint.verb, HttpRequest.BodyPublishers.ofString("{}"));
    } else {
      builder.method(endpoint.verb, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    return response.statusCode();
  }

  /**
   * Replaces every path template variable with a syntactically plausible URL-encoded CEDAR
   * artifact id, derived from the route's own root segment (e.g. the template_element_id
   * becomes an encoded https://repo.metadatacenter.org/template-elements/... id).
   */
  private String substitutePathParameters(String pathTemplate) {
    String root = pathTemplate.substring(1, pathTemplate.indexOf('/', 1) > 0 ? pathTemplate.indexOf('/', 1) : pathTemplate.length());
    String plausibleId = "https://repo.metadatacenter.org/" + root + "/8bc64ab5-df6b-48c8-8c61-6c016245918e";
    String encodedId = URLEncoder.encode(plausibleId, StandardCharsets.UTF_8);
    Matcher matcher = PATH_TEMPLATE_VARIABLE.matcher(pathTemplate);
    return matcher.replaceAll(Matcher.quoteReplacement(encodedId));
  }

}
