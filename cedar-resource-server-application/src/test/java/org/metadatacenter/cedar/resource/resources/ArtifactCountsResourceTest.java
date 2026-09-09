package org.metadatacenter.cedar.resource.resources;

import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.*;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.ArtifactServiceConfig;
import org.metadatacenter.config.environment.CedarEnvironmentSource;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.test.TestAuthUtil;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactCountsResourceTest {
  private static HttpServer artifact;
  private static final AtomicInteger calls = new AtomicInteger();
  private static volatile int status = 200;
  private static volatile String body = "{\"field\":11,\"element\":22,\"template\":33,\"instance\":44}";
  private static volatile String lastPath, lastAuth, lastKey;
  static {
    try {
      artifact = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      artifact.createContext("/", exchange -> {
        calls.incrementAndGet();
        lastPath = exchange.getRequestURI().getPath();
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        lastKey = exchange.getRequestHeaders().getFirst(ArtifactServiceConfig.HEADER);
        exchange.getResponseHeaders().set("Location", "/redirect-must-not-be-followed");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
      });
      artifact.start();
      var env = new HashMap<>(CedarEnvironmentSource.getAll());
      env.putAll(Map.of("CEDAR_RESOURCE_HTTP_PORT", "0", "CEDAR_RESOURCE_ADMIN_PORT", "0",
          "CEDAR_RESOURCE_STOP_PORT", "0", "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
          "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(artifact.getAddress().getPort())));
      CedarEnvironmentSource.setOverride(env);
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));
  private static String admin, normal, key;
  @BeforeAll static void start() throws Exception {
    SERVER.before();
    var config = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(config);
    admin = TestAuthUtil.getAdminUserAuthHeader(config);
    normal = TestAuthUtil.getTestUser1AuthHeader(config);
    key = config.getArtifactService().requireApiKey();
  }
  @AfterAll static void stop() { SERVER.after(); artifact.stop(0); }

  @Test void gatesBeforeReadingAndPreservesCallerWithConfiguredServiceKey() throws Exception {
    int before = calls.get();
    assertEquals(401, get(null).statusCode());
    assertEquals(403, get(normal).statusCode());
    assertEquals(before, calls.get());
    status = 200;
    body = "{\"field\":11,\"element\":22,\"template\":33,\"instance\":44}";
    var result = get(admin);
    assertEquals(200, result.statusCode(), result.body());
    assertEquals(org.metadatacenter.util.json.JsonMapper.STRICT_MAPPER.readTree(body),
        org.metadatacenter.util.json.JsonMapper.STRICT_MAPPER.readTree(result.body()));
    assertEquals("/monitor/artifact-counts", lastPath);
    assertEquals(admin, lastAuth);
    assertEquals(key, lastKey);
    assertEquals("no-store", result.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test void failuresAndRedirectsNeverBecomeCountsOrGetRetried() throws Exception {
    for (int code : List.of(302, 401, 403, 404, 500, 503)) {
      status = code;
      int before = calls.get();
      var result = get(admin);
      assertEquals(503, result.statusCode(), result.body());
      assertEquals(before + 1, calls.get());
      assertFalse(result.body().contains("127.0.0.1"));
    }
    status = 200;
    body = "{}";
    assertEquals(503, get(admin).statusCode());
  }

  private static HttpResponse<String> get(String auth) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVER.getLocalPort() + "/monitor/artifact-counts"));
    if (auth != null) request.header("Authorization", auth);
    request.header(ArtifactServiceConfig.HEADER, "untrusted-caller-supplied-key");
    return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
