package org.metadatacenter.cedar.resource.resources;

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
import org.metadatacenter.id.CedarFilesystemResourceId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FileSystemResource;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerGroup;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
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
import org.metadatacenter.server.security.model.permission.resource.ResourceRole;
import org.metadatacenter.server.security.model.permission.resource.ResourceCapability;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionGroup;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionGroupPermissionPair;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUserPermissionPair;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.PermissionMatrix;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.metadatacenter.util.test.PermissionMatrix.Actor.ANONYMOUS;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OTHER_USER;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OWNER;

/** REST matrix for folder roles, capabilities, and source/destination authorization. */
public class FolderPermissionLevelMatrixTest {

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

  private static String renameBody(String name) {
    return "{\"schema:name\": \"" + name + "\", \"schema:description\": \"changed\"}";
  }

  /**
   * A re-share request that is <em>valid</em>, restating user 1 as owner and asking for nothing.
   *
   * <p>This has to be valid, and it is worth saying why. An earlier version of this test sent
   * {@code {"userPermissions": [], "groupPermissions": []}}, which omits the owner the validator
   * requires. A Viewer was refused with 401 and looked correctly denied, but a Manager got 400 — the
   * body being rejected before the authority to re-share was ever considered. The row therefore
   * asserted nothing about re-sharing at all, while appearing to. Sending a request the validator
   * accepts is what forces the endpoint to answer the actual question: may this grantee change who
   * else can see the folder?
   *
   * <p>Serialized from the real request object rather than hand-written, so the shape cannot drift
   * from what the endpoint expects and silently turn back into a 400.
   */
  private static String resharePermissionsBody() throws Exception {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    return JsonMapper.MAPPER.writeValueAsString(request);
  }

  private static Map<PermissionMatrix.Actor, String> actors;
  private static CedarConfig cedarConfig;
  private static CedarRequestContext user1Context;
  private static CedarRequestContext user2Context;
  private static CedarUser user1;
  private static CedarUser user2;
  private static CedarFolderId user1HomeId;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    actors = Map.of(
        OWNER, TestAuthUtil.getTestUser1AuthHeader(cedarConfig),
        OTHER_USER, TestAuthUtil.getTestUser2AuthHeader(cedarConfig));

    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    user1 = TestAuthUtil.getTestUser1(cedarConfig);
    user2 = TestAuthUtil.getTestUser2(cedarConfig);
    user1Context = CedarRequestContextFactory.fromUser(user1);
    user2Context = CedarRequestContextFactory.fromUser(user2);
    user1HomeId = CedarDataServices.getInstance().getFolderServiceSession(user1Context).findHomeFolderOf().getResourceId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  /** Viewer permits reading and listing, but not editing or grant management. */
  @Test
  public void aViewerGrantProvidesViewerCapabilitiesOnly() throws Exception {
    FolderServerFolder readable = folder("Viewer Readable");
    FolderServerFolder renameable = folder("Viewer Renameable");
    FolderServerFolder deletable = folder("Viewer Deletable");
    FolderServerFolder resharable = folder("Viewer Resharable");
    for (FolderServerFolder f : new FolderServerFolder[]{readable, renameable, deletable, resharable}) {
      grantToUser2(f, ResourceRole.VIEWER);
    }

    PermissionMatrix matrix = matrix();

    matrix.when("GET", path(readable))
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("GET", path(readable) + "/details")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("GET", path(readable) + "/contents")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);

