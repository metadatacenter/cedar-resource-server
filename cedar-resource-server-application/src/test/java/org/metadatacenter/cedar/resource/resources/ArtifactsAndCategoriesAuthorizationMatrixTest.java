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
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.basic.FolderServerElement;
import org.metadatacenter.model.folderserver.basic.FolderServerField;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.PermissionMatrix;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.util.test.PermissionMatrix.Actor.ANONYMOUS;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OTHER_USER;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OWNER;

/**
 * The authorization grids for every artifact type — template, element, field and instance — and for a
 * category, completing the coverage the folder matrix began. These are where metadata actually lives
 * and how it is classified, so a denial that stops working here exposes one user's work to another:
 * the same class of silent failure {@link FoldersAuthorizationMatrixTest} exists to prevent, on the
 * surfaces it did not reach.
 *
 * <p>Every fixture is owned by test user 1 and shared with nobody, so user 2 has no grant on any of
 * them. Each row asserts that an unauthenticated caller is refused, that user 2 is refused, and — for
 * the reads — that the owner succeeds. The owner's success is what gives the denials their meaning:
 * it shows the endpoint works and is discriminating by identity rather than being broken for
 * everybody, which a table of 401s and 403s alone cannot distinguish.
 *
 * <p>The four artifact types are driven from one table rather than copied per type. They delegate to
 * the same superclass methods, so their behaviour ought to be identical — but that is a claim about
 * the code, and each type has its own resource class that could gate differently, or not at all. The
 * one asymmetry is real and encoded: instances expose no {@code /versions}, because they are not
 * versioned.
 *
 * <p>The artifact rows cover only the endpoints the resource server answers from the workspace graph:
 * details, permissions, report and versions. The content endpoints (<code>GET /templates/{id}</code>
 * and the write paths) proxy to the artifact server, which this suite does not run — the whole
 * resource-server suite is backend-free — so a row for them would assert the proxy failing rather
 * than the authorization decision. Covering those needs the cross-service contract tests the roadmap
 * describes, not this table. The permission check happens before the proxy in every case, so the
 * security contract itself is fully covered here; what is missing is only the owner's happy path on
 * the content routes.
 *
 * <p>Categories have no such gap, since every category endpoint is answered from the graph. Their
 * contract turns out to differ from folders in two ways worth stating, both established by running
 * this table rather than by reading the code: a category is <em>readable</em> by any authenticated
 * user holding the CATEGORY_READ role, because it is a shared classification vocabulary rather than
 * private data; and its ACL requires <em>write</em> access to read, which is stricter than folders.
 * Writes are owner-only as expected.
 */
