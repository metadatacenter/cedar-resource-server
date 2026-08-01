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
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerGroup;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.GroupServiceSession;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.auth.CedarGroupUserRequest;
import org.metadatacenter.server.security.model.auth.CedarGroupUsersRequest;
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
import org.metadatacenter.util.test.PermissionMatrix;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.metadatacenter.util.test.PermissionMatrix.Actor.ANONYMOUS;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OTHER_USER;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OWNER;

/**
 * What a <em>grant</em> actually buys you at the REST layer.
 *
 * <p>The other matrices answer a binary question: may a stranger with no grant touch this at all.
 * That is the easy half, and the half least likely to break, because refusing an unknown caller is
 * the first thing any endpoint does. Controlled sharing is what CEDAR is for, so the interesting
 * question is the middle of the model: given a READ grant, is the recipient held to reading?
 *
 * <p>{@code WorkspacePermissionIntegrationTest} already answers that for the permission
 * <em>model</em> — {@code directUserGrantGivesReadButNotWrite} and
 * {@code groupWriteGrantResolvesThroughMembership} — but it asserts through the graph session and
 * issues no HTTP at all. A correct model does not imply endpoints that consult it correctly: an
 * endpoint can check read where it should check write, or forget to check. That gap is where a
 * privilege escalation would live, and this table is what closes it.
 *
 * <p>Each grant level gets its own folders rather than sharing one, because a row that is allowed
 * changes the fixture: if WRITE turns out to confer rename or delete, a shared fixture would be
 * renamed or destroyed and every later row would then be asserting against a folder that no longer
 * exists. Rows run in insertion order, so that would look like a cascade of unrelated failures.
 *
 * <p>Grants are applied through the graph session rather than over HTTP. The REST grant path is
 * covered elsewhere; using it here would make a failure ambiguous between "the grant did not take"
 * and "the endpoint ignored it".
 */
public class FolderPermissionLevelMatrixTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // distinct from the dev server and from every other booting test class in this module.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19047",
        "CEDAR_RESOURCE_ADMIN_PORT", "19147",
        "CEDAR_RESOURCE_STOP_PORT", "19247",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final String RENAME_BODY =
      "{\"schema:name\": \"Renamed By The Grantee\", \"schema:description\": \"changed\"}";

  /**
   * A re-share request that is <em>valid</em>, restating user 1 as owner and asking for nothing.
   *
   * <p>This has to be valid, and it is worth saying why. An earlier version of this test sent
   * {@code {"userPermissions": [], "groupPermissions": []}}, which omits the owner the validator
   * requires. A reader was refused with 401 and looked correctly denied, but a writer got 400 — the
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
    user1HomeId = CedarDataServices.getFolderServiceSession(user1Context).findHomeFolderOf().getResourceId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  /**
   * A READ grant must buy reading and nothing else. This is the cell that matters most in the whole
   * suite: a READ grant that quietly permits writing turns every act of sharing into a loss of
   * control over the thing shared.
   */
  @Test
  public void aReadGrantBuysReadingOnly() throws Exception {
    FolderServerFolder readable = folder("Read Grant Readable");
    FolderServerFolder renameable = folder("Read Grant Renameable");
    FolderServerFolder deletable = folder("Read Grant Deletable");
    FolderServerFolder resharable = folder("Read Grant Resharable");
    for (FolderServerFolder f : new FolderServerFolder[]{readable, renameable, deletable, resharable}) {
      grantToUser2(f, FilesystemResourcePermission.READ);
    }

    PermissionMatrix matrix = matrix();

    matrix.when("GET", path(readable))
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("GET", path(readable) + "/details")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("GET", path(readable) + "/contents")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);

    // Reading a folder's ACL needs only read access, which differs from a category, where it needs
    // write. Worth pinning rather than assuming the two agree.
    matrix.when("GET", path(readable) + "/permissions")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);

    // The boundary. Each of these gets its own folder so an unexpected success cannot invalidate the
    // rows around it.
    matrix.when("PUT", path(renameable), RENAME_BODY)
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);
    matrix.when("DELETE", path(deletable))
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);

    // Re-sharing is its own permission (CHANGEPERMISSIONS), so a reader must not be able to widen
    // access. An authenticated reader is refused with 403 (permission denial via CedarErrorType
    // .PERMISSION); an anonymous caller is refused with 401 by the authentication layer.
    matrix.when("PUT", path(resharable) + "/permissions", resharePermissionsBody())
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);

    matrix.verify();

    assertUnchanged(renameable, "Read Grant Renameable");
    assertStillExists(deletable);
  }

  /**
   * A WRITE grant buys writing — and, it turns out, re-sharing.
   *
   * <p>Whether WRITE confers the authority to change permissions was an open question when this was
   * written, and the answer is yes. The ACL update is gated by
   * {@code ResourcePermissionRequestValidator.validateWritePermission}, which asks only whether the
   * caller has write access; {@code CHANGEPERMISSIONS} is never consulted anywhere in the codebase,
   * nor is {@code CHANGEOWNER}. Of the six levels the enum declares, two are enforced.
   *
   * <p>Getting to that answer needed a valid request body, which is the subtler lesson here: with a
   * body the validator rejected, a reader was refused 401 and a writer got 400, and the row looked
   * like a passing denial while establishing nothing. See {@link #resharePermissionsBody()}.
   */
  @Test
  public void aWriteGrantBuysWritingAndResharing() throws Exception {
    FolderServerFolder writable = folder("Write Grant Writable");
    FolderServerFolder renameable = folder("Write Grant Renameable");
    FolderServerFolder resharable = folder("Write Grant Resharable");
    for (FolderServerFolder f : new FolderServerFolder[]{writable, renameable, resharable}) {
      grantToUser2(f, FilesystemResourcePermission.WRITE);
    }

    PermissionMatrix matrix = matrix();

    // Write implies read, which the graph-level test asserts; this checks the endpoints agree.
    matrix.when("GET", path(writable))
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("GET", path(writable) + "/contents")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);

    matrix.when("PUT", path(renameable), RENAME_BODY)
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 200);

    // WRITE confers re-sharing. This was written expecting a refusal and is recorded as a 200
    // because that is what the endpoint does, deliberately: the ACL update is gated by
    // ResourcePermissionRequestValidator.validateWritePermission, which asks only
    // userHasWriteAccessToResource. CHANGEPERMISSIONS is not consulted — it is declared in
    // FilesystemResourcePermission and enforced nowhere in the codebase, as is CHANGEOWNER.
    //
    // The consequence is worth stating where someone will read it: granting WRITE on a folder also
    // grants the power to rewrite who else may see it, including revoking the grants of others. So
    // "share so they can edit" is in practice "share so they can re-share". That may be the intended
    // model, but it is not what a six-level permission enum suggests, and it is pinned here so the
    // behaviour is a decision rather than a discovery. If it is ever tightened, this row fails.
    matrix.when("PUT", path(resharable) + "/permissions", resharePermissionsBody())
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 200);

    matrix.verify();

    // The rename was allowed, so assert it actually took rather than that nothing changed.
    assertNamed(renameable, "Renamed By The Grantee");

    // A 200 alone would not distinguish "the ACL was rewritten" from "an empty change was accepted".
    // The body user 2 sent restated user 1 as owner and listed no user permissions, so if it truly
    // took effect user 2's own WRITE grant is now gone — revoked by the grantee, on someone else's
    // folder. Assert that, so the escalation is demonstrated rather than inferred from a status.
    Assertions.assertFalse(
        CedarDataServices.getResourcePermissionServiceSession(
                CedarRequestContextFactory.fromUser(user2))
            .userHasWriteAccessToResource(resharable.getResourceId()),
        "user 2 held only WRITE, yet rewriting the ACL succeeded and removed their own grant: "
            + "the update is gated on write access, not on CHANGEPERMISSIONS");
  }

  /**
   * The same READ contract, granted through a group rather than to the user directly. Membership
   * resolution is the most indirect route to access in the system and therefore the easiest to get
   * subtly wrong, and it is how sharing is actually used.
   */
  @Test
  public void aGroupReadGrantBehavesLikeADirectReadGrant() {
    FolderServerFolder readable = folder("Group Read Readable");
    FolderServerFolder renameable = folder("Group Read Renameable");

    GroupServiceSession groups = CedarDataServices.getGroupServiceSession(user1Context);
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
          new ResourcePermissionGroup(group.getId()), FilesystemResourcePermission.READ));
      apply(f, request);
    }

    PermissionMatrix matrix = matrix();

    matrix.when("GET", path(readable))
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("GET", path(readable) + "/contents")
        .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    matrix.when("PUT", path(renameable), RENAME_BODY)
        .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);

    matrix.verify();

    assertUnchanged(renameable, "Group Read Renameable");
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
    FolderServerFolder created = CedarDataServices.getFolderServiceSession(user1Context)
        .createFolderAsChildOfId(newFolder, user1HomeId, newFolderId);
    Assertions.assertNotNull(created, "the fixture folder '" + name + "' should be created");
    return created;
  }

  private static void grantToUser2(FolderServerFolder folder, FilesystemResourcePermission permission) {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    request.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), permission));
    apply(folder, request);
  }

  private static void apply(FolderServerFolder folder, ResourcePermissionsRequest request) {
    BackendCallResult result = CedarDataServices.getResourcePermissionServiceSession(user1Context)
        .updateResourcePermissions(folder.getResourceId(), request);
    Assertions.assertFalse(result.isError(),
        "the grant should succeed: " + (result.isError() ? result.getFirstErrorMessage() : ""));
  }

  /** Reads the folder back as its owner and checks the name, so a refusal is shown to have done nothing. */
  private static void assertUnchanged(FolderServerFolder folder, String expectedName) {
    assertNamed(folder, expectedName);
  }

  private static void assertNamed(FolderServerFolder folder, String expectedName) {
    FolderServerFolder after = CedarDataServices.getFolderServiceSession(user1Context)
        .findFolderById(folder.getResourceId());
    Assertions.assertNotNull(after, "the folder should still exist");
    Assertions.assertEquals(expectedName, after.getName(),
        "the folder's name is not what the grant level should have allowed");
  }

  private static void assertStillExists(FolderServerFolder folder) {
    Assertions.assertNotNull(
        CedarDataServices.getFolderServiceSession(user1Context).findFolderById(folder.getResourceId()),
        "a refused DELETE should have left the folder in place");
  }

}
