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
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerGroup;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.GroupServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.auth.CedarGroupUserRequest;
import org.metadatacenter.server.security.model.auth.CedarGroupUsersRequest;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarNodeUserPermission;
import org.metadatacenter.server.security.model.permission.resource.FilesystemResourcePermission;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionGroup;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionGroupPermissionPair;
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
import java.util.List;
import java.util.Map;

/**
 * Sharing, performed the way a user performs it: over HTTP.
 *
 * <p>The permission-level matrices establish what a grant buys, but they apply the grant through the
 * graph session, so they assert the <em>effect</em> of sharing and never the <em>act</em>. Every
 * {@code PUT .../permissions} elsewhere in these suites sends a body that grants nothing — either
 * owner-less, to be refused, or owner-only with empty lists. Nothing asserted the round trip: the
 * owner asks for user 2 to have READ, the endpoint accepts, and user 2 then has READ.
 *
 * <p>That gap matters because four validators run only on the HTTP path and were never exercised with
 * real content: {@code validateAndSetUsers}, {@code validateUserUniqueness},
 * {@code validateOwnerAndUserCollision} and {@code validateOwnerSetPermission}. A request whose
 * requested level was misread — dropped, downgraded, or upgraded — would be invisible to a test that
 * bypasses parsing by granting through the session.
 *
 * <p>The upgrade is the case worth guarding. WRITE confers re-sharing (see
 * {@link FolderPermissionLevelMatrixTest}), so a body asking for READ that quietly produced WRITE would
 * hand the recipient the power to widen access further, and every existing test would still pass. So
 * each row here asserts the level is exactly what was asked for, in both directions: the recipient has
 * what was granted, and does <em>not</em> have what was not. Asserting only that a reader can read
 * would pass just as happily if they had been given write.
 *
 * <p>The user-grant cases read the ACL back through the API and deserialize it into the type the
 * endpoint returns, rather than matching text, so a field rename cannot make the assertion silently
 * vacuous. The group case cannot do that, and the reason is a finding in itself:
 * {@code CedarNodePermissionsWithExtract} is not deserializable once it contains a group, because
 * {@code CedarGroupExtract} declares only a two-argument constructor and no default one, while
 * {@code CedarUserExtract} has both. The response is half round-trippable through the shared model, so
 * that case reads a tree instead. Recorded on the roadmap rather than papered over.
 */
