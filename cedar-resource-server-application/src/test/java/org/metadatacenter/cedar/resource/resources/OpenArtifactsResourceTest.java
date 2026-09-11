package org.metadatacenter.cedar.resource.resources;

import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.*;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.ArtifactServiceConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.*;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpenArtifactsResourceTest {
  private static HttpServer artifact;
  private static final AtomicInteger calls = new AtomicInteger();
  private static final AtomicInteger upstreamStatus = new AtomicInteger(200);
  private static volatile String lastPath, lastAuth, lastServiceKey;
  static {
    try {
      artifact = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      artifact.createContext("/", exchange -> {
        calls.incrementAndGet();
        lastPath = exchange.getRequestURI().getRawPath();
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        lastServiceKey = exchange.getRequestHeaders().getFirst(ArtifactServiceConfig.HEADER);
        byte[] body = "{\"schema:name\":\"Open fixture\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(upstreamStatus.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      artifact.start();
      EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
          "CEDAR_RESOURCE_HTTP_PORT", "0", "CEDAR_RESOURCE_ADMIN_PORT", "0", "CEDAR_RESOURCE_STOP_PORT", "0",
          "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
          "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(artifact.getAddress().getPort()),
          "CEDAR_REDIS_PERSISTENT_PORT", "1"));
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static CedarConfig config;
  private static FolderServiceSession folders;
  private static CedarFolderId home, openChild;
  private static String ownerAuth;

  @BeforeAll static void start() throws Exception {
    SERVER.before();
    config = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(config);
    EmbeddedCedarNeo4j.seed(config);
    var user = TestAuthUtil.getTestUser1(config);
    ownerAuth = user.getFirstApiKeyAuthHeader();
    folders = CedarDataServices.getInstance().getFolderServiceSession(CedarRequestContextFactory.fromUser(user));
    home = folders.findHomeFolderOf().getResourceId();
    FolderServerFolder ancestor = folder(home);
    assertTrue(folders.setOpen(ancestor.getResourceId()));
    openChild = folder(ancestor.getResourceId()).getResourceId();
  }
  @AfterAll static void stop() { SERVER.after(); artifact.stop(0); }

  @Test void everyFamilyUsesOnlyExplicitOrInheritedOpennessRegardlessOfCaller() throws Exception {
    upstreamStatus.set(200);
    for (CedarResourceType type : List.of(CedarResourceType.TEMPLATE, CedarResourceType.ELEMENT,
        CedarResourceType.FIELD, CedarResourceType.INSTANCE)) {
      FolderServerArtifact privateArtifact = create(type, home);
      String id = privateArtifact.getId();
      for (String auth : Arrays.asList(null, ownerAuth, "apiKey invalid")) {
        int before = calls.get();
        assertEquals(401, get(type, id, auth).statusCode(), "Private artifacts stay private even for their owner");
        assertEquals(before, calls.get(), "Deny before fetching a document");
      }
      assertTrue(folders.setOpen(org.metadatacenter.id.CedarArtifactId.build(id, type)));
      for (String auth : Arrays.asList(null, ownerAuth, "apiKey invalid")) {
        var response = get(type, id, auth);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(config.getArtifactService().getApiKey(), lastServiceKey);
        assertNotEquals(ownerAuth, lastAuth);
        assertNotEquals("apiKey invalid", lastAuth);
        assertNotNull(lastAuth);
      }
      String bare = id.substring(id.lastIndexOf('/') + 1);
      assertEquals(200, get(type, bare, null).statusCode());
      assertEquals("/" + type.getPrefix() + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8), lastPath);
      assertTrue(folders.setNotOpen(org.metadatacenter.id.CedarArtifactId.build(id, type)));
      assertEquals(401, get(type, id, ownerAuth).statusCode(), "Revocation is immediately effective");

      var inherited = create(type, openChild);
      assertEquals(200, get(type, inherited.getId(), null).statusCode());
      int before = calls.get();
      assertEquals(404, get(type, UUID.randomUUID().toString(), ownerAuth).statusCode());
      assertEquals(before, calls.get());
    }
  }

  @Test void preservesArtifactMissingAndUnavailableWithoutRetry() throws Exception {
    var open = create(CedarResourceType.TEMPLATE, openChild);
    for (int code : List.of(404, 503)) {
      upstreamStatus.set(code);
      int before = calls.get();
      var response = get(CedarResourceType.TEMPLATE, open.getId(), null);
      assertEquals(code, response.statusCode(), response.body());
      assertEquals(before + 1, calls.get());
    }
    upstreamStatus.set(200);
  }

  private static FolderServerFolder folder(CedarFolderId parent) {
    var folder = new FolderServerFolder();
    folder.setName("Open test folder");
    return folders.createFolderAsChildOfId(folder, parent,
        config.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class));
  }

  private static FolderServerArtifact create(CedarResourceType type, CedarFolderId parent) {
    FolderServerArtifact value = switch (type) {
      case TEMPLATE -> new FolderServerTemplate();
      case ELEMENT -> new FolderServerElement();
      case FIELD -> new FolderServerField();
      case INSTANCE -> new FolderServerInstance();
      default -> throw new IllegalArgumentException();
    };
    value.setId(config.getLinkedDataUtil().buildNewLinkedDataId(type));
    value.setName("Open fixture");
    value.setDescription("Open fixture");
    if (value instanceof FolderServerSchemaArtifact schema) {
      schema.setVersion("1.0.0");
      schema.setPublicationStatus("bibo:draft");
      schema.setLatestVersion(true);
      schema.setLatestDraftVersion(true);
      schema.setLatestPublishedVersion(false);
    }
    assertNotNull(folders.createResourceAsChildOfId(value, parent));
    return value;
  }

  private static HttpResponse<String> get(CedarResourceType type, String id, String auth) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVER.getLocalPort()
        + "/open/" + type.getPrefix() + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8)));
    if (auth != null) request.header("Authorization", auth);
    return CLIENT.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
