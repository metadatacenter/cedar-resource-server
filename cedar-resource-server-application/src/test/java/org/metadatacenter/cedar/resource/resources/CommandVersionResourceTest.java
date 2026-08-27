package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Failure classification tests for template-version commands. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CommandVersionResourceTest {

  private static final int ARTIFACT_PORT = 19327;
  private static final int TERMINOLOGY_PORT = 19328;
  private static final String TERMINOLOGY_VERSION_ID = "doid-version-hash";

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19027",
        "CEDAR_RESOURCE_ADMIN_PORT", "19127",
        "CEDAR_RESOURCE_STOP_PORT", "19227",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(ARTIFACT_PORT),
        "CEDAR_TERMINOLOGY_SERVER_HOST", "127.0.0.1",
        "CEDAR_TERMINOLOGY_HTTP_PORT", Integer.toString(TERMINOLOGY_PORT)));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static HttpServer artifactServer;
  private static HttpServer terminologyServer;
  private static String authHeader;
  private static CedarTemplateId templateId;
  private static CedarTemplateId publishTemplateId;
  private static CedarTemplateId failedPublishTemplateId;
  private static CedarTemplateId failedDraftTemplateId;
  private static CedarTemplateId deleteRetryTemplateId;
  private static CedarTemplateId failedCreateTemplateId;
  private static CedarFolderId homeFolderId;
  private static CedarFolderId failedCreateFolderId;
  private static CedarFolderId failedDraftFolderId;
  private static FolderServiceSession folderSession;
  private static ObjectNode storedTemplate;
  private static ObjectNode publishTemplateDocument;
  private static ObjectNode failedPublishTemplateDocument;
  private static ObjectNode currentFailedPublishArtifact;
  private static ObjectNode lastPublishedTemplate;
  private static ObjectNode currentUpdateArtifact;
  private static volatile boolean artifactServerReturnsContent;
  private static volatile boolean publishingArtifact;
  private static volatile boolean failingDraft;
  private static volatile boolean retryingDelete;
  private static volatile boolean failedDraftArtifactPresent;
  private static volatile boolean failingPublish;
  private static volatile boolean publishRollbackUsedReplacementEtag;
  private static volatile int terminologyRequests;
  private static volatile boolean updatingArtifact;
  private static volatile boolean rollbackUsedReplacementEtag;
  private static volatile boolean creatingArtifact;
  private static volatile boolean createdArtifactPresent;
  private static final AtomicInteger COMPENSATING_RESTORES = new AtomicInteger();
  private static final AtomicInteger COMPENSATING_PUBLISH_RESTORES = new AtomicInteger();
  private static final AtomicInteger COMPENSATING_DRAFT_DELETES = new AtomicInteger();
  private static final AtomicInteger DELETE_RETRY_ARTIFACT_CALLS = new AtomicInteger();
  private static final AtomicInteger COMPENSATING_DELETES = new AtomicInteger();

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    artifactServer = HttpServer.create(new InetSocketAddress("127.0.0.1", ARTIFACT_PORT), 0);
    artifactServer.createContext("/", CommandVersionResourceTest::handleArtifactRequest);
    artifactServer.start();
    terminologyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", TERMINOLOGY_PORT), 0);
    terminologyServer.createContext("/", CommandVersionResourceTest::handleTerminologyRequest);
    terminologyServer.start();

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

    CedarRequestContext userContext = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    folderSession = CedarDataServices.getInstance().getFolderServiceSession(userContext);
    homeFolderId = folderSession.findHomeFolderOf().getResourceId();
    failedCreateTemplateId = CedarTemplateId.build(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    FolderServerFolder failedCreateFolder = new FolderServerFolder();
    failedCreateFolder.setName("Create compensation parent");
    failedCreateFolder.setDescription("Removed after the artifact write to make the graph create fail");
    failedCreateFolderId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    Assertions.assertNotNull(
        folderSession.createFolderAsChildOfId(failedCreateFolder, homeFolderId, failedCreateFolderId));
    failedDraftTemplateId = CedarTemplateId.build(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    FolderServerFolder failedDraftFolder = new FolderServerFolder();
    failedDraftFolder.setName("Draft compensation parent");
    failedDraftFolder.setDescription("Removed after the draft artifact write to make graph creation fail");
    failedDraftFolderId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    Assertions.assertNotNull(
        folderSession.createFolderAsChildOfId(failedDraftFolder, homeFolderId, failedDraftFolderId));

    FolderServerTemplate deleteRetryTemplate = new FolderServerTemplate();
    deleteRetryTemplate.setId(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    deleteRetryTemplate.setName("Delete retry fixture");
    deleteRetryTemplate.setDescription("Its artifact is already absent when deletion resumes");
    deleteRetryTemplate.setVersion("1.0.0");
    deleteRetryTemplate.setPublicationStatus("bibo:draft");
    deleteRetryTemplate.setLatestVersion(true);
    deleteRetryTemplate.setLatestDraftVersion(true);
    deleteRetryTemplate.setLatestPublishedVersion(false);
    FolderServerArtifact createdDeleteRetryTemplate =
        folderSession.createResourceAsChildOfId(deleteRetryTemplate, homeFolderId);
    Assertions.assertNotNull(createdDeleteRetryTemplate);
    deleteRetryTemplateId = CedarTemplateId.build(createdDeleteRetryTemplate.getId());

    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName("Version check fixture");
    template.setDescription("Template with an instance, so update checking compares the models");
    template.setVersion("1.0.0");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    FolderServerArtifact createdTemplate = folderSession.createResourceAsChildOfId(template, homeFolderId);
    Assertions.assertNotNull(createdTemplate);
    templateId = CedarTemplateId.build(createdTemplate.getId());

    FolderServerTemplate publishTemplate = new FolderServerTemplate();
    publishTemplate.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    publishTemplate.setName("Freeze-on-publish fixture");
    publishTemplate.setDescription("Uses the configured terminology service when publishing");
    publishTemplate.setVersion("1.0.0");
    publishTemplate.setPublicationStatus("bibo:draft");
    publishTemplate.setLatestVersion(true);
    publishTemplate.setLatestDraftVersion(true);
    publishTemplate.setLatestPublishedVersion(false);
    FolderServerArtifact createdPublishTemplate =
        folderSession.createResourceAsChildOfId(publishTemplate, homeFolderId);
    Assertions.assertNotNull(createdPublishTemplate);
    publishTemplateId = CedarTemplateId.build(createdPublishTemplate.getId());

    FolderServerTemplate failedPublishTemplate = new FolderServerTemplate();
    failedPublishTemplate.setId(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    failedPublishTemplate.setName("Publish compensation fixture");
    failedPublishTemplate.setDescription("Removed from the graph after its publish write");
    failedPublishTemplate.setVersion("1.0.0");
    failedPublishTemplate.setPublicationStatus("bibo:draft");
    failedPublishTemplate.setLatestVersion(true);
    failedPublishTemplate.setLatestDraftVersion(true);
    failedPublishTemplate.setLatestPublishedVersion(false);
    FolderServerArtifact createdFailedPublishTemplate =
        folderSession.createResourceAsChildOfId(failedPublishTemplate, homeFolderId);
    Assertions.assertNotNull(createdFailedPublishTemplate);
    failedPublishTemplateId = CedarTemplateId.build(createdFailedPublishTemplate.getId());

    FolderServerInstance instance = new FolderServerInstance();
    instance.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.INSTANCE));
    instance.setName("Version check instance");
    instance.setDescription("Forces check-update-template to compare definitions");
    instance.setIsBasedOn(templateId);
    Assertions.assertNotNull(folderSession.createResourceAsChildOfId(instance, homeFolderId));
    Assertions.assertEquals(1, folderSession.getNumberOfInstances(templateId));

    storedTemplate = new JsonArtifactRenderer().renderTemplateSchemaArtifact(
        TemplateSchemaArtifact.builder()
            .withJsonLdId(URI.create(templateId.getId()))
            .withName("Version check fixture")
            .build());
    publishTemplateDocument = storedTemplate.deepCopy();
    publishTemplateDocument.put("@id", publishTemplateId.getId());
    publishTemplateDocument.put("schema:name", "Freeze-on-publish fixture");
    publishTemplateDocument.put("pav:version", "1.0.0");
    publishTemplateDocument.put("bibo:status", "bibo:draft");
    publishTemplateDocument.putObject("_valueConstraints")
        .putArray("ontologies")
        .addObject()
        .put("acronym", "DOID");
    failedPublishTemplateDocument = publishTemplateDocument.deepCopy();
    failedPublishTemplateDocument.put("@id", failedPublishTemplateId.getId());
    failedPublishTemplateDocument.put("schema:name", "Publish compensation fixture");
  }

  @BeforeEach
  public void resetArtifactServer() {
    artifactServerReturnsContent = true;
    publishingArtifact = false;
    failingDraft = false;
    retryingDelete = false;
    failedDraftArtifactPresent = false;
    failingPublish = false;
    publishRollbackUsedReplacementEtag = false;
    currentFailedPublishArtifact = failedPublishTemplateDocument.deepCopy();
    terminologyRequests = 0;
    lastPublishedTemplate = null;
    currentUpdateArtifact = storedTemplate.deepCopy();
    updatingArtifact = false;
    rollbackUsedReplacementEtag = false;
    creatingArtifact = false;
    createdArtifactPresent = false;
    COMPENSATING_RESTORES.set(0);
    COMPENSATING_PUBLISH_RESTORES.set(0);
    COMPENSATING_DRAFT_DELETES.set(0);
    DELETE_RETRY_ARTIFACT_CALLS.set(0);
    COMPENSATING_DELETES.set(0);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    if (artifactServer != null) {
      artifactServer.stop(0);
    }
    if (terminologyServer != null) {
      terminologyServer.stop(0);
    }
  }

  @Test
  public void malformedSubmittedTemplateIsAnInternalFailureNotNotFound() throws Exception {
    HttpResponse<String> response = checkUpdateTemplate("[]");

    Assertions.assertEquals(500, response.statusCode(), response.body());
  }

  @Test
  public void validSubmittedTemplateCanBeChecked() throws Exception {
    HttpResponse<String> response = checkUpdateTemplate(storedTemplate.toString());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    Assertions.assertTrue(JsonMapper.MAPPER.readTree(response.body()).get("canBeUpdated").asBoolean());
  }

  @Test
  public void emptyArtifactServerResponseIsNotFound() throws Exception {
    artifactServerReturnsContent = false;

    HttpResponse<String> response = checkUpdateTemplate(storedTemplate.toString());

    Assertions.assertEquals(404, response.statusCode(), response.body());
  }

  @Test
  public void publishUsesTerminologyServerFromCedarConfig() throws Exception {
    publishingArtifact = true;
    String body = "{\"@id\":\"" + publishTemplateId.getId() + "\",\"newVersion\":\"1.0.1\"}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/publish-artifact"))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    Assertions.assertEquals(1, terminologyRequests);
    Assertions.assertNotNull(lastPublishedTemplate);
    Assertions.assertEquals(TERMINOLOGY_VERSION_ID,
        lastPublishedTemplate.path("_valueConstraints").path("ontologies").path(0)
            .path("version").path("id").asText());
  }

  /**
   * A delete retry may find that its first attempt removed the artifact before losing the graph
   * response. A 404 from the artifact server must not stop the remaining Neo4j deletion.
   */
  @Test
  @Order(Integer.MAX_VALUE - 4)
  public void deleteRetryFinishesTheGraphDeleteWhenTheArtifactIsAlreadyAbsent() throws Exception {
    retryingDelete = true;
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/"
            + URLEncoder.encode(deleteRetryTemplateId.getId(), StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .DELETE()
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(204, response.statusCode(), response.body());
    Assertions.assertNull(folderSession.findArtifactById(deleteRetryTemplateId),
        "the resumed delete left the stale graph node behind");
    Assertions.assertEquals(1, DELETE_RETRY_ARTIFACT_CALLS.get());
  }

  /** A draft artifact whose graph node cannot be created must be discarded without demoting its source. */
  @Test
  @Order(Integer.MAX_VALUE - 3)
  public void graphFailureAfterDraftCreateDiscardsTheArtifactAndKeepsTheSourceLatest() throws Exception {
    failingDraft = true;
    String body = "{\"@id\":\"" + failedPublishTemplateId.getId()
        + "\",\"newVersion\":\"1.0.1\",\"folderId\":\"" + failedDraftFolderId.getId()
        + "\",\"propagateSharing\":false,\"newFolderName\":\"\"}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/create-draft-artifact"))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertFalse(failedDraftArtifactPresent,
        "the graphless draft remained in the artifact store");
    Assertions.assertEquals(1, COMPENSATING_DRAFT_DELETES.get());
    Assertions.assertTrue(folderSession.findSchemaArtifactById(failedPublishTemplateId).isLatestVersion(),
        "the source was demoted before its draft reached the graph");
  }

  /** A failed graph publish must put the draft document back without racing a newer edit. */
  @Test
  @Order(Integer.MAX_VALUE - 2)
  public void graphFailureAfterPublishConditionallyRestoresTheDraft() throws Exception {
    failingPublish = true;
    String body = "{\"@id\":\"" + failedPublishTemplateId.getId()
        + "\",\"newVersion\":\"1.0.1\"}";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/publish-artifact"))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(500, response.statusCode(), response.body());
    Assertions.assertEquals(failedPublishTemplateDocument, currentFailedPublishArtifact,
        "the draft document was not restored after the graph publish failed");
    Assertions.assertEquals(1, COMPENSATING_PUBLISH_RESTORES.get());
    Assertions.assertTrue(publishRollbackUsedReplacementEtag,
        "the publish rollback was not guarded by the published document's ETag");
  }

  /**
   * A replacement reaches the artifact store before its metadata reaches Neo4j. Simulate the graph
   * record disappearing in that interval and require the old document to be restored with the ETag
   * of the replacement, so compensation cannot overwrite a still-newer concurrent edit.
   */
  @Test
  @Order(Integer.MAX_VALUE - 1)
  public void graphFailureAfterArtifactUpdateConditionallyRestoresThePreImage() throws Exception {
    updatingArtifact = true;
    ObjectNode replacement = storedTemplate.deepCopy();
    replacement.put("schema:name", "Replacement that must be rolled back");

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates/"
            + URLEncoder.encode(templateId.getId(), StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .header("If-Match", "\"fixture-etag\"")
        .PUT(HttpRequest.BodyPublishers.ofString(replacement.toString()))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(404, response.statusCode(), response.body());
    Assertions.assertEquals(storedTemplate, currentUpdateArtifact,
        "the artifact document was not restored after the graph update failed");
    Assertions.assertEquals(1, COMPENSATING_RESTORES.get());
    Assertions.assertTrue(rollbackUsedReplacementEtag,
        "the rollback was not guarded by the ETag of the document it replaced");
  }

  /**
   * The artifact server commits before the workspace graph does. If the chosen parent disappears in
   * that gap, the failed create must delete the just-created artifact instead of leaving a graphless
   * record that the caller cannot discover or clean up.
   */
  @Test
  @Order(Integer.MAX_VALUE)
  public void graphFailureAfterArtifactCreateCompensatesTheArtifactWrite() throws Exception {
    creatingArtifact = true;
    ObjectNode requestDocument = storedTemplate.deepCopy();
    requestDocument.putNull("@id");
    requestDocument.put("schema:name", "Compensated create fixture");

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/templates?folder_id="
            + URLEncoder.encode(failedCreateFolderId.getId(), StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestDocument.toString()))
        .build();

    HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(400, response.statusCode(), response.body());
    Assertions.assertFalse(createdArtifactPresent,
        "the resource server left the failed create in the artifact store");
    Assertions.assertEquals(1, COMPENSATING_DELETES.get(),
        "the resource server did not issue exactly one compensating artifact delete");
  }

  private static HttpResponse<String> checkUpdateTemplate(String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/check-update-template/"
            + URLEncoder.encode(templateId.getId(), StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    if (retryingDelete && "DELETE".equals(exchange.getRequestMethod())) {
      DELETE_RETRY_ARTIFACT_CALLS.incrementAndGet();
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    if (failingDraft && "GET".equals(exchange.getRequestMethod())) {
      sendArtifactResponse(exchange, failedPublishTemplateDocument, "\"fixture-etag\"");
      return;
    }
    if (failingDraft && "POST".equals(exchange.getRequestMethod())) {
      failedDraftArtifactPresent = true;
      folderSession.deleteFolderById(failedDraftFolderId);
      ObjectNode draft = (ObjectNode) JsonMapper.MAPPER.readTree(requestBody);
      draft.put("@id", failedDraftTemplateId.getId());
      byte[] response = draft.toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set("Location", failedDraftTemplateId.getId());
      exchange.sendResponseHeaders(201, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
      return;
    }
    if (failingDraft && "DELETE".equals(exchange.getRequestMethod())) {
      failedDraftArtifactPresent = false;
      COMPENSATING_DRAFT_DELETES.incrementAndGet();
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    if (failingPublish && "GET".equals(exchange.getRequestMethod())) {
      sendArtifactResponse(exchange, currentFailedPublishArtifact, "\"fixture-etag\"");
      return;
    }
    if (failingPublish && "PUT".equals(exchange.getRequestMethod())) {
      String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
      ObjectNode submitted = (ObjectNode) JsonMapper.MAPPER.readTree(requestBody);
      if ("\"fixture-etag\"".equals(ifMatch)) {
        currentFailedPublishArtifact = submitted;
        folderSession.deleteResourceById(failedPublishTemplateId);
        sendArtifactResponse(exchange, currentFailedPublishArtifact, "\"published-etag\"");
      } else {
        publishRollbackUsedReplacementEtag = "\"published-etag\"".equals(ifMatch);
        currentFailedPublishArtifact = submitted;
        COMPENSATING_PUBLISH_RESTORES.incrementAndGet();
        sendArtifactResponse(exchange, currentFailedPublishArtifact, "\"restored-draft-etag\"");
      }
      return;
    }
    if (updatingArtifact && "GET".equals(exchange.getRequestMethod())) {
      sendArtifactResponse(exchange, currentUpdateArtifact, "\"fixture-etag\"");
      return;
    }
    if (updatingArtifact && "PUT".equals(exchange.getRequestMethod())) {
      String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
      ObjectNode submitted = (ObjectNode) JsonMapper.MAPPER.readTree(requestBody);
      if ("\"fixture-etag\"".equals(ifMatch)) {
        currentUpdateArtifact = submitted;
        folderSession.deleteResourceById(templateId);
        sendArtifactResponse(exchange, currentUpdateArtifact, "\"replacement-etag\"");
      } else {
        rollbackUsedReplacementEtag = "\"replacement-etag\"".equals(ifMatch);
        currentUpdateArtifact = submitted;
        COMPENSATING_RESTORES.incrementAndGet();
        sendArtifactResponse(exchange, currentUpdateArtifact, "\"restored-etag\"");
      }
      return;
    }
    if (creatingArtifact && "POST".equals(exchange.getRequestMethod())) {
      createdArtifactPresent = true;
      folderSession.deleteFolderById(failedCreateFolderId);
      ObjectNode created = storedTemplate.deepCopy();
      created.put("@id", failedCreateTemplateId.getId());
      created.put("schema:name", "Compensated create fixture");
      byte[] response = created.toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set("Location", failedCreateTemplateId.getId());
      exchange.sendResponseHeaders(201, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
      return;
    }
    if (creatingArtifact && "DELETE".equals(exchange.getRequestMethod())) {
      createdArtifactPresent = false;
      COMPENSATING_DELETES.incrementAndGet();
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    if (!artifactServerReturnsContent) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }

    ObjectNode responseDocument;
    if ("PUT".equals(exchange.getRequestMethod())) {
      lastPublishedTemplate = (ObjectNode) JsonMapper.MAPPER.readTree(requestBody);
      responseDocument = lastPublishedTemplate;
    } else {
      responseDocument = publishingArtifact ? publishTemplateDocument : storedTemplate;
      exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
    }
    byte[] response = responseDocument.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private static void sendArtifactResponse(HttpExchange exchange, ObjectNode document, String etag)
      throws IOException {
    byte[] response = document.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("ETag", etag);
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private static void handleTerminologyRequest(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    terminologyRequests++;
    byte[] response = ("{\"id\":\"" + TERMINOLOGY_VERSION_ID + "\"}")
        .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }
}
