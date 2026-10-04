package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.core.TextFieldInstance;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.id.CedarGroupId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.ModelNodeNames;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerCategory;
import org.metadatacenter.model.folderserver.basic.FolderServerGroup;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.GroupServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.ElasticsearchManagementService;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.permission.InlineSearchPermissionRelay;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.auth.CedarGroupUserRequest;
import org.metadatacenter.server.security.model.auth.CedarGroupUsersRequest;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionGroup;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionGroupPermissionPair;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUserPermissionPair;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
import org.metadatacenter.server.security.model.permission.resource.ResourceRole;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.http.RevisionPreconditionParser;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every reversible change to a resource's state, made and then undone over HTTP against a real
 * search index.
 *
 * <p>A resource is described three ways: by its details, by its entry in a folder listing, and by
 * its document in the search index. The first two are read from the graph when asked. The third is
 * written when something changes, so a change that forgets to write it leaves search describing the
 * old state. Each test here makes a change, undoes it, and after every step requires the three
 * descriptions to agree: on whether the user can see the resource at all, and on the actions they are
 * offered. Undoing matters as much as doing. A disabled action that cannot be enabled again was how
 * the first of these defects showed itself.
 *
 * <p>The permission projection the worker performs in production runs here on the test's thread,
 * through {@link InlineSearchPermissionRelay}. Runs under the {@code opensearch-it} profile, against
 * an index of its own that it deletes afterwards.
 */
public class ResourceStateRoundTripIT {

  private static final String OPENSEARCH_HOST =
      System.getenv().getOrDefault("CEDAR_OPENSEARCH_HOST", "127.0.0.1");
  private static final String OPENSEARCH_PORT =
      System.getenv().getOrDefault("CEDAR_OPENSEARCH_REST_PORT", "9200");
  private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  private static final String ALIAS = "cedar-search-rt-" + RUN;

  private static final Map<String, ObjectNode> ARTIFACTS = new ConcurrentHashMap<>();
  private static final Map<String, Long> ETAGS = new ConcurrentHashMap<>();
  private static final AtomicLong REVISIONS = new AtomicLong();
  private static final HttpServer ARTIFACT_SERVER;