public class SharingRoundTripTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // distinct from the dev server and from every other booting test class in this module.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19067",
        "CEDAR_RESOURCE_ADMIN_PORT", "19167",
        "CEDAR_RESOURCE_STOP_PORT", "19267",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static CedarConfig cedarConfig;
  private static CedarUser user1;
  private static CedarUser user2;
  private static CedarRequestContext user1Context;
  private static CedarRequestContext user2Context;
  private static CedarFolderId user1HomeId;
  private static String user1Header;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    user1 = TestAuthUtil.getTestUser1(cedarConfig);
    user2 = TestAuthUtil.getTestUser2(cedarConfig);
    user1Header = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    user1Context = CedarRequestContextFactory.fromUser(user1);
    user2Context = CedarRequestContextFactory.fromUser(user2);
    user1HomeId = CedarDataServices.getFolderServiceSession(user1Context).findHomeFolderOf().getResourceId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  /** Sharing at READ must produce READ, and nothing more. */
  @Test
  public void sharingAtReadGrantsReadAndNotWrite() throws Exception {
    shareAndVerify(FilesystemResourcePermission.READ, "Share Read Folder");
  }

  /** Sharing at WRITE must produce WRITE — and, because write implies read, read as well. */
  @Test
  public void sharingAtWriteGrantsWrite() throws Exception {
    shareAndVerify(FilesystemResourcePermission.WRITE, "Share Write Folder");
  }

  /**
   * Sharing with a group over HTTP, so the group half of the request body is parsed too. The member
   * gains exactly what the group was granted.
   */
  @Test
  public void sharingWithAGroupGrantsThroughMembership() throws Exception {
    FolderServerFolder folder = folder("Share Group Folder");
    FolderServerGroup group = group("sharing-round-trip-group");

    ResourcePermissionsRequest request = ownedByUser1();
    request.getGroupPermissions().add(new ResourcePermissionGroupPermissionPair(
        new ResourcePermissionGroup(group.getId()), FilesystemResourcePermission.READ));

    HttpResponse<String> shared = send("PUT", permissionsPath(folder),
        JsonMapper.MAPPER.writeValueAsString(request), user1Header);
    Assertions.assertEquals(200, shared.statusCode(), "sharing with a group should succeed: " + shared.body());

    // Read back as a tree rather than as CedarNodePermissionsWithExtract, because that type cannot be
    // deserialized when the ACL contains a group: CedarGroupExtract declares only a two-argument
    // constructor and no default one, so Jackson refuses it, while CedarUserExtract has both and works.
    // The response is therefore only half round-trippable through the shared model, which is recorded
    // on the roadmap rather than worked around silently here.
    JsonNode acl = JsonMapper.MAPPER.readTree(rawAcl(folder));
    JsonNode groupGrants = acl.path("groupPermissions");
    Assertions.assertEquals(1, groupGrants.size(),
        "the ACL should hold exactly the one group grant that was asked for: " + acl);
    Assertions.assertEquals(FilesystemResourcePermission.READ.getValue(),
        groupGrants.get(0).path("permission").asText(),
        "the group's granted level is not the one that was requested: " + acl);
    Assertions.assertEquals(group.getId(), groupGrants.get(0).path("group").path("@id").asText(),
        "the grant is recorded against the wrong group: " + acl);

    // The member gains read through the group, and no more than that.
    Assertions.assertTrue(user2Permissions().userHasReadAccessToResource(folder.getResourceId()),
        "a member of a group granted READ over HTTP should have read access");
    Assertions.assertFalse(user2Permissions().userHasWriteAccessToResource(folder.getResourceId()),
        "a group READ grant made over HTTP must not confer write access");
  }

  /**
   * The three request shapes the HTTP validators exist to refuse. None of them is reachable when a
   * grant is applied through the session, which is why they were untested.
   */
  @Test
  public void malformedSharingRequestsAreRefused() throws Exception {
    FolderServerFolder folder = folder("Share Rejection Folder");

    // No owner. The validator requires one, since only an unchanged owner needs no transfer authority.
    String ownerless = "{\"userPermissions\": [], \"groupPermissions\": []}";
    expectRefusal(folder, ownerless, "a request without an owner");

    // The same user twice, which leaves the intended level ambiguous.
    ResourcePermissionsRequest duplicate = ownedByUser1();
    duplicate.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), FilesystemResourcePermission.READ));
    duplicate.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), FilesystemResourcePermission.WRITE));
    expectRefusal(folder, JsonMapper.MAPPER.writeValueAsString(duplicate), "a request naming one user twice");

    // The owner also listed as a grantee, which would say two things about the same person.
    ResourcePermissionsRequest collision = ownedByUser1();
    collision.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user1.getId()), FilesystemResourcePermission.READ));
    expectRefusal(folder, JsonMapper.MAPPER.writeValueAsString(collision),
        "a request listing the owner as a grantee");

    // None of the refusals may have changed anything: user 2 still has no access at all.
    Assertions.assertFalse(user2Permissions().userHasReadAccessToResource(folder.getResourceId()),
        "a refused sharing request must not have granted anything");
  }

  // ── the round trip ─────────────────────────────────────────────────────────

  /**
   * Shares the folder with user 2 at the given level over HTTP, then checks the result three ways: the
   * endpoint accepted it, the ACL it serves back names exactly that level, and user 2's effective
   * access is exactly what that level implies — no more.
   */
  private void shareAndVerify(FilesystemResourcePermission level, String folderName) throws Exception {
    FolderServerFolder folder = folder(folderName);

    Assertions.assertFalse(user2Permissions().userHasReadAccessToResource(folder.getResourceId()),
        "user 2 should start with no access, or the test proves nothing");

    ResourcePermissionsRequest request = ownedByUser1();
    request.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), level));

    HttpResponse<String> shared = send("PUT", permissionsPath(folder),
        JsonMapper.MAPPER.writeValueAsString(request), user1Header);
    Assertions.assertEquals(200, shared.statusCode(),
        "sharing at " + level + " should succeed: " + shared.body());

    // What the API says it did.
    CedarNodePermissionsWithExtract acl = readAcl(folder);
    List<CedarNodeUserPermission> granted = acl.getUserPermissions();
    Assertions.assertEquals(1, granted.size(),
        "the ACL should hold exactly the one grant that was asked for, but holds " + granted.size());
    Assertions.assertEquals(user2.getId(), granted.get(0).getUser().getId(),
        "the grant is recorded against the wrong user");
    Assertions.assertEquals(level, granted.get(0).getPermission(),
        "the recorded level is not the one that was requested — a silently altered grant");
    Assertions.assertEquals(user1.getId(), acl.getOwner().getId(),
        "sharing must not have changed the owner");

    // What the graph actually enforces. The negative half is the point: asserting only that a reader
    // can read would pass just as well if READ had been quietly turned into WRITE.
    boolean expectWrite = level == FilesystemResourcePermission.WRITE;
    Assertions.assertTrue(user2Permissions().userHasReadAccessToResource(folder.getResourceId()),
        "a grant of " + level + " should confer read access");
    Assertions.assertEquals(expectWrite, user2Permissions().userHasWriteAccessToResource(folder.getResourceId()),
        "a grant of " + level + " conferred the wrong write access");
    Assertions.assertFalse(user2Permissions().userIsOwnerOfResource(folder.getResourceId()),
        "sharing must never confer ownership");
  }

  private void expectRefusal(FolderServerFolder folder, String body, String description) throws Exception {
    HttpResponse<String> response = send("PUT", permissionsPath(folder), body, user1Header);
    Assertions.assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
        description + " should have been refused with a 4xx, but answered "
            + response.statusCode() + ": " + response.body());
  }

  // ── fixtures and helpers ───────────────────────────────────────────────────

  private static ResourcePermissionsRequest ownedByUser1() {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    return request;
  }

  private static String permissionsPath(FolderServerFolder folder) {
    return "/folders/" + URLEncoder.encode(folder.getId(), StandardCharsets.UTF_8) + "/permissions";
  }

  /** The ACL as the endpoint serves it, deserialized into the type it returns. */
  private static CedarNodePermissionsWithExtract readAcl(FolderServerFolder folder) throws Exception {
    return JsonMapper.MAPPER.readValue(rawAcl(folder), CedarNodePermissionsWithExtract.class);
  }

  /** The ACL as raw JSON, for the group case the typed read cannot handle. */
  private static String rawAcl(FolderServerFolder folder) throws Exception {
    HttpResponse<String> response = send("GET", permissionsPath(folder), null, user1Header);
    Assertions.assertEquals(200, response.statusCode(),
        "the owner should be able to read the ACL back: " + response.body());
    return response.body();
  }

  private static FolderServerFolder folder(String name) {
    FolderServerFolder newFolder = new FolderServerFolder();
    newFolder.setName(name);
    newFolder.setDescription("Created by SharingRoundTripTest");
    CedarFolderId newFolderId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    FolderServerFolder created = CedarDataServices.getFolderServiceSession(user1Context)
        .createFolderAsChildOfId(newFolder, user1HomeId, newFolderId);
    Assertions.assertNotNull(created, "the fixture folder should be created");
    return created;
  }

  private static FolderServerGroup group(String name) {
    GroupServiceSession groups = CedarDataServices.getGroupServiceSession(user1Context);
    FolderServerGroup created = groups.createGroup(name, "Created by SharingRoundTripTest");
    Assertions.assertNotNull(created, "the fixture group should be created");

    CedarGroupUsersRequest membership = new CedarGroupUsersRequest();
    membership.getUsers().add(new CedarGroupUserRequest(new ResourcePermissionUser(user1.getId()), true, true));
    membership.getUsers().add(new CedarGroupUserRequest(new ResourcePermissionUser(user2.getId()), false, true));
    BackendCallResult result = groups.updateGroupUsers(created.getResourceId(), membership);
    Assertions.assertFalse(result.isError(), "the fixture membership should be established");
    return created;
  }

  private static ResourcePermissionServiceSession user2Permissions() {
    return CedarDataServices.getResourcePermissionServiceSession(user2Context);
  }

  private static HttpResponse<String> send(String method, String path, String body, String authHeader)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json");
    if (authHeader != null) {
      builder.header("Authorization", authHeader);
    }
    builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

}
