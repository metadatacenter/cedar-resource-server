package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The resource server's true-or-false parameters accept true and false in any case and refuse every
 * other value, which Jersey used to read as false.
 *
 * <p>The conversion belongs to the shared library, and these tests show that the booted application
 * applies it to its own routes. The routes are ones whose answer shows the value they were given. On
 * a write, {@code compact} is refused whenever it is present, so that refusal shows that a value was
 * converted. A {@code verbatim} write with a YAML body is refused only when the flag is true.
 */
@Tag("integration")
public class TrueOrFalseParameterTest {

  static {
    // Must run before the test support boots the server. OS-assigned ports, so the test instance never
    // collides with a running dev server. Redis, the artifact server and OpenSearch are on dead ports.
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

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final String TEMPLATE_ID = "https://repo.metadatacenter.org/templates/7b8977ed-c4d7-4c29-b202-53e38a41c726";
  private static final String YAML_TEMPLATE = "type: template\nname: X\n";
  private static final String WRITE_GUARD = "not supported on write operations";
  private static final String VERBATIM_GUARD = "verbatimWriteRefused";

  private static String authHeaderUser1;
  private static String authHeaderAdmin;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    authHeaderAdmin = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  /** A value that is neither true nor false never reaches the route, on a write or on a read. */
  @ParameterizedTest
  @ValueSource(strings = {"abc", "1", "yes", ""})
  public void aCompactValueThatIsNeitherTrueNorFalseIsRefused(String value) throws Exception {
    assertRefused(postTemplate("?compact=" + value), "compact");
    assertRefused(getTemplate("?compact=" + value), "compact");
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", "false", "False"})
  public void compactInAnyCaseReachesTheWriteGuard(String value) throws Exception {
    HttpResponse<String> response = postTemplate("?compact=" + value);
    Assertions.assertEquals(400, response.statusCode(), response.body());
    Assertions.assertTrue(response.body().contains(WRITE_GUARD), response.body());
  }

  /** An absent compact is an empty Optional, as it was, and the write guard lets the request through. */
  @Test
  public void anAbsentCompactPassesTheWriteGuard() throws Exception {
    HttpResponse<String> response = postTemplate("");
    Assertions.assertFalse(response.body().contains(WRITE_GUARD), response.body());
    Assertions.assertFalse(response.body().contains("must be true or false"), response.body());
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "1", "yes", ""})
  public void aVerbatimValueThatIsNeitherTrueNorFalseIsRefused(String value) throws Exception {
    assertRefused(putTemplate("?verbatim=" + value), "verbatim");
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", "True"})
  public void verbatimTrueInAnyCaseIsAVerbatimWrite(String value) throws Exception {
    HttpResponse<String> response = putTemplate("?verbatim=" + value);
    Assertions.assertEquals(400, response.statusCode(), response.body());
    Assertions.assertTrue(response.body().contains(VERBATIM_GUARD), response.body());
  }

  /** With verbatim false in any case, or absent, the PUT is an ordinary write, which accepts a YAML body. */
  @ParameterizedTest
  @ValueSource(strings = {"?verbatim=false", "?verbatim=FALSE", "?verbatim=False", ""})
  public void verbatimFalseOrAbsentIsAnOrdinaryWrite(String query) throws Exception {
    HttpResponse<String> response = putTemplate(query);
    Assertions.assertFalse(response.body().contains(VERBATIM_GUARD), response.body());
    Assertions.assertFalse(response.body().contains("must be true or false"), response.body());
  }

  private static void assertRefused(HttpResponse<String> response, String parameter) throws Exception {
    Assertions.assertEquals(400, response.statusCode(), response.body());
    JsonNode error = JsonMapper.STRICT_MAPPER.readTree(response.body());
    Assertions.assertEquals("BAD_REQUEST", error.path("status").asText(), response.body());
    Assertions.assertEquals("The query parameter " + parameter + " must be true or false",
        error.path("message").asText(), response.body());
    Assertions.assertTrue(error.path("errorKey").isNull(), response.body());
  }

  private static HttpResponse<String> postTemplate(String query) throws Exception {
    return send(HttpRequest.newBuilder(uri("/templates" + query))
        .header("Authorization", authHeaderUser1)
        .header("Content-Type", "application/yaml")
        .POST(HttpRequest.BodyPublishers.ofString(YAML_TEMPLATE)));
  }

  private static HttpResponse<String> getTemplate(String query) throws Exception {
    return send(HttpRequest.newBuilder(uri("/templates/" + URLEncoder.encode(TEMPLATE_ID, StandardCharsets.UTF_8)
        + query)).header("Authorization", authHeaderUser1).GET());
  }

  private static HttpResponse<String> putTemplate(String query) throws Exception {
    return send(HttpRequest.newBuilder(uri("/templates/" + URLEncoder.encode(TEMPLATE_ID, StandardCharsets.UTF_8)
        + query))
        .header("Authorization", authHeaderAdmin)
        .header("Content-Type", "application/yaml")
        .PUT(HttpRequest.BodyPublishers.ofString(YAML_TEMPLATE)));
  }

  private static URI uri(String path) {
    return URI.create("http://localhost:" + SERVER.getLocalPort() + path);
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return CLIENT.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
  }

}
