package org.metadatacenter.cedar.resource.resources;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Cross-service failure behavior for the DOI command. */
public class CommandAnnotationsResourceTest {

  private static final int ARTIFACT_PORT = 19397;

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19097",
        "CEDAR_RESOURCE_ADMIN_PORT", "19197",
        "CEDAR_RESOURCE_STOP_PORT", "19297",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(ARTIFACT_PORT)));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final AtomicInteger PUT_REQUESTS = new AtomicInteger();

  private static HttpServer artifactServer;
  private static String authHeader;
  private static CedarUntypedArtifactId artifactId;
  private static CedarRequestContext userContext;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", ARTIFACT_PORT), 0);
    artifactServer.createContext("/", CommandAnnotationsResourceTest::handleArtifactRequest);
    artifactServer.start();

    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    FolderServiceSession folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    CedarFolderId homeFolderId = folderSession.findHomeFolderOf().getResourceId();
    FolderServerInstance instance = new FolderServerInstance();
    instance.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.INSTANCE));
    instance.setName("DOI failure fixture");
    instance.setDescription("The graph must remain unchanged when the artifact PUT fails");
    FolderServerArtifact created = folderSession.createResourceAsChildOfId(instance, homeFolderId);
    Assertions.assertNotNull(created);
    artifactId = CedarUntypedArtifactId.build(created.getId());
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    if (artifactServer != null) {
      artifactServer.stop(0);
    }
  }

  @Test
  public void artifactFailureDoesNotUpdateGraphDoi() throws Exception {
    String doi = "10.1234/rejected";
    String body = "{\"@id\": \"" + artifactId.getId() + "\", \"doi\": \"" + doi + "\"}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/annotations/doi"))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(503, response.statusCode(), response.body());
    Assertions.assertTrue(PUT_REQUESTS.get() >= 1, "the artifact PUT should have been attempted");

    FolderServerArtifact graphArtifact = CedarDataServices.getInstance().getFolderServiceSession(userContext)
        .findArtifactById(artifactId);
    Assertions.assertNotNull(graphArtifact);
    Assertions.assertNull(graphArtifact.getDOI(), "the failed artifact write must not be committed to GraphDB");
  }

  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    byte[] response;
    int status;
    if ("PUT".equals(exchange.getRequestMethod())) {
      PUT_REQUESTS.incrementAndGet();
      status = 503;
      response = "{\"error\":\"artifact write rejected\"}".getBytes(StandardCharsets.UTF_8);
    } else {
      status = 200;
      response = "{}".getBytes(StandardCharsets.UTF_8);
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
