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

/** HTTP round trips for Viewer, Editor, Manager, grants, and ownership transfer. */
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

  /** A Viewer grant remains Viewer throughout the REST and graph layers. */
  @Test
  public void sharingAsViewerGrantsViewerOnly() throws Exception {
    shareAndVerify(ResourceRole.VIEWER, "Share Read Folder");
  }

  /** An Editor grant remains distinct from both Viewer and Manager. */
  @Test
  public void sharingAsEditorGrantsEditingWithoutManagement() throws Exception {
    shareAndVerify(ResourceRole.EDITOR, "Share Editor Folder");
  }

  /** A Manager grant provides the complete role-based capability set. */
  @Test
  public void sharingAsManagerGrantsManagement() throws Exception {
    shareAndVerify(ResourceRole.MANAGER, "Share Write Folder");
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
        new ResourcePermissionGroup(group.getId()), ResourceRole.VIEWER));

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
    Assertions.assertEquals(ResourceRole.VIEWER, acl.getGroupPermissions().get(0).getRole(),
        "the group's granted level is not the one that was requested");
    Assertions.assertEquals(group.getId(), acl.getGroupPermissions().get(0).getGroup().getId(),
        "the grant is recorded against the wrong group");

    // The member gains Viewer through the group, and no more than that.
    Assertions.assertTrue(user2Permissions().userHasCapability(folder.getResourceId(), ResourceCapability.READ_RESOURCE),
        "a member of a group granted Viewer over HTTP should have read access");
    Assertions.assertFalse(user2Permissions().userHasCapability(folder.getResourceId(), ResourceCapability.UPDATE_RESOURCE),
        "a group Viewer grant made over HTTP must not confer edit access");
  }

  /**
   * The three request shapes the HTTP validators exist to refuse. None of them is reachable when a
   * grant is applied through the session, which is why they were untested.
   */
  @Test
  public void malformedSharingRequestsAreRefused() throws Exception {
    FolderServerFolder folder = folder("Share Rejection Folder");

    // The same user twice, which leaves the intended level ambiguous.
    ResourcePermissionsRequest duplicate = ownedByUser1();
    duplicate.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), ResourceRole.VIEWER));
    duplicate.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), ResourceRole.MANAGER));
    expectRefusal(folder, JsonMapper.MAPPER.writeValueAsString(duplicate), "a request naming one user twice");

    // The owner also listed as a grantee, which would say two things about the same person.
    ResourcePermissionsRequest collision = ownedByUser1();
    collision.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user1.getId()), ResourceRole.VIEWER));
    expectRefusal(folder, JsonMapper.MAPPER.writeValueAsString(collision),
        "a request listing the owner as a grantee");

    String legacyProperty = "{\"userPermissions\":[{\"user\":{\"@id\":\"" + user2.getId()
        + "\"},\"permission\":\"read\"}],\"groupPermissions\":[]}";
    expectRefusal(folder, legacyProperty, "a request using the removed permission property");

    String legacyValue = "{\"userPermissions\":[{\"user\":{\"@id\":\"" + user2.getId()
        + "\"},\"role\":\"write\"}],\"groupPermissions\":[]}";
    expectRefusal(folder, legacyValue, "a request using a legacy permission value as a role");

    // None of the refusals may have changed anything: user 2 still has no access at all.
    Assertions.assertFalse(user2Permissions().userHasCapability(folder.getResourceId(), ResourceCapability.READ_RESOURCE),
        "a refused sharing request must not have granted anything");
  }

  // ── the round trip ─────────────────────────────────────────────────────────

  /**
   * Shares the folder with user 2 at the given level over HTTP, then checks the result three ways: the
   * endpoint accepted it, the ACL it serves back names exactly that level, and user 2's effective
   * access is exactly what that level implies — no more.
   */
  private void shareAndVerify(ResourceRole level, String folderName) throws Exception {
    FolderServerFolder folder = folder(folderName);

    Assertions.assertFalse(user2Permissions().userHasCapability(folder.getResourceId(), ResourceCapability.READ_RESOURCE),
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
    Assertions.assertEquals(level, granted.get(0).getRole(),
        "the recorded level is not the one that was requested — a silently altered grant");
    Assertions.assertEquals(user1.getId(), acl.getOwner().getId(),
        "sharing must not have changed the owner");

    // What the graph actually enforces. The negative half is the point: asserting only that a Viewer
    // can read would pass just as well if Viewer had been quietly turned into Manager.
    boolean expectEdit = level == ResourceRole.EDITOR || level == ResourceRole.MANAGER;
    boolean expectManagement = level == ResourceRole.MANAGER;
    Assertions.assertTrue(user2Permissions().userHasCapability(folder.getResourceId(), ResourceCapability.READ_RESOURCE),
        "a grant of " + level + " should confer read access");
    Assertions.assertEquals(expectEdit,
        user2Permissions().userHasCapability(folder.getResourceId(), ResourceCapability.UPDATE_RESOURCE),
        "a grant of " + level + " conferred the wrong edit capability");
    Assertions.assertEquals(expectManagement,
        user2Permissions().userHasCapability(folder.getResourceId(), ResourceCapability.MANAGE_GRANTS),
        "a grant of " + level + " conferred the wrong grant-management capability");
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

  /** A Manager may manage grants but may not transfer ownership. */
  @Test
  public void aManagerCannotTransferOwnership() throws Exception {
    List<Target> targets = new ArrayList<>();
    targets.add(folderTarget("Ownership Theft Folder"));
    targets.addAll(artifactTargets("theft"));

    for (Target target : targets) {
      grantToUser2(target, ResourceRole.MANAGER);
      Assertions.assertTrue(user2Permissions().userHasCapability(target.id(), ResourceCapability.MANAGE_GRANTS),
          "the Manager grant on the " + target.label() + " should have taken, or the test proves nothing");

      HttpResponse<String> attempt = transfer(target, user2.getId(), user2Header);
      Assertions.assertEquals(403, attempt.statusCode(),
          "a Manager transferring ownership of the " + target.label() + " should be refused, but got "
              + attempt.statusCode() + ": " + attempt.body());

      // The status is not the whole story. Confirm the graph still names user 1 as owner.
      Assertions.assertTrue(user1Permissions().userIsOwnerOfResource(target.id()),
          "the " + target.label() + " changed hands despite the refusal");
      Assertions.assertFalse(user2Permissions().userIsOwnerOfResource(target.id()),
          "the Manager became owner of the " + target.label() + " despite the refusal");
    }
  }

  /** A Viewer may not transfer ownership. */
  @Test
  public void aViewerCannotTransferOwnership() throws Exception {
    Target target = folderTarget("Ownership Read Grantee Folder");
    grantToUser2(target, ResourceRole.VIEWER);

    HttpResponse<String> attempt = transfer(target, user2.getId(), user2Header);
    Assertions.assertEquals(403, attempt.statusCode(),
        "a Viewer transferring ownership should be refused, but got " + attempt.statusCode());
    Assertions.assertTrue(user1Permissions().userIsOwnerOfResource(target.id()),
        "the folder changed hands despite the refusal");
  }

  @Test
  public void ownershipTransferRequiresAnotherUser() throws Exception {
    Target target = folderTarget("Ownership Self Transfer Folder");

    HttpResponse<String> response = transfer(target, user1.getId(), user1Header);

    Assertions.assertEquals(400, response.statusCode(),
        "transferring ownership to the current owner should be rejected: " + response.body());
    Assertions.assertTrue(user1Permissions().userIsOwnerOfResource(target.id()),
        "a rejected self-transfer must leave the owner unchanged");
  }

  @Test
  public void ownershipTransferRequiresTheCurrentAclRevision() throws Exception {
    Target target = folderTarget("Ownership Revision Folder");

    HttpResponse<String> acl = send("GET", target.permissionsPath(), null, user1Header);
    String originalEtag = acl.headers().firstValue("ETag").orElseThrow();
    grantToUser2(target, ResourceRole.VIEWER);

    HttpResponse<String> stale = transfer(target, user2.getId(), user1Header, originalEtag);
    Assertions.assertEquals(412, stale.statusCode(),
        "a transfer based on a stale ACL should be rejected: " + stale.body());
    Assertions.assertTrue(user1Permissions().userIsOwnerOfResource(target.id()),
        "a stale transfer must leave the owner unchanged");

    HttpResponse<String> missing = transfer(target, user2.getId(), user1Header, null);
    Assertions.assertEquals(428, missing.statusCode(),
        "a transfer without If-Match should be rejected: " + missing.body());
    Assertions.assertTrue(user1Permissions().userIsOwnerOfResource(target.id()),
        "a transfer without a precondition must leave the owner unchanged");
  }

  /**
   * The owner may transfer a resource and keeps reaching it afterwards if it stays in their tree.
   * The owner field moves; effective access does not, because the donor still owns the parent and
   * permissions inherit downwards.
   */
  @Test
  public void transferMovesOwnershipButNotInheritedAccess() throws Exception {
    Target target = folderTarget("Ownership Transfer Folder");

    // Give the recipient a direct grant first. Transfer must remove that redundant grant while
    // replacing the owner in the same graph transaction.
    grantToUser2(target, ResourceRole.VIEWER);
    HttpResponse<String> handover = transfer(target, user2.getId(), user1Header);
    Assertions.assertEquals(200, handover.statusCode(),
        "the owner should be able to transfer ownership: " + handover.body());

    Assertions.assertTrue(user2Permissions().userIsOwnerOfResource(target.id()),
        "user 2 should own the folder after the transfer");
    Assertions.assertTrue(user2Permissions().userHasCapability(target.id(), ResourceCapability.UPDATE_RESOURCE),
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
    Assertions.assertTrue(user1Permissions().userHasCapability(target.id(), ResourceCapability.READ_RESOURCE),
        "the previous owner should still reach the folder through the home folder they own");
    Assertions.assertTrue(user1Permissions().userHasCapability(target.id(), ResourceCapability.UPDATE_RESOURCE),
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
    Assertions.assertTrue(acl.getUserPermissions().stream()
            .noneMatch(grant -> user2.getId().equals(grant.getUser().getId())),
        "the new owner must not also retain a direct role grant on the same resource");
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

  private static void grantToUser2(Target target, ResourceRole permission) {
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

  private static HttpResponse<String> transfer(Target target, String newOwnerId, String authHeader)
      throws Exception {
    return transfer(target, newOwnerId, authHeader, "*");
  }

  private static HttpResponse<String> transfer(Target target, String newOwnerId, String authHeader,
                                               String ifMatch) throws Exception {
    String body = JsonMapper.MAPPER.writeValueAsString(Map.of(
        "@id", target.id().getId(),
        "newOwnerId", newOwnerId));
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort()
            + "/command/transfer-resource-ownership"))
        .header("Content-Type", "application/json")
        .header("Authorization", authHeader);
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    builder.POST(HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> send(String method, String path, String body, String authHeader)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json");
    if (authHeader != null) {
      builder.header("Authorization", authHeader);
    }
    if (("PUT".equals(method) && path.endsWith("/permissions"))
        || path.equals("/command/transfer-resource-ownership")) {
      builder.header("If-Match", "*");
    }
    builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

}
