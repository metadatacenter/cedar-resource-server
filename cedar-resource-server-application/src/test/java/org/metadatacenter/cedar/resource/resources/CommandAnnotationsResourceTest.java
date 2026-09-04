package org.metadatacenter.cedar.resource.resources;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.metadatacenter.model.folderserver.basic.FolderServerField;
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
import org.metadatacenter.util.ModelUtil;
import org.metadatacenter.util.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Cross-service failure behavior for the DOI command. */
public class CommandAnnotationsResourceTest {

  private static final int ARTIFACT_PORT = 19397;

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(ARTIFACT_PORT)));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final AtomicInteger PUT_REQUESTS = new AtomicInteger();
  private static final Object ARTIFACT_LOCK = new Object();

  private static HttpServer artifactServer;
  private static ExecutorService artifactExecutor;
  private static String authHeader;
  private static CedarUntypedArtifactId artifactId;
  private static CedarRequestContext userContext;
  private static CedarConfig cedarConfig;
  private static ObjectNode currentArtifact;
  private static int currentRevision;
  private static boolean rejectPut;
  private static boolean deleteGraphAfterNextWrite;
  private static String rollbackIfMatch;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", ARTIFACT_PORT), 0);
    artifactServer.createContext("/", CommandAnnotationsResourceTest::handleArtifactRequest);
    artifactExecutor = Executors.newCachedThreadPool();
    artifactServer.setExecutor(artifactExecutor);
    artifactServer.start();

    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
  }

  @BeforeEach
  public void setUpArtifact() {
    FolderServiceSession folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    CedarFolderId homeFolderId = folderSession.findHomeFolderOf().getResourceId();
    FolderServerInstance instance = new FolderServerInstance();
    instance.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.INSTANCE));
    instance.setName("DOI concurrency fixture");
    instance.setDescription("DOI command integration test");
    FolderServerArtifact created = folderSession.createResourceAsChildOfId(instance, homeFolderId);
    Assertions.assertNotNull(created);
    artifactId = CedarUntypedArtifactId.build(created.getId());
    synchronized (ARTIFACT_LOCK) {
      currentArtifact = JsonMapper.MAPPER.createObjectNode();
      currentArtifact.put("@id", artifactId.getId());
      currentRevision = 1;
      rejectPut = false;
      deleteGraphAfterNextWrite = false;
      rollbackIfMatch = null;
      PUT_REQUESTS.set(0);
    }
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    if (artifactServer != null) {
      artifactServer.stop(0);
    }
    if (artifactExecutor != null) {
      artifactExecutor.shutdownNow();
    }
  }

  @Test
  public void artifactFailureDoesNotUpdateGraphDoi() throws Exception {
    rejectPut = true;
    String doi = "10.1234/rejected";
    HttpResponse<String> response = setDoi(doi);
    Assertions.assertEquals(503, response.statusCode(), response.body());
    Assertions.assertTrue(PUT_REQUESTS.get() >= 1, "the artifact PUT should have been attempted");

    FolderServerArtifact graphArtifact = CedarDataServices.getInstance().getFolderServiceSession(userContext)
        .findArtifactById(artifactId);
    Assertions.assertNotNull(graphArtifact);
    Assertions.assertNull(graphArtifact.getDOI(), "the failed artifact write must not be committed to GraphDB");
  }

  @Test
  public void missingNullAndBlankDoiAreRejectedBeforeAnyWrite() throws Exception {
    for (String body : List.of(
        "{\"@id\": \"" + artifactId.getId() + "\"}",
        "{\"@id\": \"" + artifactId.getId() + "\", \"doi\": null}",
        "{\"@id\": \"" + artifactId.getId() + "\", \"doi\": \"   \"}")) {
      HttpResponse<String> response = postDoiCommand(body);
      Assertions.assertEquals(400, response.statusCode(), response.body());
    }

    Assertions.assertEquals(0, PUT_REQUESTS.get());
    Assertions.assertFalse(currentArtifact.has("_annotations"));
    FolderServerArtifact graphArtifact = CedarDataServices.getInstance().getFolderServiceSession(userContext)
        .findArtifactById(artifactId);
    Assertions.assertNull(graphArtifact.getDOI());
  }

  @Test
  public void emptyRequestBodyIsRejectedBeforeAnyWrite() throws Exception {
    HttpResponse<String> response = postDoiCommand("");

    Assertions.assertEquals(400, response.statusCode(), response.body());
    Assertions.assertEquals(0, PUT_REQUESTS.get());
    Assertions.assertFalse(currentArtifact.has("_annotations"));
  }

  @Test
  public void missingNullAndBlankArtifactIdAreRejectedBeforeAnyWrite() throws Exception {
    for (String body : List.of(
        "{\"doi\": \"10.1234/missing-id\"}",
        "{\"@id\": null, \"doi\": \"10.1234/null-id\"}",
        "{\"@id\": \"   \", \"doi\": \"10.1234/blank-id\"}")) {
      HttpResponse<String> response = postDoiCommand(body);
      Assertions.assertEquals(400, response.statusCode(), response.body());
    }

    Assertions.assertEquals(0, PUT_REQUESTS.get());
    Assertions.assertFalse(currentArtifact.has("_annotations"));
  }

  @Test
  public void doiIsRejectedForUnsupportedResourceTypeBeforeArtifactWrite() throws Exception {
    FolderServiceSession folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    CedarFolderId homeFolderId = folderSession.findHomeFolderOf().getResourceId();
    FolderServerField field = new FolderServerField();
    field.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FIELD));
    field.setName("DOI unsupported resource fixture");
    field.setDescription("DOI command unsupported resource integration test");
    FolderServerArtifact created = folderSession.createResourceAsChildOfId(field, homeFolderId);
    Assertions.assertNotNull(created);

    HttpResponse<String> response = postDoiCommand(
        "{\"@id\": \"" + created.getId() + "\", \"doi\": \"10.1234/field\"}");

    Assertions.assertEquals(400, response.statusCode(), response.body());
    Assertions.assertEquals(0, PUT_REQUESTS.get());
    Assertions.assertNull(folderSession.findArtifactById(CedarUntypedArtifactId.build(created.getId())).getDOI());
  }

  @Test
  public void concurrentDifferentDoisCannotBothCommit() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<HttpResponse<String>> first = executor.submit(() -> setDoi("10.1234/first"));
      Future<HttpResponse<String>> second = executor.submit(() -> setDoi("10.1234/second"));
      List<HttpResponse<String>> responses = List.of(first.get(), second.get());

      Assertions.assertEquals(1, responses.stream().filter(r -> r.statusCode() == 200).count(),
          responses.toString());
      Assertions.assertEquals(1, responses.stream().filter(r -> r.statusCode() == 400 || r.statusCode() == 412)
          .count(), responses.toString());

      String documentDoi = ModelUtil.extractDOIFromResource(currentArtifact).getValue();
      FolderServerArtifact graphArtifact = CedarDataServices.getInstance().getFolderServiceSession(userContext)
          .findArtifactById(artifactId);
      Assertions.assertEquals(documentDoi, graphArtifact.getDOI());
      Assertions.assertTrue(documentDoi.equals("10.1234/first") || documentDoi.equals("10.1234/second"));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void repeatingTheSameDoiIsIdempotent() throws Exception {
    Assertions.assertEquals(200, setDoi("10.1234/same").statusCode());
    int revisionAfterFirstWrite = currentRevision;
    int putsAfterFirstWrite = PUT_REQUESTS.get();

    HttpResponse<String> repeated = setDoi("10.1234/same");

    Assertions.assertEquals(200, repeated.statusCode(), repeated.body());
    Assertions.assertEquals(putsAfterFirstWrite, PUT_REQUESTS.get());
    Assertions.assertEquals(revisionAfterFirstWrite, currentRevision,
        "an idempotent DOI retry must not replace the artifact document");
  }

  @Test
  public void graphFailureAfterArtifactWriteRestoresTheDocumentConditionally() throws Exception {
    deleteGraphAfterNextWrite = true;

    HttpResponse<String> response = setDoi("10.1234/rollback");

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertEquals("\"2\"", rollbackIfMatch,
        "rollback must use the ETag returned by the successful DOI write; PUTs=" + PUT_REQUESTS.get());
    Assertions.assertNull(ModelUtil.extractDOIFromResource(currentArtifact).getValue(),
        "the artifact pre-image was not restored");
  }

  private static HttpResponse<String> setDoi(String doi) throws Exception {
    String body = "{\"@id\": \"" + artifactId.getId() + "\", \"doi\": \"" + doi + "\"}";
    return postDoiCommand(body);
  }

  private static HttpResponse<String> postDoiCommand(String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/annotations/doi"))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] response;
    int status;
    if ("PUT".equals(exchange.getRequestMethod())) {
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      synchronized (ARTIFACT_LOCK) {
        int putNumber = PUT_REQUESTS.incrementAndGet();
        String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
        if (rejectPut) {
          status = 503;
          response = "{\"error\":\"artifact write rejected\"}".getBytes(StandardCharsets.UTF_8);
        } else if (!"*".equals(ifMatch) && !("\"" + currentRevision + "\"").equals(ifMatch)) {
          status = 412;
          response = "{\"status\":412}".getBytes(StandardCharsets.UTF_8);
        } else {
          currentArtifact = (ObjectNode) JsonMapper.MAPPER.readTree(requestBody);
          currentRevision++;
          status = 200;
          response = currentArtifact.toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("ETag", "\"" + currentRevision + "\"");
          if (putNumber > 1) {
            rollbackIfMatch = ifMatch;
          }
          if (deleteGraphAfterNextWrite) {
            deleteGraphAfterNextWrite = false;
            CedarDataServices.getInstance().getFolderServiceSession(userContext).deleteResourceById(artifactId);
          }
        }
      }
    } else {
      exchange.getRequestBody().readAllBytes();
      status = 200;
      synchronized (ARTIFACT_LOCK) {
        response = currentArtifact.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("ETag", "\"" + currentRevision + "\"");
      }
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