    // A Viewer may read a folder's ACL. Categories have a separate permission model in which the
    // analogous operation requires category WRITE; this row prevents the two models from drifting.
    matrix.when("GET", path(readable) + "/permissions")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);

    // The boundary. Each of these gets its own folder so an unexpected success cannot invalidate the
    // rows around it.
    matrix.when("PUT", path(renameable), renameBody("Viewer Rename Must Fail"))
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);
    matrix.when("DELETE", path(deletable))
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);

    // A Viewer cannot widen access. Anonymous requests are rejected by authentication first.
    matrix.when("PUT", path(resharable) + "/permissions", resharePermissionsBody())
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);

    matrix.verify();

    assertUnchanged(renameable, "Viewer Renameable");
    assertStillExists(deletable);
  }

  /** Editor permits rename and delete, but does not permit grant management. */
  @Test
  public void anEditorGrantProvidesEditingWithoutGrantManagement() throws Exception {
    FolderServerFolder renameable = folder("Editor Renameable");
    FolderServerFolder deletable = folder("Editor Deletable");
    FolderServerFolder resharable = folder("Editor Resharable");
    for (FolderServerFolder f : new FolderServerFolder[]{renameable, deletable, resharable}) {
      grantToUser2(f, ResourceRole.EDITOR);
    }

    PermissionMatrix matrix = matrix();
    matrix.when("PUT", path(renameable), renameBody("Renamed By Editor"))
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 200);
    matrix.when("DELETE", path(deletable))
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 204);
    matrix.when("PUT", path(resharable) + "/permissions", resharePermissionsBody())
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);
    matrix.verify();

    assertNamed(renameable, "Renamed By Editor");
    Assertions.assertTrue(user2Permissions().userHasCapability(
        resharable.getResourceId(), ResourceCapability.UPDATE_RESOURCE));
    Assertions.assertFalse(user2Permissions().userHasCapability(
        resharable.getResourceId(), ResourceCapability.MANAGE_GRANTS));
  }

  /** Manager adds grant management to every Editor capability. */
  @Test
  public void aManagerGrantProvidesEditingAndGrantManagement() throws Exception {
    FolderServerFolder readable = folder("Manager Readable");
    FolderServerFolder renameable = folder("Manager Renameable");
    FolderServerFolder resharable = folder("Manager Resharable");
    for (FolderServerFolder f : new FolderServerFolder[]{readable, renameable, resharable}) {
      grantToUser2(f, ResourceRole.MANAGER);
    }

    PermissionMatrix matrix = matrix();

    // Manager includes Viewer capabilities; these rows confirm that the endpoints apply that rule.
    matrix.when("GET", path(readable))
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("GET", path(readable) + "/contents")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);

    matrix.when("PUT", path(renameable), renameBody("Renamed By Manager"))
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 200);

    matrix.when("PUT", path(resharable) + "/permissions", resharePermissionsBody())
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 200);

    matrix.verify();

    // The rename was allowed, so assert it actually took rather than that nothing changed.
    assertNamed(renameable, "Renamed By Manager");

    // The successful replacement removes the Manager's own grant because the submitted ACL is empty.
    Assertions.assertFalse(
        CedarDataServices.getInstance().getResourcePermissionServiceSession(
                CedarRequestContextFactory.fromUser(user2))
            .userHasCapability(resharable.getResourceId(), ResourceCapability.UPDATE_RESOURCE),
        "the successful Manager ACL replacement should remove the omitted grant");
  }

  /**
   * The same Viewer capabilities, granted through a group rather than to the user directly. Membership
   * resolution is the most indirect route to access in the system and therefore the easiest to get
   * subtly wrong, and it is how sharing is actually used.
   */
  @Test
  public void aGroupViewerGrantBehavesLikeADirectViewerGrant() {
    FolderServerFolder readable = folder("Group Viewer Readable");
    FolderServerFolder renameable = folder("Group Viewer Renameable");

    GroupServiceSession groups = CedarDataServices.getInstance().getGroupServiceSession(user1Context);
    FolderServerGroup group = groups.createGroup("permission-level-matrix-group",
        "Group for the REST-level group grant test");
    Assertions.assertNotNull(group, "the group should be created");

    // Membership updates replace the whole set, so user1 is restated as creator and administrator.
    CedarGroupUsersRequest membership = new CedarGroupUsersRequest();
    membership.getUsers().add(new CedarGroupUserRequest(new ResourcePermissionUser(user1.getId()), true, true));
    membership.getUsers().add(new CedarGroupUserRequest(new ResourcePermissionUser(user2.getId()), false, true));
    BackendCallResult membershipResult = groups.updateGroupUsers(group.getResourceId(), membership);
    Assertions.assertFalse(membershipResult.isError(), "the membership update should succeed");

    for (FolderServerFolder f : new FolderServerFolder[]{readable, renameable}) {
      ResourcePermissionsRequest request = new ResourcePermissionsRequest();
      request.setOwner(new ResourcePermissionUser(user1.getId()));
      request.getGroupPermissions().add(new ResourcePermissionGroupPermissionPair(
          new ResourcePermissionGroup(group.getId()), ResourceRole.VIEWER));
      apply(f, request);
    }

    PermissionMatrix matrix = matrix();

    matrix.when("GET", path(readable))
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("GET", path(readable) + "/contents")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("PUT", path(renameable), renameBody("Group Viewer Rename Must Fail"))
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);

    matrix.verify();

    assertUnchanged(renameable, "Group Viewer Renameable");
  }

  @Test
  public void movingAResourceChecksTheSourceAndDestinationAuthoritiesSeparately() {
    FolderServerFolder sourceFolder = folder("Move Matrix Source Folder");
    FolderServerFolder destination = folder("Move Matrix Destination Folder");
    FolderServerArtifact source = template(sourceFolder.getResourceId(), "Move Matrix Artifact");
    String body = "{\"@id\":\"" + source.getId() + "\",\"targetFolderId\":\""
        + destination.getId() + "\"}";

    grantToUser2(source, ResourceRole.EDITOR);
    grantToUser2(destination, ResourceRole.EDITOR);
    matrix().when("POST", "/command/move-resource-to-folder", body)
        .header("If-Match", "*")
        .expect(OTHER_USER, 403)
        .verify();

    grantToUser2(source, ResourceRole.MANAGER);
    grantToUser2(destination, ResourceRole.VIEWER);
    matrix().when("POST", "/command/move-resource-to-folder", body)
        .header("If-Match", "*")
        .expect(OTHER_USER, 403)
        .verify();

    grantToUser2(destination, ResourceRole.EDITOR);
    matrix().when("POST", "/command/move-resource-to-folder", body)
        .header("If-Match", "*")
        .expect(OTHER_USER, 201)
        .verify();
  }

  // ── fixtures and helpers ───────────────────────────────────────────────────

  private static PermissionMatrix matrix() {
    return new PermissionMatrix("http://localhost:" + SERVER.getLocalPort(), actors);
  }

  private static String path(FolderServerFolder folder) {
    return "/folders/" + URLEncoder.encode(folder.getId(), StandardCharsets.UTF_8);
  }

  private static FolderServerFolder folder(String name) {
    FolderServerFolder newFolder = new FolderServerFolder();
    newFolder.setName(name);
    newFolder.setDescription("Created by FolderPermissionLevelMatrixTest");
    CedarFolderId newFolderId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    FolderServerFolder created = CedarDataServices.getInstance().getFolderServiceSession(user1Context)
        .createFolderAsChildOfId(newFolder, user1HomeId, newFolderId);
    Assertions.assertNotNull(created, "the fixture folder '" + name + "' should be created");
    return created;
  }

  private static FolderServerArtifact template(CedarFolderId parentId, String name) {
    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName(name);
    template.setDescription("Created by FolderPermissionLevelMatrixTest");
    template.setVersion("0.0.1");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    FolderServerArtifact created = CedarDataServices.getInstance()
        .getFolderServiceSession(user1Context).createResourceAsChildOfId(template, parentId);
    Assertions.assertNotNull(created, "the fixture template '" + name + "' should be created");
    return created;
  }

  private static void grantToUser2(FileSystemResource resource, ResourceRole role) {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    request.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), role));
    apply(resource.getResourceId(), request);
  }

  private static ResourcePermissionServiceSession user2Permissions() {
    return CedarDataServices.getInstance().getResourcePermissionServiceSession(user2Context);
  }

  private static void apply(FileSystemResource resource, ResourcePermissionsRequest request) {
    apply(resource.getResourceId(), request);
  }

  private static void apply(CedarFilesystemResourceId resourceId, ResourcePermissionsRequest request) {
    BackendCallResult result = CedarDataServices.getInstance().getResourcePermissionServiceSession(user1Context)
        .updateResourcePermissions(resourceId, request);
    Assertions.assertFalse(result.isError(),
        "the grant should succeed: " + (result.isError() ? result.getFirstErrorMessage() : ""));
  }

  /** Reads the folder back as its owner and checks the name, so a refusal is shown to have done nothing. */
  private static void assertUnchanged(FolderServerFolder folder, String expectedName) {
    assertNamed(folder, expectedName);
  }

  private static void assertNamed(FolderServerFolder folder, String expectedName) {
    FolderServerFolder after = CedarDataServices.getInstance().getFolderServiceSession(user1Context)
        .findFolderById(folder.getResourceId());
    Assertions.assertNotNull(after, "the folder should still exist");
    Assertions.assertEquals(expectedName, after.getName(),
        "the folder's name is not what the grant level should have allowed");
  }

  private static void assertStillExists(FolderServerFolder folder) {
    Assertions.assertNotNull(
        CedarDataServices.getInstance().getFolderServiceSession(user1Context).findFolderById(folder.getResourceId()),
        "a refused DELETE should have left the folder in place");
  }

}
