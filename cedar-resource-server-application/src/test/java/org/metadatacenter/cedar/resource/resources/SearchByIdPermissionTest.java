package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.permission.resource.FilesystemResourcePermission;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUserPermissionPair;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
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
 * What {@code GET /search?id=} answers for a resource the caller may not read.
 *
 * <p>An identifier outranks every other selector, so it decides the search type on its own and
 * {@code /search-deep?id=} resolves through the same code. That code is the one search here served
 * from a bare graph lookup: the other search types carry permission conditions in their Cypher and
 * an unreadable resource never reaches the response, while a lookup by identifier has one row and
 * nothing to filter.
 *
 * <p>What takes the place of filtering is redaction. The row survives, carrying its identifier and
 * its type and nothing else, and reports that the active user cannot read it. These tests pin that:
 * the name, description, owner and timestamps of an unshared resource must not appear in the answer
 * given to anyone else, and a read grant must bring them back.
 */
public class SearchByIdPermissionTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // assigned by the OS, so they cannot collide with the dev server or another test in this module.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  /** Distinctive enough that asserting on its absence from a response body means something. */
  private static final String SECRET_NAME = "SBI unshared template Vercingetorix";
  private static final String SECRET_DESCRIPTION = "SBI unshared description Alesia";
  private static final String SECRET_FOLDER_NAME = "SBI unshared folder Gergovia";

  private static CedarConfig cedarConfig;
  private static CedarUser user1;
  private static CedarUser user2;
  private static CedarRequestContext user1Context;
  private static String authHeaderUser1;
  private static String authHeaderUser2;
  private static String authHeaderAdmin;

  private static String unsharedTemplateId;
  private static String unsharedFolderId;
  private static String sharedTemplateId;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    authHeaderUser2 = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);
    authHeaderAdmin = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);

    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    user1 = TestAuthUtil.getTestUser1(cedarConfig);
    user2 = TestAuthUtil.getTestUser2(cedarConfig);
    user1Context = CedarRequestContextFactory.fromUser(user1);

    FolderServiceSession folderSession = CedarDataServices.getInstance().getFolderServiceSession(user1Context);
    CedarFolderId homeId = folderSession.findHomeFolderOf().getResourceId();

    unsharedTemplateId = createTemplate(folderSession, homeId, SECRET_NAME, SECRET_DESCRIPTION).getId();

    FolderServerArtifact shared = createTemplate(folderSession, homeId, "SBI shared template", "SBI shared description");
    grantReadToUser2(shared);
    sharedTemplateId = shared.getId();

    FolderServerFolder folder = new FolderServerFolder();
    folder.setName(SECRET_FOLDER_NAME);
    folder.setDescription("Created by SearchByIdPermissionTest");
    CedarFolderId newFolderId = CedarFolderId.build(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FOLDER));
    unsharedFolderId = folderSession.createFolderAsChildOfId(folder, homeId, newFolderId).getId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @Test
  public void theOwnerReadsTheWholeRow() throws Exception {
    JsonNode row = singleRow(search("/search", unsharedTemplateId, authHeaderUser1));
    Assertions.assertTrue(row.path("activeUserCanRead").asBoolean(), row.toString());
    Assertions.assertEquals(SECRET_NAME, row.path("schema:name").asText(), row.toString());
    Assertions.assertEquals(user1.getId(), row.path("ownedBy").asText(), row.toString());
  }

  @Test
  public void anotherUserReadsNothingButTheIdentifierAndTheType() throws Exception {
    HttpResponse<String> response = search("/search", unsharedTemplateId, authHeaderUser2);
    assertRedacted(response, unsharedTemplateId, CedarResourceType.Types.TEMPLATE);
    Assertions.assertFalse(response.body().contains(SECRET_NAME), response.body());
    Assertions.assertFalse(response.body().contains(SECRET_DESCRIPTION), response.body());
    Assertions.assertFalse(response.body().contains(user1.getId()), response.body());
  }

  /**
   * The identifier decides the search type before any other parameter is read, so the deep search
   * reaches the same lookup and must redact it the same way.
   */
  @Test
  public void theDeepSearchRedactsTheSameRow() throws Exception {
    HttpResponse<String> response = search("/search-deep", unsharedTemplateId, authHeaderUser2);
    assertRedacted(response, unsharedTemplateId, CedarResourceType.Types.TEMPLATE);
    Assertions.assertFalse(response.body().contains(SECRET_NAME), response.body());
  }

  /**
   * A folder resolves through the second half of the same lookup, which was equally bare. Someone
   * else's home folder and its children are named after them, so a folder name is worth no less
   * than an artifact's.
   */
  @Test
  public void anUnreadableFolderIsRedactedToo() throws Exception {
    HttpResponse<String> response = search("/search", unsharedFolderId, authHeaderUser2);
    assertRedacted(response, unsharedFolderId, CedarResourceType.Types.FOLDER);
    Assertions.assertFalse(response.body().contains(SECRET_FOLDER_NAME), response.body());
  }

  @Test
  public void aReadGrantBringsTheWholeRowBack() throws Exception {
    JsonNode row = singleRow(search("/search", sharedTemplateId, authHeaderUser2));
    Assertions.assertTrue(row.path("activeUserCanRead").asBoolean(), row.toString());
    Assertions.assertEquals("SBI shared template", row.path("schema:name").asText(), row.toString());
  }

  /**
   * {@code READ_NOT_READABLE_NODE} waives the check, exactly as it drops the Cypher conditions for
   * every other search this endpoint serves.
   */
  @Test
  public void aFilesystemAdministratorReadsTheWholeRow() throws Exception {
    JsonNode row = singleRow(search("/search", unsharedTemplateId, authHeaderAdmin));
    Assertions.assertTrue(row.path("activeUserCanRead").asBoolean(), row.toString());
    Assertions.assertEquals(SECRET_NAME, row.path("schema:name").asText(), row.toString());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static HttpResponse<String> search(String path, String id, String authHeader) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path
            + "?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8)))
        .header("Authorization", authHeader)
        .GET()
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** The one row the lookup answers with, after asserting that there is exactly one. */
  private static JsonNode singleRow(HttpResponse<String> response) throws Exception {
    Assertions.assertEquals(200, response.statusCode(), response.body());
    JsonNode body = JsonMapper.MAPPER.readTree(response.body());
    Assertions.assertEquals(1, body.path("resources").size(), response.body());
    return body.path("resources").get(0);
  }

  /**
   * The row states that the resource exists, under the identifier the caller already supplied, and
   * says nothing else about it.
   */
  private static void assertRedacted(HttpResponse<String> response, String id, String resourceType) throws Exception {
    JsonNode row = singleRow(response);
    Assertions.assertEquals(id, row.path("@id").asText(), row.toString());
    Assertions.assertEquals(resourceType, row.path("resourceType").asText(), row.toString());
    Assertions.assertFalse(row.path("activeUserCanRead").asBoolean(), row.toString());
    for (String leaked : new String[]{"schema:name", "schema:description", "ownedBy", "pav:createdBy",
        "oslc:modifiedBy", "pav:createdOn", "pav:lastUpdatedOn", "pav:version", "bibo:status"}) {
      Assertions.assertTrue(row.path(leaked).isMissingNode() || row.path(leaked).isNull(),
          "the redacted row still carries " + leaked + ": " + row);
    }
  }

  private static FolderServerArtifact createTemplate(FolderServiceSession folderSession, CedarFolderId parentId,
                                                     String name, String description) {
    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName(name);
    template.setDescription(description);
    template.setVersion("1.0.0");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    FolderServerArtifact created = folderSession.createResourceAsChildOfId(template, parentId);
    Assertions.assertNotNull(created, "the fixture template should have been created");
    return created;
  }

  private static void grantReadToUser2(FolderServerArtifact artifact) {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    request.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), FilesystemResourcePermission.READ));
    BackendCallResult result = CedarDataServices.getInstance().getResourcePermissionServiceSession(user1Context)
        .updateResourcePermissions(artifact.getResourceId(), request);
    Assertions.assertFalse(result.isError(), "the grant should succeed");
  }
}