public class ArtifactsAndCategoriesAuthorizationMatrixTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // distinct from the dev server and from every other booting test class in this module.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19037",
        "CEDAR_RESOURCE_ADMIN_PORT", "19137",
        "CEDAR_RESOURCE_STOP_PORT", "19237",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static Map<PermissionMatrix.Actor, String> actors;
  private static List<Artifact> artifacts;
  private static String categoryPath;
  private static String rootCategoryPath;
  private static String categoryName;
  private static String siblingCategoryName;
  private static String adminAuthHeader;
  private static String user1Id;
  private static CedarCategoryId categoryId;
  private static CedarCategoryId inaccessibleCategoryId;
  private static CedarRequestContext user1Context;

  /**
   * One artifact fixture: where its endpoints live, what it is called, and whether it is versioned.
   *
   * @param label     the type, for assertion messages
   * @param path      the encoded path to this artifact, e.g. {@code /template-elements/<encoded id>}
   * @param name      its {@code schema:name}, so a denied write can be shown to have changed nothing
   * @param versioned whether the type exposes {@code /versions}: instances do not, since they are
   *                  not versioned, while templates, elements and fields are
   */
  private record Artifact(String label, String path, String id, String name, boolean versioned) {
  }

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    actors = Map.of(
        OWNER, TestAuthUtil.getTestUser1AuthHeader(cedarConfig),
        OTHER_USER, TestAuthUtil.getTestUser2AuthHeader(cedarConfig));
    adminAuthHeader = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);

    EmbeddedCedarNeo4j.seed(cedarConfig);

    // No OpenSearch: indexing is a no-op and none of these endpoints search.
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    user1Context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    user1Id = TestAuthUtil.getTestUser1(cedarConfig).getId();

    // One node per artifact type in the workspace graph, under user 1's home folder. Created through
    // the graph session rather than the REST API on purpose: a POST would proxy the content to the
    // artifact server, which is not running, while every endpoint under test reads only the graph.
    CedarFolderId user1HomeId = CedarDataServices.getInstance().getFolderServiceSession(user1Context).findHomeFolderOf().getResourceId();
    artifacts = List.of(
        createSchemaArtifact(cedarConfig, user1Context, user1HomeId,
            new FolderServerTemplate(), CedarResourceType.TEMPLATE, "/templates", "template"),
        createSchemaArtifact(cedarConfig, user1Context, user1HomeId,
            new FolderServerElement(), CedarResourceType.ELEMENT, "/template-elements", "element"),
        createSchemaArtifact(cedarConfig, user1Context, user1HomeId,
            new FolderServerField(), CedarResourceType.FIELD, "/template-fields", "field"),
        createInstanceArtifact(cedarConfig, user1Context, user1HomeId,
            "/template-instances", "instance"));

    // A category owned by user 1, under the root category that seeding creates.
    FolderServerCategory rootCategory = CedarDataServices.getInstance().getCategoryServiceSession(user1Context).getRootCategory();
    Assertions.assertNotNull(rootCategory, "the seeded graph should contain the root category");
    CedarCategoryId rootCategoryId = rootCategory.getResourceId();
    categoryName = "Matrix Category";
    FolderServerCategory category = CedarDataServices.getInstance().getCategoryServiceSession(user1Context)
        .createCategory(rootCategoryId, categoryName,
            "Created by ArtifactsAndCategoriesAuthorizationMatrixTest", null);
    Assertions.assertNotNull(category, "the fixture category should have been created");
    categoryId = category.getResourceId();
    categoryPath = "/categories/" + URLEncoder.encode(category.getId(), StandardCharsets.UTF_8);
    rootCategoryPath = "/categories/" + URLEncoder.encode(rootCategoryId.getId(), StandardCharsets.UTF_8);
    siblingCategoryName = "Matrix Category Sibling";
    FolderServerCategory sibling = CedarDataServices.getInstance().getCategoryServiceSession(user1Context)
        .createCategory(rootCategoryId, siblingCategoryName,
            "Sibling created by ArtifactsAndCategoriesAuthorizationMatrixTest", null);
    Assertions.assertNotNull(sibling, "the sibling category fixture should have been created");

    CedarRequestContext user2Context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser2(cedarConfig));
    FolderServerCategory inaccessibleCategory = CedarDataServices.getInstance().getCategoryServiceSession(user2Context)
        .createCategory(rootCategoryId, "Matrix Category Owned By User 2",
            "Used to verify batch attachment preflight", null);
    Assertions.assertNotNull(inaccessibleCategory, "the inaccessible category fixture should have been created");
    inaccessibleCategoryId = inaccessibleCategory.getResourceId();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  /**
   * Creates a versioned artifact — template, element or field. The version and publication fields
   * live on {@code FolderServerSchemaArtifact}, so they are set here and not for instances.
   */
  private static Artifact createSchemaArtifact(CedarConfig cedarConfig, CedarRequestContext context,
                                               CedarFolderId parent, FolderServerSchemaArtifact artifact,
                                               CedarResourceType type, String pathPrefix, String label) {
    String name = "Matrix " + label;
    artifact.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(type));
    artifact.setName(name);
    artifact.setDescription("Created by ArtifactsAndCategoriesAuthorizationMatrixTest");
    artifact.setVersion("1.0.0");
    artifact.setPublicationStatus("bibo:draft");
    artifact.setLatestVersion(true);
    artifact.setLatestDraftVersion(true);
    artifact.setLatestPublishedVersion(false);
    return store(context, parent, artifact, pathPrefix, label, name, true);
  }

  /** Creates an instance, which carries neither a version nor a publication status. */
  private static Artifact createInstanceArtifact(CedarConfig cedarConfig, CedarRequestContext context,
                                                 CedarFolderId parent, String pathPrefix, String label) {
    String name = "Matrix " + label;
    FolderServerInstance instance = new FolderServerInstance();
    instance.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.INSTANCE));
    instance.setName(name);
    instance.setDescription("Created by ArtifactsAndCategoriesAuthorizationMatrixTest");
    return store(context, parent, instance, pathPrefix, label, name, false);
  }

  private static Artifact store(CedarRequestContext context, CedarFolderId parent,
                                FolderServerArtifact artifact, String pathPrefix, String label,
                                String name, boolean versioned) {
    FolderServerArtifact created = CedarDataServices.getInstance().getFolderServiceSession(context)
        .createResourceAsChildOfId(artifact, parent);
    Assertions.assertNotNull(created, "the fixture " + label + " should have been created");
    String path = pathPrefix + "/" + URLEncoder.encode(created.getId(), StandardCharsets.UTF_8);
    return new Artifact(label, path, created.getId(), name, versioned);
  }

  /**
   * The same grid over all four artifact types, in one table. The four resources delegate to the same
   * superclass methods, so the expectation is that their authorization behaviour is identical — but
   * "they share a superclass" is a claim about the code, not about the routes, and each type has its
   * own resource class that could gate differently or forget to gate at all. Driving them from one
   * table is what turns that assumption into an assertion, and keeps a new artifact type from being
   * added with no grid of its own.
   *
   * <p>One table rather than four tests on purpose: {@code PermissionMatrix} collects every failing
   * cell, so a single run reports the complete divergence across all types instead of stopping at
   * whichever type happens to run first. The paths carry the type, so attribution is not lost.
   */
  @Test
  public void aSecondUserCannotReachAnotherUsersArtifacts() throws Exception {
    String permissionsBody = "{\"userPermissions\": [], \"groupPermissions\": []}";
    PermissionMatrix matrix = new PermissionMatrix("http://localhost:" + SERVER.getLocalPort(), actors);

    for (Artifact artifact : artifacts) {
      // The node's own metadata: name, description, owner, folder. Leaking it would disclose what a
      // user is working on even without the content.
      matrix.when("GET", artifact.path() + "/details")
          .expect(ANONYMOUS, 401)
          .expect(OWNER, 200)
          .expect(OTHER_USER, 403);

      // The ACL is as sensitive as the artifact: it names who else can see it.
      matrix.when("GET", artifact.path() + "/permissions")
          .expect(ANONYMOUS, 401)
          .expect(OWNER, 200)
          .expect(OTHER_USER, 403);

      matrix.when("GET", artifact.path() + "/report")
          .expect(ANONYMOUS, 401)
          .expect(OWNER, 200)
          .expect(OTHER_USER, 403);

      // Only the schema types are versioned, so only they expose /versions. Asserting it for an
      // instance would pin a 404 that says nothing about authorization.
      if (artifact.versioned()) {
        // The version chain: a stranger must not be able to enumerate a user's drafts.
        matrix.when("GET", artifact.path() + "/versions")
            .expect(ANONYMOUS, 401)
            .expect(OWNER, 200)
            .expect(OTHER_USER, 403);
      }

      // Taking over the ACL is the most valuable single request available here: it would convert a
      // read denial into permanent access. An authenticated stranger is refused with 403, like every
      // other write row. This denial reaches the call-result path, which once defaulted to 401 (see
      // the category row below); it now carries CedarErrorType.PERMISSION, so it answers 403 to match.
      matrix.when("PUT", artifact.path() + "/permissions", permissionsBody)
          .header("If-Match", "*")
          .expect(ANONYMOUS, 401)
          .expect(OTHER_USER, 403);
    }

    matrix.verify();

    // Statuses alone would not show the refusals had no effect. Re-read each as the owner and confirm
    // it is untouched.
    for (Artifact artifact : artifacts) {
      HttpResponse<String> after = request("GET", artifact.path() + "/details", null, actors.get(OWNER));
      Assertions.assertEquals(200, after.statusCode(),
          "the owner's " + artifact.label() + " should have survived the denied requests");
      JsonNode details = JsonMapper.MAPPER.readTree(after.body());
      Assertions.assertEquals(artifact.name(), details.path("schema:name").asText(),
          "a denied request changed the " + artifact.label() + ": " + after.body());
    }
  }

  @Test
  public void aSecondUserCannotReachAnotherUsersCategory() throws Exception {
    String renameBody = "{\"schema:name\": \"Renamed By An Intruder\", \"schema:description\": \"nope\"}";
    String permissionsBody = "{\"userPermissions\": [], \"groupPermissions\": []}";
    String createBody = "{\"schema:name\": \"Created By An Intruder\", \"schema:description\": \"nope\","
        + " \"parentCategoryId\": null}";
    PermissionMatrix matrix = new PermissionMatrix("http://localhost:" + SERVER.getLocalPort(), actors);

    // Reading a category is open to any authenticated user holding the CATEGORY_READ role: the
    // endpoint gates on the role and does no per-category check. That is the design rather than a
    // gap — categories are a shared classification vocabulary, and a tree only its owner could read
    // would be useless for classifying anything. This row records that contract so a later change
    // that quietly makes reads private, breaking the picker for everyone else, fails here. Asserted
    // for both a private category and the root below.
    matrix.when("GET", categoryPath)
        .expect(ANONYMOUS, 401)
        .expect(OWNER, 200)
        .expect(OTHER_USER, 200);

    matrix.when("GET", rootCategoryPath)
        .expect(ANONYMOUS, 401)
        .expect(OWNER, 200)
        .expect(OTHER_USER, 200);

    // The ACL is not open, and it is stricter than the folder equivalent: reading a category's
    // permissions requires WRITE access to it (userMustHaveWriteAccessToCategory), not merely read.
    // Defensible — who may change a category is only of use to someone who may change it — but worth
    // pinning, since it differs from how folders treat their own ACL.
    matrix.when("GET", categoryPath + "/permissions")
        .expect(ANONYMOUS, 401)
        .expect(OWNER, 200)
        .expect(OTHER_USER, 403);

    // Renaming and deleting someone else's category would corrupt how their artifacts are classified
    // without touching the artifacts themselves, which makes it a quiet kind of damage.
    matrix.when("PUT", categoryPath, renameBody)
        .expect(ANONYMOUS, 401)
        .expect(OTHER_USER, 403);

    matrix.when("DELETE", categoryPath)
        .expect(ANONYMOUS, 401)
        .expect(OTHER_USER, 403);

    // Attaching a child to another user's category is a write to that category.
    matrix.when("POST", "/categories", createBody)
        .expect(ANONYMOUS, 401);

    // 403, which is correct — and was the reference behaviour the folder and artifact rows were
    // brought into line with. Categories reach it two ways over: gating on userMustHaveWriteAccessTo-
    // Category, which raises an exception carrying an explicit 403 status before the validator runs;
    // and, for the owner-change path that does reach the call-result validator, the same
    // CedarErrorType.PERMISSION (403) the resource path now uses. The whole write-denial family is 403.
    matrix.when("PUT", categoryPath + "/permissions", permissionsBody)
        .header("If-Match", "*")
        .expect(ANONYMOUS, 401)
        .expect(OTHER_USER, 403);

    matrix.verify();

    HttpResponse<String> after = request("GET", categoryPath, null, actors.get(OWNER));
    Assertions.assertEquals(200, after.statusCode(), "the owner's category should have survived the denied requests");
    JsonNode category = JsonMapper.MAPPER.readTree(after.body());
    Assertions.assertEquals(categoryName, category.path("schema:name").asText(),
        "a denied request renamed the category: " + after.body());
  }

  @Test
  public void categoryCannotBeRenamedToAnExistingSiblingName() throws Exception {
    String renameBody = "{\"schema:name\": \"" + siblingCategoryName
        + "\", \"schema:description\": \"duplicate sibling name\"}";

    HttpResponse<String> response = request("PUT", categoryPath, renameBody, adminAuthHeader);
    Assertions.assertEquals(409, response.statusCode(), response.body());
    Assertions.assertTrue(response.body().contains("categoryAlreadyPresent"), response.body());

    HttpResponse<String> after = request("GET", categoryPath, null, adminAuthHeader);
    Assertions.assertEquals(200, after.statusCode(), after.body());
    Assertions.assertEquals(categoryName, JsonMapper.MAPPER.readTree(after.body()).path("schema:name").asText());
  }

  @Test
  public void categoryPermissionReplacementUsesETags() throws Exception {
    String path = categoryPath + "/permissions";
    String body = "{\"owner\":{\"@id\":\"" + user1Id
        + "\"},\"userPermissions\":[],\"groupPermissions\":[]}";

    HttpResponse<String> initial = request("GET", path, null, adminAuthHeader);
    Assertions.assertEquals(200, initial.statusCode(), initial.body());
    Assertions.assertEquals("\"1\"", initial.headers().firstValue("ETag").orElse(null));

    HttpResponse<String> missing = request("PUT", path, body, adminAuthHeader);
    Assertions.assertEquals(428, missing.statusCode(), missing.body());

    HttpResponse<String> updated = request("PUT", path, body, adminAuthHeader, "\"1\"");
    Assertions.assertEquals(200, updated.statusCode(), updated.body());
    Assertions.assertEquals("\"2\"", updated.headers().firstValue("ETag").orElse(null));

    HttpResponse<String> stale = request("PUT", path, body, adminAuthHeader, "\"1\"");
    Assertions.assertEquals(412, stale.statusCode(), stale.body());

    HttpResponse<String> wildcard = request("PUT", path, body, adminAuthHeader, "*");
    Assertions.assertEquals(200, wildcard.statusCode(), wildcard.body());
    Assertions.assertEquals("\"3\"", wildcard.headers().firstValue("ETag").orElse(null));
  }

  @Test
  public void batchCategoryAttachValidatesEveryCategoryBeforeMutating() throws Exception {
    Artifact artifact = artifacts.get(0);
    String body = "{\"artifactId\":\"" + artifact.id() + "\",\"categoryIds\":[\""
        + categoryId.getId() + "\",\"" + inaccessibleCategoryId.getId() + "\"]}";

    HttpResponse<String> response = request("POST", "/command/attach-categories", body, actors.get(OWNER));
    Assertions.assertEquals(403, response.statusCode(), response.body());

    List<CedarCategoryId> attached = CedarDataServices.getInstance().getCategoryServiceSession(user1Context)
        .getAttachedCategoryIds(CedarUntypedArtifactId.build(artifact.id()));
    Assertions.assertTrue(attached.stream().noneMatch(categoryId::equals),
        "the permitted category must not be attached when a later category fails preflight");
  }

  private HttpResponse<String> request(String method, String path, String body, String authHeader) throws Exception {
    return request(method, path, body, authHeader, null);
  }

  private HttpResponse<String> request(String method, String path, String body, String authHeader,
                                       String ifMatch) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json");
    if (authHeader != null) {
      builder.header("Authorization", authHeader);
    }
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

}
