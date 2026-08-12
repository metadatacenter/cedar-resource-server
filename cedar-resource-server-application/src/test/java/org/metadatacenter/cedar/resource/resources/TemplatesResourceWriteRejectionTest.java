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

/**
 * Endpoint tests for the write-path rejections of the compact YAML convenience. Both rejections
 * fire before the request would reach the artifact server, so these tests run against the
 * booted application with no live backend.
 */
public class TemplatesResourceWriteRejectionTest {

  static {
    // Must run before the test support boots the server. Alternate ports, so the test
    // instance never collides with a running dev server; Redis on a dead port, since queue
    // writes are best-effort.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19007",
        "CEDAR_RESOURCE_ADMIN_PORT", "19107",
        "CEDAR_RESOURCE_STOP_PORT", "19207",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String authHeaderUser1;
  private static String authHeaderAdmin;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    authHeaderAdmin = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  private HttpResponse<String> post(String query, String body, String contentType) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates" + query))
        .header("Authorization", authHeaderUser1)
        .header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> putTemplate(String id, String query, String body, String contentType,
                                           String authHeader) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/"
            + URLEncoder.encode(id, StandardCharsets.UTF_8) + query))
        .header("Authorization", authHeader)
        .header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void compactParameterIsRejectedOnWrite() throws Exception {
    HttpResponse<String> response = post("?compact=true", "type: template\nname: X\n", "application/yaml");
    Assertions.assertEquals(400, response.statusCode());
    Assertions.assertTrue(response.body().contains("not supported on write operations"));
  }

  @Test
  public void compactParameterIsRejectedOnWriteEvenWhenFalse() throws Exception {
    HttpResponse<String> response = post("?compact=false", "type: template\nname: X\n", "application/yaml");
    Assertions.assertEquals(400, response.statusCode());
  }

  @Test
  public void compactYamlBodyIsRejectedOnWrite() throws Exception {
    // The compact-form signature: an id with none of the system-recorded keys
    String compactBody = "type: template\n"
        + "name: Study\n"
        + "id: https://repo.metadatacenter.org/templates/7b8977ed-c4d7-4c29-b202-53e38a41c723\n"
        + "children:\n"
        + "- key: study-name\n"
        + "  type: text-field\n"
        + "  name: Study Name\n";
    HttpResponse<String> response = post("", compactBody, "application/yaml");
    Assertions.assertEquals(400, response.statusCode());
    Assertions.assertTrue(response.body().contains("compact form"));
  }

  @Test
  public void verbatimYamlBodyIsRejectedBeforeItCanBeTranscoded() throws Exception {
    String id = "https://repo.metadatacenter.org/templates/7b8977ed-c4d7-4c29-b202-53e38a41c723";
    HttpResponse<String> response = putTemplate(id, "?verbatim=true", "type: template\nname: X\n",
        "application/yaml", authHeaderAdmin);

    Assertions.assertEquals(400, response.statusCode());
    Assertions.assertTrue(response.body().contains("verbatimWriteRefused"));
    Assertions.assertTrue(response.body().contains("needs a JSON body"));
  }

}