  static {
    try {
      ARTIFACT_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    ARTIFACT_SERVER.createContext("/", ResourceStateRoundTripIT::handleArtifactRequest);
    ARTIFACT_SERVER.start();
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(ARTIFACT_SERVER.getAddress().getPort()),
        "CEDAR_OPENSEARCH_HOST", OPENSEARCH_HOST,
        "CEDAR_OPENSEARCH_REST_PORT", OPENSEARCH_PORT,
        "CEDAR_OPENSEARCH_TRANSPORT_PORT", "9300"));
  }

  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));
  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static CedarConfig cedarConfig;
  private static ElasticsearchManagementService management;
  private static InlineSearchPermissionRelay relay;
  private static CedarUser user1;
  private static CedarUser user2;
  private static String user1Auth;
  private static String user2Auth;
  private static String user1Home;

  @BeforeAll
  static void oneTimeSetUp() throws Exception {
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);
    // Named before the server starts. It builds its own indexing services from this configuration at
    // startup, and its version projection relay keeps writing through them. With the configured name
    // they would write into whatever index a developer's stack is serving.
    cedarConfig.getElasticsearchConfig().getIndexes().getSearchIndex().setName(ALIAS);
    SERVER.before();
    Assertions.assertSame(cedarConfig, CedarConfig.getInstance(environment),
        "the server rebuilt its configuration, so its own indexing services may not be using " + ALIAS);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);
    user1 = TestAuthUtil.getTestUser1(cedarConfig);
    user2 = TestAuthUtil.getTestUser2(cedarConfig);
    user1Auth = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    user2Auth = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);

    IndexUtils indexUtils = new IndexUtils(cedarConfig);
    management = indexUtils.getEsManagementService();
    indexUtils.ensureIndexAndAliasExist(management, ALIAS);
    NodeIndexingService indexing = indexUtils.getNodeIndexingService();
    NodeSearchingService searching = indexUtils.getNodeSearchingService();
    CedarRequestContext admin = CedarRequestContextFactory.fromUser(TestAuthUtil.getAdminUser(cedarConfig));
    relay = InlineSearchPermissionRelay.projectingAs(cedarConfig, admin, indexUtils, searching, indexing);
    AbstractResourceServerResource.injectServices(indexing, searching, relay.enqueueService(),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    user1Home = CedarDataServices.getInstance().getFolderServiceSession(CedarRequestContextFactory.fromUser(user1))
        .findHomeFolderOf().getId();
  }

  @AfterAll
  static void oneTimeTearDown() throws Exception {
    try {
      if (relay != null) {
        relay.close();
      }
      if (management != null) {
        for (String index : management.getAllIndices()) {
          if (index.startsWith(ALIAS)) {
            management.deleteIndex(index);
          }
        }
      }
    } finally {
      SERVER.after();
      ARTIFACT_SERVER.stop(0);
    }
  }

  // ── 1. Ownership: handed over, then handed back ──────────────────────────────

  /**
   * The transfer rewrote the ownership relationship and left the node's owner property naming the
   * old owner. Search reads ownership from that property, so it went on offering the old owner's
   * actions to the old owner and withholding them from the new one.
   */
  @Test
  void ownershipCanBeHandedOverAndBack() throws Exception {
    String folder = createFolder(user1Home, "ownership");
    String template = createTemplate(folder, "ownership");
    assertAgree(user1Auth, template, folder);
    assertOwner(user1Auth, template, true);

    transferOwnership(template, user2, user1Auth);
    assertAgree(user2Auth, template, null);
    assertOwner(user2Auth, template, true);
    // User 1 still owns the folder, and with it access to everything in it, but no longer the template.
    assertAgree(user1Auth, template, folder);
    assertOwner(user1Auth, template, false);

    transferOwnership(template, user1, user2Auth);
    assertAgree(user1Auth, template, folder);
    assertOwner(user1Auth, template, true);
    assertHidden(user2Auth, template, null);
  }

  // ── 2. Deleting a draft gives the release its Create Draft back ──────────────

  @Test
  void deletingADraftReenablesCreatingOneFromTheRelease() throws Exception {
    String folder = createFolder(user1Home, "redraft");
    String release = createTemplate(folder, "redraft");
    publish(release, "1.0.0");
    String draft = createDraft(release, "2.0.0", folder);
    assertOffers(user1Auth, release, folder, Set.of(), Set.of("createDraft", "publish"));
    assertOffers(user1Auth, draft, folder, Set.of("publish"), Set.of("createDraft"));

    delete(draft);
    assertHidden(user1Auth, draft, folder);
    assertOffers(user1Auth, release, folder, Set.of("createDraft"), Set.of("publish"));

    String secondDraft = createDraft(release, "2.0.0", folder);
    assertOffers(user1Auth, secondDraft, folder, Set.of("publish"), Set.of("createDraft"));
    assertOffers(user1Auth, release, folder, Set.of(), Set.of("createDraft", "publish"));
  }

  // ── 3. Sharing: granted, revoked, granted to everybody, revoked ──────────────

  @Test
  void aShareCanBeGrantedAndRevokedForAUserAndForEverybody() throws Exception {
    String folder = createFolder(user1Home, "sharing");
    String template = createTemplate(folder, "sharing");
    assertHidden(user2Auth, template, folder);

    share(folder, Map.of(user2.getId(), ResourceRole.VIEWER), Map.of());
    assertAgree(user2Auth, template, folder);
    assertSharedView(user2Auth, "shared-with-me", folder, true);
    share(folder, Map.of(), Map.of());
    assertHidden(user2Auth, template, folder);
    assertSharedView(user2Auth, "shared-with-me", folder, false);

    String everybody = everybodyGroupId();
    share(folder, Map.of(), Map.of(everybody, ResourceRole.VIEWER));
    assertAgree(user2Auth, template, folder);
    assertSharedView(user2Auth, "shared-with-everybody", folder, true);
    share(folder, Map.of(), Map.of());
    assertHidden(user2Auth, template, folder);
    assertSharedView(user2Auth, "shared-with-everybody", folder, false);
  }

  // ── 4. Move: into a shared folder, then back ─────────────────────────────────

  @Test
  void aResourceMovedIntoASharedFolderAndBackFollowsItsFolder() throws Exception {
    String privateFolder = createFolder(user1Home, "move private");
    String sharedFolder = createFolder(user1Home, "move shared");
    share(sharedFolder, Map.of(user2.getId(), ResourceRole.VIEWER), Map.of());
    String template = createTemplate(privateFolder, "move");
    assertHidden(user2Auth, template, privateFolder);

    move(template, sharedFolder);
    assertAgree(user1Auth, template, sharedFolder);
    assertAgree(user2Auth, template, sharedFolder);
    Assertions.assertNull(listed(user1Auth, privateFolder, template), "the folder it left still lists it");

    move(template, privateFolder);
    assertAgree(user1Auth, template, privateFolder);
    assertHidden(user2Auth, template, privateFolder);
    Assertions.assertNull(listed(user1Auth, sharedFolder, template), "the folder it left still lists it");
  }

  // ── 5. Group membership: added, then removed ─────────────────────────────────

  /**
   * Membership changes in the group server, which records the change for the search projection the
   * way {@code GroupsResource} does. This makes the same two calls and then reads every view.
   */
  @Test
  void aGroupMemberGainsAndLosesWhatTheGroupWasGranted() throws Exception {
    GroupServiceSession groups = CedarDataServices.getInstance()
        .getGroupServiceSession(CedarRequestContextFactory.fromUser(user1));
    FolderServerGroup group = groups.createGroup("round trip " + RUN, "Created by ResourceStateRoundTripIT");
    Assertions.assertNotNull(group);
    setMembers(groups, group.getResourceId(), false);
    String folder = createFolder(user1Home, "group");
    String template = createTemplate(folder, "group");
    share(folder, Map.of(), Map.of(group.getId(), ResourceRole.VIEWER));
    assertHidden(user2Auth, template, folder);

    setMembers(groups, group.getResourceId(), true);
    assertAgree(user2Auth, template, folder);

    setMembers(groups, group.getResourceId(), false);
    assertHidden(user2Auth, template, folder);
  }

  // ── 6. Category: attached, detached, attached again ──────────────────────────

  @Test
  void aCategoryCanBeAttachedDetachedAndAttachedAgain() throws Exception {
    CategoryServiceSession categories = CedarDataServices.getInstance()
        .getCategoryServiceSession(CedarRequestContextFactory.fromUser(user1));
    FolderServerCategory category = categories.createCategory(
        CedarCategoryId.build(categories.getRootCategory().getId()), "round trip " + RUN,
        "Created by ResourceStateRoundTripIT", null);
    Assertions.assertNotNull(category);
    String folder = createFolder(user1Home, "category");
    String template = createTemplate(folder, "category");
    Assertions.assertFalse(categorized(category.getId(), template));

    for (boolean attached : new boolean[]{true, false, true}) {
      HttpResponse<String> response = send("POST",
          attached ? "/command/attach-category" : "/command/detach-category",
          json(Map.of("artifactId", template, "categoryId", category.getId())), user1Auth, null);
      Assertions.assertTrue(response.statusCode() < 300, response.body());
      Assertions.assertEquals(attached, categorized(category.getId(), template),
          "search by category must follow the attachment");
      assertAgree(user1Auth, template, folder);
    }
  }

  // ── 7. A parent folder made open, then not open ──────────────────────────────

  @Test
  void aResourceIsOpenThroughItsFolderOnlyWhileTheFolderIsOpen() throws Exception {
    String parent = createFolder(user1Home, "open parent");
    String template = createTemplate(parent, "open child");
    assertImplicitlyOpen(template, parent, false);

    for (boolean open : new boolean[]{true, false}) {
      String etag = etag(send("GET", "/folders/" + enc(parent), null, user1Auth, null));
      HttpResponse<String> changed = send("POST",
          open ? "/command/make-folder-open" : "/command/make-folder-not-open",
          json(Map.of("@id", parent)), user1Auth, etag);
      Assertions.assertEquals(200, changed.statusCode(), changed.body());
      assertImplicitlyOpen(template, parent, open);
      // The child's own flag is unchanged. The child itself would still have to be made open.
      assertOffers(user1Auth, template, parent, Set.of("enableOpenView"), Set.of("disableOpenView"));
    }
  }

  // ── 8. Publish, then create a draft ──────────────────────────────────────────

  @Test
  void publishingMovesPublishToCreateDraftAndADraftMovesItBack() throws Exception {
    String folder = createFolder(user1Home, "publish");
    String template = createTemplate(folder, "publish");
    assertOffers(user1Auth, template, folder, Set.of("publish"), Set.of("createDraft"));

    publish(template, "1.0.0");
    assertOffers(user1Auth, template, folder, Set.of("createDraft"), Set.of("publish"));

    String draft = createDraft(template, "2.0.0", folder);
    assertOffers(user1Auth, template, folder, Set.of(), Set.of("createDraft", "publish"));
    assertOffers(user1Auth, draft, folder, Set.of("publish"), Set.of("createDraft"));
  }

  // ── 9. A template's field relabelled, as its instance's index document sees it ─

  /**
   * An instance's indexed field names and labels are read from its template, so relabelling a field
   * changes what search should say about every instance although no instance changed. Nothing
   * reindexed them. The version projection relay now does, in the background, within a few seconds.
   */
  @Test
  void relabellingATemplatesFieldReachesItsInstancesIndexDocuments() throws Exception {
    String folder = createFolder(user1Home, "relabel");
    TemplateSchemaArtifact model = templateModel(name("relabel"), "Colour");
    String template = createTemplate(folder, model);
    String instance = createInstance(folder, template, model);
    Assertions.assertEquals("Colour", indexedFieldLabel(instance));

    for (String label : new String[]{"Shade", "Colour"}) {
      HttpResponse<String> read = send("GET", "/templates/" + enc(template), null, user1Auth, null);
      ObjectNode relabelled = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(read.body());
      ((ObjectNode) relabelled.path("properties").path(FIELD)).put("skos:prefLabel", label);
      HttpResponse<String> written = send("PUT", "/templates/" + enc(template), relabelled.toString(), user1Auth,
          etag(read));
      Assertions.assertEquals(200, written.statusCode(), written.body());
      awaitIndexedFieldLabel(instance, label);
    }
  }

  // ── what every view must agree on ────────────────────────────────────────────

  /**
   * The user sees the resource in its details, in its folder's listing and in search, and is offered
   * the same actions in all three. A null folder skips the listing, for a user who may read the
   * resource but not the folder it is in.
   */
  private static JsonNode assertAgree(String auth, String id, String folder) throws Exception {
    HttpResponse<String> detailsResponse = send("GET", detailsPath(id), null, auth, null);
    Assertions.assertEquals(200, detailsResponse.statusCode(), detailsResponse.body());
    JsonNode details = JsonMapper.STRICT_MAPPER.readTree(detailsResponse.body());
    Set<String> expected = actions(details);

    if (folder != null) {
      JsonNode entry = listed(auth, folder, id);
      Assertions.assertNotNull(entry, "the folder listing does not show " + id);
      Assertions.assertEquals(expected, actions(entry), "the folder listing offers other actions than the details");
    }
    JsonNode found = searched(auth, id);
    Assertions.assertNotNull(found, "search does not find " + id);
    Assertions.assertEquals(expected, actions(found), "search offers other actions than the details");
    return details;
  }

  private static void assertHidden(String auth, String id, String folder) throws Exception {
    int status = send("GET", detailsPath(id), null, auth, null).statusCode();
    Assertions.assertTrue(status == 403 || status == 404, "the details are readable: " + status);
    if (folder != null) {
      HttpResponse<String> listing = send("GET", "/folders/" + enc(folder) + "/contents?limit=100", null, auth, null);
      Assertions.assertTrue(listing.statusCode() != 200 || listed(auth, folder, id) == null,
          "the folder listing shows " + id);
    }
    Assertions.assertNull(searched(auth, id), "search still finds " + id);
  }

  private static void assertOffers(String auth, String id, String folder, Set<String> offered, Set<String> withheld)
      throws Exception {
    Set<String> actions = actions(assertAgree(auth, id, folder));
    Assertions.assertTrue(actions.containsAll(offered), "expected " + offered + " among " + actions);
    for (String action : withheld) {
      Assertions.assertFalse(actions.contains(action), "did not expect " + action + " among " + actions);
    }
  }

  /**
   * The shared views are served from the graph rather than the index. One lists the resource exactly
   * while it is shared, and offers the same actions as its details.
   */
  private static void assertSharedView(String auth, String sharing, String id, boolean listed) throws Exception {
    HttpResponse<String> response = send("GET", "/search?limit=100&sharing=" + sharing, null, auth, null);
    Assertions.assertEquals(200, response.statusCode(), response.body());
    JsonNode entry = find(JsonMapper.STRICT_MAPPER.readTree(response.body()), id);
    Assertions.assertEquals(listed, entry != null, "the " + sharing + " view");
    if (listed) {
      JsonNode details = JsonMapper.STRICT_MAPPER.readTree(send("GET", detailsPath(id), null, auth, null).body());
      Assertions.assertEquals(actions(details), actions(entry),
          "the " + sharing + " view offers other actions than the details");
    }
  }

  private static void assertOwner(String auth, String id, boolean owner) throws Exception {
    Assertions.assertEquals(owner, assertAgree(auth, id, null).path("currentUserPermissions").path("owner").asBoolean());
    Assertions.assertEquals(owner, searched(auth, id).path("currentUserPermissions").path("owner").asBoolean(),
        "search disagrees about who owns " + id);
  }

  /**
   * The listing and the details read this from the path above the resource, and search asks the graph
   * once per page, because its documents record a resource's parent and nothing above it.
   */
  private static void assertImplicitlyOpen(String id, String folder, boolean open) throws Exception {
    Assertions.assertEquals(open, listed(user1Auth, folder, id).path("isOpenImplicitly").asBoolean(),
        "the folder listing");
    JsonNode details = JsonMapper.STRICT_MAPPER.readTree(send("GET", detailsPath(id), null, user1Auth, null).body());
    JsonNode self = null;
    for (JsonNode step : details.path("pathInfo")) {
      if (id.equals(step.path("@id").asText())) {
        self = step;
      }
    }
    Assertions.assertNotNull(self, "the details' path does not reach the resource: " + details.path("pathInfo"));
    Assertions.assertEquals(open, self.path("isOpenImplicitly").asBoolean(), "the details' path");
    Assertions.assertEquals(open, searched(user1Auth, id).path("isOpenImplicitly").asBoolean(), "search");
  }

  private static JsonNode listed(String auth, String folder, String id) throws Exception {
    HttpResponse<String> listing = send("GET", "/folders/" + enc(folder) + "/contents?limit=100", null, auth, null);
    if (listing.statusCode() != 200) {
      return null;
    }
    return find(JsonMapper.STRICT_MAPPER.readTree(listing.body()), id);
  }

  /** The resource as search returns it, found by the unique word in its name. */
  private static JsonNode searched(String auth, String id) throws Exception {
    refresh();
    String word = NAMES.get(id).split(" ")[0];
    HttpResponse<String> response = send("GET", "/search?limit=100&q=" + enc(word), null, auth, null);
    Assertions.assertEquals(200, response.statusCode(), response.body());
    return find(JsonMapper.STRICT_MAPPER.readTree(response.body()), id);
  }

  private static final String FIELD = "colour";

  /** The label the instance's index document gives its one field, read from the index itself. */
  private static String indexedFieldLabel(String instance) throws Exception {
    HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
        .uri(URI.create("http://" + OPENSEARCH_HOST + ":" + OPENSEARCH_PORT + "/" + ALIAS + "/_doc/" + enc(instance)))
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(200, response.statusCode(), response.body());
    JsonNode fields = JsonMapper.STRICT_MAPPER.readTree(response.body()).path("_source").path("infoFields");
    for (JsonNode field : fields) {
      if (FIELD.equals(field.path("fieldName").asText())) {
        return field.path("fieldPrefLabel").asText();
      }
    }
    return Assertions.fail("the instance's index document has no " + FIELD + " field: " + fields);
  }

  private static void awaitIndexedFieldLabel(String instance, String label) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    String indexed = indexedFieldLabel(instance);
    while (!label.equals(indexed) && System.nanoTime() < deadline) {
      Thread.sleep(250);
      indexed = indexedFieldLabel(instance);
    }
    Assertions.assertEquals(label, indexed, "the instance's index document still carries its template's old label");
  }

  private static boolean categorized(String categoryId, String id) throws Exception {
    refresh();
    HttpResponse<String> response = send("GET", "/search?limit=100&category_id=" + enc(categoryId), null, user1Auth, null);
    Assertions.assertEquals(200, response.statusCode(), response.body());
    return find(JsonMapper.STRICT_MAPPER.readTree(response.body()), id) != null;
  }

  private static JsonNode find(JsonNode page, String id) {
    for (JsonNode resource : page.path("resources")) {
      if (id.equals(resource.path("@id").asText())) {
        return resource;
      }
    }
    return null;
  }

  private static Set<String> actions(JsonNode resource) {
    Set<String> actions = new TreeSet<>();
    resource.path("currentUserPermissions").path("availableActions").forEach(a -> actions.add(a.asText()));
    return actions;
  }

  // ── the changes, each as the browser makes it ────────────────────────────────

  /** The name each resource was created with, which outlives the resource. */
  private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

  /** Every name starts with a word no other run uses, which is how search finds the resource. */
  private static String name(String label) {
    return "rt" + RUN + label.replace(" ", "") + " " + label;
  }

  private static String createFolder(String parent, String label) throws Exception {
    String name = name(label);
    HttpResponse<String> created = send("POST", "/folders",
        json(Map.of("folderId", parent, "name", name, "description", "Created by ResourceStateRoundTripIT")),
        user1Auth, null);
    Assertions.assertEquals(201, created.statusCode(), created.body());
    String id = JsonMapper.STRICT_MAPPER.readTree(created.body()).path("@id").asText();
    NAMES.put(id, name);
    return id;
  }

  private static String createTemplate(String folder, String label) throws Exception {
    return createTemplate(folder, templateModel(name(label), null));
  }

  private static TemplateSchemaArtifact templateModel(String name, String fieldLabel) {
    TemplateSchemaArtifact.Builder builder = TemplateSchemaArtifact.builder()
        .withName(name)
        .withVersion(Version.fromString("0.0.1"))
        .withStatus(Status.DRAFT);
    if (fieldLabel != null) {
      builder.withFieldSchema(TextField.builder().withName(FIELD).withPreferredLabel(fieldLabel).build());
    }
    return builder.build();
  }

  private static String createTemplate(String folder, TemplateSchemaArtifact model) throws Exception {
    ObjectNode document = new JsonArtifactRenderer().renderTemplateSchemaArtifact(model);
    HttpResponse<String> created = send("POST", "/templates?folder_id=" + enc(folder), document.toString(),
        user1Auth, null);
    Assertions.assertEquals(201, created.statusCode(), created.body());
    String id = JsonMapper.STRICT_MAPPER.readTree(created.body()).path("@id").asText();
    NAMES.put(id, model.name());
    return id;
  }

  private static String createInstance(String folder, String template, TemplateSchemaArtifact model)
      throws Exception {
    // Search indexes the fields an instance fills, so this one fills its only field.
    TemplateInstanceArtifact instance = InstanceInflater.inflate(model, TemplateInstanceArtifact.builder()
        .withIsBasedOn(URI.create(template))
        .withName(name("instance"))
        .withDescription("")
        .withSingleInstanceFieldInstance(FIELD, TextFieldInstance.builder().withValue("teal").build())
        .build());
    HttpResponse<String> created = send("POST", "/template-instances?folder_id=" + enc(folder),
        new JsonArtifactRenderer().renderTemplateInstanceArtifact(instance).toString(), user1Auth, null);
    Assertions.assertEquals(201, created.statusCode(), created.body());
    String id = JsonMapper.STRICT_MAPPER.readTree(created.body()).path("@id").asText();
    NAMES.put(id, name("instance"));
    return id;
  }

  private static void transferOwnership(String id, CedarUser newOwner, String auth) throws Exception {
    HttpResponse<String> response = send("POST", "/command/transfer-resource-ownership",
        json(Map.of("@id", id, "newOwnerId", newOwner.getId())), auth, "*");
    Assertions.assertEquals(200, response.statusCode(), response.body());
    relay.drain();
  }

  private static void share(String folder, Map<String, ResourceRole> users, Map<String, ResourceRole> groups)
      throws Exception {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    users.forEach((user, role) -> request.getUserPermissions().add(
        new ResourcePermissionUserPermissionPair(new ResourcePermissionUser(user), role)));
    groups.forEach((group, role) -> request.getGroupPermissions().add(
        new ResourcePermissionGroupPermissionPair(new ResourcePermissionGroup(group), role)));
    HttpResponse<String> response = send("PUT", "/folders/" + enc(folder) + "/permissions",
        JsonMapper.STRICT_MAPPER.writeValueAsString(request), user1Auth, "*");
    Assertions.assertEquals(200, response.statusCode(), response.body());
    relay.drain();
  }

  private static void setMembers(GroupServiceSession groups, CedarGroupId group, boolean withUser2) {
    CedarGroupUsersRequest members = new CedarGroupUsersRequest();
    members.getUsers().add(new CedarGroupUserRequest(new ResourcePermissionUser(user1.getId()), true, true));
    if (withUser2) {
      members.getUsers().add(new CedarGroupUserRequest(new ResourcePermissionUser(user2.getId()), false, true));
    }
    Assertions.assertFalse(groups.updateGroupUsers(group, members).isError());
    relay.enqueueService().groupMembersUpdated(group.getId());
    relay.drain();
  }

  private static String everybodyGroupId() throws Exception {
    String name = cedarConfig.getFolderStructureConfig().getEverybodyGroup().getName();
    return CedarDataServices.getInstance().getGroupServiceSession(CedarRequestContextFactory.fromUser(user1))
        .findGroupByName(name).getId();
  }

  private static void move(String id, String target) throws Exception {
    HttpResponse<String> response = send("POST", "/command/move-resource-to-folder",
        json(Map.of("@id", id, "targetFolderId", target)), user1Auth, "*");
    Assertions.assertTrue(response.statusCode() < 300, response.body());
    relay.drain();
  }

  private static void publish(String id, String version) throws Exception {
    HttpResponse<String> response = send("POST", "/command/publish-artifact",
        json(Map.of("@id", id, "newVersion", version)), user1Auth, null);
    Assertions.assertEquals(200, response.statusCode(), response.body());
  }

  private static String createDraft(String id, String version, String folder) throws Exception {
    HttpResponse<String> response = send("POST", "/command/create-draft-artifact",
        json(Map.of("@id", id, "newVersion", version, "folderId", folder, "propagateSharing", false,
            "newFolderName", "")), user1Auth, null);
    Assertions.assertEquals(201, response.statusCode(), response.body());
    String draft = JsonMapper.STRICT_MAPPER.readTree(response.body()).path("@id").asText();
    NAMES.put(draft, NAMES.get(id));
    return draft;
  }

  private static void delete(String id) throws Exception {
    String etag = etag(send("GET", "/templates/" + enc(id), null, user1Auth, null));
    HttpResponse<String> response = send("DELETE", "/templates/" + enc(id), null, user1Auth, etag);
    Assertions.assertTrue(response.statusCode() < 300, response.body());
  }

  private static String detailsPath(String id) {
    return id.contains("/folders/") ? "/folders/" + enc(id) : "/templates/" + enc(id) + "/details";
  }

  private static void refresh() throws Exception {
    HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
        .uri(URI.create("http://" + OPENSEARCH_HOST + ":" + OPENSEARCH_PORT + "/" + ALIAS + "/_refresh"))
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(200, response.statusCode(), response.body());
  }

  private static String json(Map<String, ?> body) throws Exception {
    return JsonMapper.STRICT_MAPPER.writeValueAsString(body);
  }

  private static HttpResponse<String> send(String method, String path, String body, String auth, String ifMatch)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Content-Type", "application/json")
        .header("Authorization", auth);
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String etag(HttpResponse<String> response) {
    Assertions.assertEquals(200, response.statusCode(), response.body());
    return response.headers().firstValue("ETag").orElseThrow();
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  // ── the artifact server, which keeps each document by identifier with a revision

  private static void handleArtifactRequest(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    boolean instances = path.startsWith("/template-instances");
    String collection = instances ? "/template-instances/" : "/templates/";
    boolean predecessor = path.endsWith("/version-predecessor");
    String id = path.startsWith(collection) && path.length() > collection.length()
        ? URLDecoder.decode(path.substring(collection.length(),
        predecessor ? path.length() - "/version-predecessor".length() : path.length()), StandardCharsets.UTF_8)
        : null;
    if (id != null) id = cedarConfig.getLinkedDataUtil().resolveResourceId(
        instances ? CedarResourceType.INSTANCE : CedarResourceType.TEMPLATE, id);

    if ("POST".equals(method) && id == null) {
      ObjectNode created = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(body);
      String minted = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(
          instances ? CedarResourceType.INSTANCE : CedarResourceType.TEMPLATE);
      created.put(ModelNodeNames.JSON_LD_ID, minted);
      store(minted, created);
      respond(exchange, 201, created, minted);
      return;
    }
    if (id == null || !ARTIFACTS.containsKey(id)) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    if ("GET".equals(method)) {
      respond(exchange, 200, ARTIFACTS.get(id), null);
      return;
    }
    String condition = exchange.getRequestHeaders().getFirst("If-Match");
    if (condition != null && !RevisionPreconditionParser.parse(condition).matches(ETAGS.get(id))) {
      respond(exchange, 412, JsonMapper.STRICT_MAPPER.createObjectNode().put("message", "stale"), null);
      return;
    }
    if ("DELETE".equals(method)) {
      ARTIFACTS.remove(id);
      ETAGS.remove(id);
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    if ("PUT".equals(method) && predecessor) {
      JsonNode previous = JsonMapper.STRICT_MAPPER.readTree(body).path("previousVersion");
      ObjectNode document = ARTIFACTS.get(id).deepCopy();
      if (previous.isTextual()) {
        document.put("pav:previousVersion", previous.asText());
      } else {
        document.remove("pav:previousVersion");
      }
      store(id, document);
      respond(exchange, 200, document, null);
      return;
    }
    if ("PUT".equals(method)) {
      ObjectNode replacement = (ObjectNode) JsonMapper.STRICT_MAPPER.readTree(body);
      store(id, replacement);
      respond(exchange, 200, replacement, null);
      return;
    }
    exchange.sendResponseHeaders(405, -1);
    exchange.close();
  }

  private static void store(String id, ObjectNode document) {
    ARTIFACTS.put(id, document);
    ETAGS.put(id, REVISIONS.incrementAndGet());
  }

  private static void respond(HttpExchange exchange, int status, JsonNode body, String location) throws IOException {
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    Long revision = ETAGS.get(body.path(ModelNodeNames.JSON_LD_ID).asText());
    if (revision != null) {
      exchange.getResponseHeaders().set("ETag", "\"" + revision + "\"");
    }
    if (location != null) {
      exchange.getResponseHeaders().set("Location", location);
    }
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
