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
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerElement;
import org.metadatacenter.model.folderserver.basic.FolderServerField;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.ResourcePermissionServiceSession;
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
import org.metadatacenter.util.test.PermissionMatrix;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.metadatacenter.util.test.PermissionMatrix.Actor.ANONYMOUS;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OTHER_USER;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OWNER;

/**
 * What a grant buys on an artifact, for every artifact type — the companion to
 * {@link FolderPermissionLevelMatrixTest}, which asks the same of folders.
 *
 * <p>The permission machinery is shared: artifacts and folders are both filesystem resources and both
 * ACL updates run through {@code ResourcePermissionRequestValidator}. So the expectation is that
 * artifacts behave exactly as folders do, including conferring re-sharing on a WRITE grant. That is a
 * claim about shared code rather than about these routes, though, and each artifact type has its own
 * resource class; this is what turns the expectation into an assertion.
 *
 * <p>The surface here is narrower than for folders, because artifacts have no rename and their content
 * write and delete both proxy to the artifact server, which this suite does not run. What remains is
 * the reads and the ACL update — and the ACL update is the row that matters, since it is where the
 * escalation lives.
 *
 * <p>Grants are applied through the graph session, so a failure cannot be confused with a grant that
 * did not take.
 */
public class ArtifactPermissionLevelMatrixTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // distinct from the dev server and from every other booting test class in this module.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19057",
        "CEDAR_RESOURCE_ADMIN_PORT", "19157",
        "CEDAR_RESOURCE_STOP_PORT", "19257",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static Map<PermissionMatrix.Actor, String> actors;
  private static CedarConfig cedarConfig;
  private static CedarRequestContext user1Context;
  private static CedarRequestContext user2Context;
  private static CedarUser user1;
  private static CedarUser user2;
  private static CedarFolderId user1HomeId;

  /** One artifact fixture: its REST path, and whether its type exposes {@code /versions}. */
  private record Fixture(String label, String path, FolderServerArtifact node, boolean versioned) {
  }

  /** The four artifact types: how to build one, where its endpoints live, and whether it is versioned. */
  private record Type(String label, String pathPrefix, CedarResourceType resourceType,
                      Supplier<FolderServerArtifact> factory, boolean versioned) {
  }

  private static final List<Type> TYPES = List.of(
      new Type("template", "/templates", CedarResourceType.TEMPLATE, FolderServerTemplate::new, true),
      new Type("element", "/template-elements", CedarResourceType.ELEMENT, FolderServerElement::new, true),
      new Type("field", "/template-fields", CedarResourceType.FIELD, FolderServerField::new, true),
      // Instances are not versioned, so they expose no /versions route.
      new Type("instance", "/template-instances", CedarResourceType.INSTANCE, FolderServerInstance::new, false));

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

  /**
   * A READ grant must buy reading every graph-backed view of the artifact, and must not buy the
   * authority to widen access.
   */
  @Test
  public void aReadGrantBuysReadingOnly() throws Exception {
    List<Fixture> readable = fixtures("read-readable", FilesystemResourcePermission.READ);
    List<Fixture> resharable = fixtures("read-resharable", FilesystemResourcePermission.READ);

    PermissionMatrix matrix = new PermissionMatrix("http://localhost:" + SERVER.getLocalPort(), actors);

    for (Fixture f : readable) {
      matrix.when("GET", f.path() + "/details")
          .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
      matrix.when("GET", f.path() + "/permissions")
          .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
      matrix.when("GET", f.path() + "/report")
          .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
      if (f.versioned()) {
        matrix.when("GET", f.path() + "/versions")
            .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
      }
    }

    // Refused, so these fixtures are not mutated and need no isolation beyond their own set. An
    // authenticated re-sharer is refused with 403 (permission denial via CedarErrorType.PERMISSION);
    // an anonymous caller is refused with 401 by the authentication layer.
    for (Fixture f : resharable) {
      matrix.when("PUT", f.path() + "/permissions", resharePermissionsBody())
          .expect(ANONYMOUS, 401).expect(OTHER_USER, 403);
    }

    matrix.verify();

    // A refusal must have changed nothing: user 2's read grant should survive its own denied attempt.
    for (Fixture f : resharable) {
      Assertions.assertTrue(user2Permissions().userHasReadAccessToResource(f.node().getResourceId()),
          "the refused ACL update should have left the " + f.label() + "'s permissions untouched");
    }
  }

  /**
   * A WRITE grant confers re-sharing, on every artifact type, exactly as it does on a folder. Recorded
   * as the 200 the endpoints actually answer: the ACL update is gated on write access, and
   * {@code CHANGEPERMISSIONS} is enforced nowhere. See {@link FolderPermissionLevelMatrixTest} and the
   * roadmap entry on the unenforced levels.
   */
  @Test
  public void aWriteGrantConfersResharingOnEveryType() throws Exception {
    List<Fixture> readable = fixtures("write-readable", FilesystemResourcePermission.WRITE);
    List<Fixture> resharable = fixtures("write-resharable", FilesystemResourcePermission.WRITE);

    PermissionMatrix matrix = new PermissionMatrix("http://localhost:" + SERVER.getLocalPort(), actors);

    for (Fixture f : readable) {
      matrix.when("GET", f.path() + "/details")
          .expect(ANONYMOUS, 401).expect(OWNER, 200).expect(OTHER_USER, 200);
    }

    // Each re-share row gets its own fixture, because this one succeeds and rewrites the ACL.
    for (Fixture f : resharable) {
      matrix.when("PUT", f.path() + "/permissions", resharePermissionsBody())
          .expect(ANONYMOUS, 401).expect(OTHER_USER, 200);
    }

    matrix.verify();

    // A 200 would not distinguish an accepted no-op from a real rewrite. The body restated user 1 as
    // owner and listed no user permissions, so a grantee holding only WRITE has just revoked their own
    // access to someone else's artifact. Assert that per type, so the escalation is demonstrated.
    for (Fixture f : resharable) {
      Assertions.assertFalse(user2Permissions().userHasWriteAccessToResource(f.node().getResourceId()),
          "user 2 held only WRITE on the " + f.label() + ", yet rewriting its ACL succeeded and removed "
              + "their own grant: the update is gated on write access, not on CHANGEPERMISSIONS");
    }
  }

  // ── fixtures and helpers ───────────────────────────────────────────────────

  /** One artifact of every type, under user 1's home folder, each granted the given permission to user 2. */
  private static List<Fixture> fixtures(String tag, FilesystemResourcePermission permission) {
    List<Fixture> built = new ArrayList<>();
    for (Type type : TYPES) {
      FolderServerArtifact artifact = type.factory().get();
      artifact.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(type.resourceType()));
      artifact.setName("PLM " + tag + " " + type.label());
      artifact.setDescription("Created by ArtifactPermissionLevelMatrixTest");
      // Version and publication fields live on the schema types only; instances carry neither.
      if (artifact instanceof FolderServerSchemaArtifact schema) {
        schema.setVersion("1.0.0");
        schema.setPublicationStatus("bibo:draft");
        schema.setLatestVersion(true);
        schema.setLatestDraftVersion(true);
        schema.setLatestPublishedVersion(false);
      }
      FolderServerArtifact created = CedarDataServices.getInstance().getFolderServiceSession(user1Context)
          .createResourceAsChildOfId(artifact, user1HomeId);
      Assertions.assertNotNull(created, "the fixture " + type.label() + " should have been created");

      grantToUser2(created, permission);
      built.add(new Fixture(type.label(),
          type.pathPrefix() + "/" + URLEncoder.encode(created.getId(), StandardCharsets.UTF_8),
          created, type.versioned()));
    }
    return built;
  }

  private static void grantToUser2(FolderServerArtifact artifact, FilesystemResourcePermission permission) {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    request.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), permission));
    BackendCallResult result = CedarDataServices.getInstance().getResourcePermissionServiceSession(user1Context)
        .updateResourcePermissions(artifact.getResourceId(), request);
    Assertions.assertFalse(result.isError(), "the grant should succeed");
  }

  /**
   * A valid re-share request, restating user 1 as owner and asking for nothing. It must be valid, or
   * the validator rejects the body with 400 before the authority to re-share is considered and the row
   * asserts nothing — the trap {@link FolderPermissionLevelMatrixTest#resharePermissionsBody()}
   * documents. Serialized from the real request object so the shape cannot drift.
   */
  private static String resharePermissionsBody() throws Exception {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    return JsonMapper.MAPPER.writeValueAsString(request);
  }

  private static ResourcePermissionServiceSession user2Permissions() {
    return CedarDataServices.getInstance().getResourcePermissionServiceSession(user2Context);
  }

}
