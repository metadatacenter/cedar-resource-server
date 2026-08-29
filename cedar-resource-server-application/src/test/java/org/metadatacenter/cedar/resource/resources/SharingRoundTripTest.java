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
import org.metadatacenter.id.CedarFilesystemResourceId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerElement;
import org.metadatacenter.model.folderserver.basic.FolderServerField;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sharing and ownership transfer, performed the way a user performs them: over HTTP.
 *
 * <p>Both live here because both are the same request — {@code PUT .../permissions} carries the whole
 * permission set, owner included, so giving someone read access and giving them the resource outright
 * differ only in which field of the body changes. Keeping them in one class also keeps one server boot
 * rather than two, which this module is short of: see the note on the class below.
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
 * <p>Every case reads the ACL back through the API and deserializes it into the type the endpoint
 * returns, rather than matching text, so a field rename cannot make the assertion silently vacuous.
 * Writing this test is what found that the group case could not do that: a permissions response
 * containing a group grant was undeserializable, because {@code CedarGroupExtract} had no no-argument
 * constructor while {@code CedarUserExtract} did. That is fixed, and the typed read below is its
 * regression test.
 */
public class SharingRoundTripTest {

  // A note for whoever adds the next test class here. This module runs its tests in one shared JVM,
  // and each class that boots a server also creates a Neo4j driver whose Netty event-loop threads are
  // not reclaimed between classes. Nine such classes exhausted the JVM: a later one failed with
  // "failed to create a child event loop", an exhaustion error that appears only in the full run and
  // never when the class is run alone, and which names whichever class happened to boot last rather
  // than the cause. Eight is the number that currently passes. If you need another, merge into an
  // existing class as this one merges sharing with ownership, or take up the roadmap item that fixes
  // it properly by isolating forks or closing the drivers.
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
  private static String user2Header;

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
    user2Header = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);
    user1Context = CedarRequestContextFactory.fromUser(user1);
    user2Context = CedarRequestContextFactory.fromUser(user2);
    user1HomeId = CedarDataServices.getInstance().getFolderServiceSession(user1Context).findHomeFolderOf().getResourceId();
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

    // Read back through the typed model, which also stands as the regression test for the fix that
    // made it possible: CedarGroupExtract had no no-argument constructor, so deserializing a
    // permissions response containing a group grant failed outright. If that constructor is ever
    // removed, this line throws rather than quietly falling back to text matching.
    CedarNodePermissionsWithExtract acl = readAcl(folder);
    Assertions.assertEquals(1, acl.getGroupPermissions().size(),
        "the ACL should hold exactly the one group grant that was asked for");
    Assertions.assertEquals(FilesystemResourcePermission.READ, acl.getGroupPermissions().get(0).getPermission(),
        "the group's granted level is not the one that was requested");
    Assertions.assertEquals(group.getId(), acl.getGroupPermissions().get(0).getGroup().getId(),
        "the grant is recorded against the wrong group");

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


  // ── ownership: who may hand a resource over ───────────────────────────────

  /**
   * A WRITE grantee may rewrite the ACL but must not be able to write themselves into the owner slot.
   * Checked on a folder and on every artifact type, because each reaches the shared validator through
   * its own resource class and could in principle skip it.
   */
  @Test
  public void aWriteGranteeCannotTakeOwnership() throws Exception {
    List<Target> targets = new ArrayList<>();
    targets.add(folderTarget("Ownership Theft Folder"));
    targets.addAll(artifactTargets("theft"));

    for (Target target : targets) {
      grantToUser2(target, FilesystemResourcePermission.WRITE);
      Assertions.assertTrue(user2Permissions().userHasWriteAccessToResource(target.id()),
          "the WRITE grant on the " + target.label() + " should have taken, or the test proves nothing");

      // User 2 asks to become the owner, keeping their own write grant.
      ResourcePermissionsRequest theft = new ResourcePermissionsRequest();
      theft.setOwner(new ResourcePermissionUser(user2.getId()));
      theft.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
          new ResourcePermissionUser(user2.getId()), FilesystemResourcePermission.WRITE));

      HttpResponse<String> attempt = send("PUT", target.permissionsPath(),
          JsonMapper.MAPPER.writeValueAsString(theft), user2Header);
      Assertions.assertTrue(attempt.statusCode() >= 400,
          "a WRITE grantee taking ownership of the " + target.label() + " should be refused, but got "
              + attempt.statusCode() + ": " + attempt.body());

      // The status is not the whole story. Confirm the graph still names user 1 as owner.
      Assertions.assertTrue(user1Permissions().userIsOwnerOfResource(target.id()),
          "the " + target.label() + " changed hands despite the refusal");
      Assertions.assertFalse(user2Permissions().userIsOwnerOfResource(target.id()),
          "the WRITE grantee became owner of the " + target.label() + " despite the refusal");
    }
  }

  /**
   * A READ grantee is refused earlier — they cannot update the ACL at all — but assert it, because
   * "refused for a different reason" is still the answer that matters here.
   */
  @Test
  public void aReadGranteeCannotTakeOwnership() throws Exception {
    Target target = folderTarget("Ownership Read Grantee Folder");
    grantToUser2(target, FilesystemResourcePermission.READ);

    ResourcePermissionsRequest theft = new ResourcePermissionsRequest();
    theft.setOwner(new ResourcePermissionUser(user2.getId()));

    HttpResponse<String> attempt = send("PUT", target.permissionsPath(),
        JsonMapper.MAPPER.writeValueAsString(theft), user2Header);
    Assertions.assertTrue(attempt.statusCode() >= 400,
        "a READ grantee taking ownership should be refused, but got " + attempt.statusCode());
    Assertions.assertTrue(user1Permissions().userIsOwnerOfResource(target.id()),
        "the folder changed hands despite the refusal");
  }

  /**
   * The owner may hand a resource over — and keeps reaching it afterwards, if it stays in their tree.
   * The owner field moves; effective access does not, because the donor still owns the parent and
   * permissions inherit downwards.
   */
  @Test
  public void transferMovesOwnershipButNotInheritedAccess() throws Exception {
    Target target = folderTarget("Ownership Transfer Folder");

    // Transfer to user 2, listing nobody else — the shape a caller writes when thinking only about who
    // should own it next.
    ResourcePermissionsRequest transfer = new ResourcePermissionsRequest();
    transfer.setOwner(new ResourcePermissionUser(user2.getId()));

    HttpResponse<String> handover = send("PUT", target.permissionsPath(),
        JsonMapper.MAPPER.writeValueAsString(transfer), user1Header);
    Assertions.assertEquals(200, handover.statusCode(),
        "the owner should be able to transfer ownership: " + handover.body());

    Assertions.assertTrue(user2Permissions().userIsOwnerOfResource(target.id()),
        "user 2 should own the folder after the transfer");
    Assertions.assertTrue(user2Permissions().userHasWriteAccessToResource(target.id()),
        "the new owner should have write access");

    Assertions.assertFalse(user1Permissions().userIsOwnerOfResource(target.id()),
        "the previous owner should no longer be the owner");

    // But they have not lost access, and this is the part worth knowing. The request listed no user
    // permissions, so nothing was granted back to user 1 directly — yet the folder still sits inside
    // user 1's home folder, which user 1 still owns, and permissions inherit down
    // (WorkspacePermissionInheritanceIntegrationTest.readGrantOnTopFolderReachesEveryDescendant). So
    // transferring ownership of something inside your own tree moves the owner field without moving
    // effective control: the recipient owns it, and the donor still reaches it through the parent.
    //
    // "Transfer ownership" therefore does not mean "give it away" unless the resource also leaves the
    // donor's tree. Worth stating plainly in a permissions document, because both parties are likely
    // to assume otherwise — the donor that they have relinquished it, the recipient that they now have
    // it to themselves.
    Assertions.assertTrue(user1Permissions().userHasReadAccessToResource(target.id()),
        "the previous owner should still reach the folder through the home folder they own");
    Assertions.assertTrue(user1Permissions().userHasWriteAccessToResource(target.id()),
        "inherited access from the owned parent should still carry write");

    // Which means the previous owner can still read the ACL, unlike a stranger.
    HttpResponse<String> asOldOwner = send("GET", target.permissionsPath(), null, user1Header);
    Assertions.assertEquals(200, asOldOwner.statusCode(),
        "the previous owner still has inherited access, so the ACL should still be readable to them: "
            + asOldOwner.body());

    HttpResponse<String> asNewOwner = send("GET", target.permissionsPath(), null, user2Header);
    Assertions.assertEquals(200, asNewOwner.statusCode(),
        "the new owner should be able to read the ACL: " + asNewOwner.body());
    CedarNodePermissionsWithExtract acl =
        JsonMapper.MAPPER.readValue(asNewOwner.body(), CedarNodePermissionsWithExtract.class);
    Assertions.assertEquals(user2.getId(), acl.getOwner().getId(),
        "the ACL should name the new owner");
  }

  // ── fixtures and helpers ───────────────────────────────────────────────────

  /** A resource under test: its permissions path and the graph id to check ownership against. */
  private record Target(String label, String permissionsPath, CedarFilesystemResourceId id) {
  }

  private static Target folderTarget(String name) {
    FolderServerFolder newFolder = new FolderServerFolder();
    newFolder.setName(name);
    newFolder.setDescription("Created by OwnershipTransferTest");
    CedarFolderId newFolderId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    FolderServerFolder created = CedarDataServices.getInstance().getFolderServiceSession(user1Context)
        .createFolderAsChildOfId(newFolder, user1HomeId, newFolderId);
    Assertions.assertNotNull(created, "the fixture folder should be created");
    return new Target("folder", "/folders/"
        + URLEncoder.encode(created.getId(), StandardCharsets.UTF_8) + "/permissions", created.getResourceId());
  }

  /** One artifact of each type, owned by user 1. */
  private static List<Target> artifactTargets(String tag) {
    record Type(String label, String prefix, CedarResourceType type, Supplier<FolderServerArtifact> factory) {
    }
    List<Type> types = List.of(
        new Type("template", "/templates", CedarResourceType.TEMPLATE, FolderServerTemplate::new),
        new Type("element", "/template-elements", CedarResourceType.ELEMENT, FolderServerElement::new),
        new Type("field", "/template-fields", CedarResourceType.FIELD, FolderServerField::new),
        new Type("instance", "/template-instances", CedarResourceType.INSTANCE, FolderServerInstance::new));

    List<Target> targets = new ArrayList<>();
    for (Type type : types) {
      FolderServerArtifact artifact = type.factory().get();
      artifact.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(type.type()));
      artifact.setName("Ownership " + tag + " " + type.label());
      artifact.setDescription("Created by OwnershipTransferTest");
      if (artifact instanceof FolderServerSchemaArtifact schema) {
        schema.setVersion("1.0.0");
        schema.setPublicationStatus("bibo:draft");
        schema.setLatestVersion(true);
        schema.setLatestDraftVersion(true);
        schema.setLatestPublishedVersion(false);
      }
      FolderServerArtifact created = CedarDataServices.getInstance().getFolderServiceSession(user1Context)
          .createResourceAsChildOfId(artifact, user1HomeId);
      Assertions.assertNotNull(created, "the fixture " + type.label() + " should be created");
      targets.add(new Target(type.label(), type.prefix() + "/"
          + URLEncoder.encode(created.getId(), StandardCharsets.UTF_8) + "/permissions",
          created.getResourceId()));
    }
    return targets;
  }

  private static void grantToUser2(Target target, FilesystemResourcePermission permission) {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    request.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), permission));
    BackendCallResult result = CedarDataServices.getInstance().getResourcePermissionServiceSession(user1Context)
        .updateResourcePermissions(target.id(), request);
    Assertions.assertFalse(result.isError(), "the grant on the " + target.label() + " should succeed");
  }

  private static ResourcePermissionServiceSession user1Permissions() {
    return CedarDataServices.getInstance().getResourcePermissionServiceSession(user1Context);
  }



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
    HttpResponse<String> response = send("GET", permissionsPath(folder), null, user1Header);
    Assertions.assertEquals(200, response.statusCode(),
        "the owner should be able to read the ACL back: " + response.body());
    return JsonMapper.MAPPER.readValue(response.body(), CedarNodePermissionsWithExtract.class);
  }

  private static FolderServerFolder folder(String name) {
    FolderServerFolder newFolder = new FolderServerFolder();
    newFolder.setName(name);
    newFolder.setDescription("Created by SharingRoundTripTest");
    CedarFolderId newFolderId = cedarConfig.getLinkedDataUtil().buildNewLinkedDataIdObject(CedarFolderId.class);
    FolderServerFolder created = CedarDataServices.getInstance().getFolderServiceSession(user1Context)
        .createFolderAsChildOfId(newFolder, user1HomeId, newFolderId);
    Assertions.assertNotNull(created, "the fixture folder should be created");
    return created;
  }

  private static FolderServerGroup group(String name) {
    GroupServiceSession groups = CedarDataServices.getInstance().getGroupServiceSession(user1Context);
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
    return CedarDataServices.getInstance().getResourcePermissionServiceSession(user2Context);
  }

  private static HttpResponse<String> send(String method, String path, String body, String authHeader)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json");
    if (authHeader != null) {
      builder.header("Authorization", authHeader);
    }
    if ("PUT".equals(method) && path.endsWith("/permissions")) {
      builder.header("If-Match", "*");
    }
    builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

}
