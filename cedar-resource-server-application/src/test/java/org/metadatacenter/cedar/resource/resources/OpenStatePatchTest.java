package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ClassScopedSearchPermissionServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.util.IndexUtils;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/** Integration coverage for conditional OpenView visibility changes against embedded Neo4j. */
@Tag("integration")
public class OpenStatePatchTest {

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1",
        "CEDAR_OPENSEARCH_REST_PORT", "1"));
  }

  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final String OPEN = "{\"isOpen\":true}";
  private static final String NOT_OPEN = "{\"isOpen\":false}";

  private static NoOpNodeIndexingService indexing;
  private static String authHeader;
  private static String artifactId;
  private static String folderId;
  private static String homeFolderId;
  private static String listedFolderId;
  private static String listedArtifactId;

  @BeforeAll
  static void setUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig = CedarConfig.getInstance(
        CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    authHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    indexing = new NoOpNodeIndexingService(cedarConfig);
    AbstractResourceServerResource.injectServices(
        indexing,
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        ClassScopedSearchPermissionServices.open(cedarConfig));

    CedarRequestContext context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    FolderServiceSession session = CedarDataServices.getInstance().getFolderServiceSession(context);
    CedarFolderId homeId = session.findHomeFolderOf().getResourceId();
    // Other integration classes share the home folder and can fill its first page.
    // Keep the visibility assertions in a directory containing only our fixtures.
    homeFolderId = createFolder(session, cedarConfig, homeId, "Open visibility fixtures");

    folderId = createFolder(session, cedarConfig, homeId, "Conditional Open Folder");
    artifactId = createTemplate(session, cedarConfig, folderId, "Conditional Open Template");
    // The listing tests change open state on fixtures of their own, so the revision counts the
    // tests above assert do not depend on the order the methods run in.
    listedFolderId = createFolder(session, cedarConfig, CedarFolderId.build(homeFolderId), "Listed Open Folder");
    listedArtifactId = createTemplate(session, cedarConfig, listedFolderId, "Listed Open Template");
  }

  private static String createFolder(FolderServiceSession session, CedarConfig cedarConfig, CedarFolderId parentId,
                                     String name) {
    FolderServerFolder folder = new FolderServerFolder();
    folder.setName(name);
    folder.setDescription("Open state integration fixture");
    CedarFolderId newFolderId = CedarFolderId.build(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FOLDER));
    return session.createFolderAsChildOfId(folder, parentId, newFolderId).getId();
  }

  private static String createTemplate(FolderServiceSession session, CedarConfig cedarConfig, String parentId,
                                       String name) {
    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName(name);
    template.setDescription("Open state integration fixture");
    template.setVersion("1.0.0");
    template.setPublicationStatus("bibo:draft");
    template.setLatestVersion(true);
    template.setLatestDraftVersion(true);
    template.setLatestPublishedVersion(false);
    return session.createResourceAsChildOfId(template, CedarFolderId.build(parentId)).getId();
  }

  @AfterAll
  static void tearDown() {
    SERVER.after();
  }

  @Test
  void artifactVisibilityRequiresTheDetailsEtagAndAdvancesIt() throws Exception {
    String detailsPath = "/templates/" + enc(artifactId) + "/details";
    String path = "/templates/" + enc(artifactId);
    HttpResponse<String> details = request("GET", detailsPath, null, null);
    Assertions.assertEquals(200, details.statusCode(), details.body());
    Assertions.assertEquals("\"1\"", etag(details));

    Assertions.assertEquals(428, request("PATCH", path, OPEN, null).statusCode());

    HttpResponse<String> opened = request("PATCH", path, OPEN, "\"1\"");
    Assertions.assertEquals(200, opened.statusCode(), opened.body());
    Assertions.assertEquals("\"2\"", etag(opened));
    Assertions.assertTrue(JsonMapper.STRICT_MAPPER.readTree(opened.body()).path("isOpen").asBoolean());

    HttpResponse<String> staleClose = request("PATCH", path, NOT_OPEN, "\"1\"");
    Assertions.assertEquals(412, staleClose.statusCode(), staleClose.body());
    Assertions.assertEquals("\"2\"",
        JsonMapper.STRICT_MAPPER.readTree(staleClose.body()).path("parameters").path("currentETag").asText(),
        staleClose.body());

    HttpResponse<String> closed = request("PATCH", path, NOT_OPEN, "\"2\"");
    Assertions.assertEquals(200, closed.statusCode(), closed.body());
    Assertions.assertEquals("\"3\"", etag(closed));
    Assertions.assertFalse(JsonMapper.STRICT_MAPPER.readTree(closed.body()).path("isOpen").asBoolean());

    HttpResponse<String> wildcard = request("PATCH", path, OPEN, "*");
    Assertions.assertEquals(200, wildcard.statusCode(), wildcard.body());
    Assertions.assertEquals("\"4\"", etag(wildcard));

    HttpResponse<String> mergePatch = CLIENT.send(requestBuilder("PATCH", path, NOT_OPEN, "*")
        .setHeader("Content-Type", "application/merge-patch+json").build(), HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(200, mergePatch.statusCode(), mergePatch.body());
    Assertions.assertEquals("\"5\"", etag(mergePatch));
  }

  /**
   * The patch body is closed. It carries isOpen as a JSON boolean and nothing else, so a caller
   * cannot mistake a property the route ignores for one it acts on.
   */
  @Test
  void anOpenStatePatchRefusesAnyOtherBody() throws Exception {
    String path = "/templates/" + enc(artifactId);
    HttpResponse<String> extra = request("PATCH", path, "{\"isOpen\":true,\"resourceType\":\"template\"}", "*");
    Assertions.assertEquals(400, extra.statusCode(), extra.body());
    Assertions.assertTrue(extra.body().contains("resourceType"), extra.body());

    for (String body : new String[]{"{}", "{\"isOpen\":\"true\"}", "{\"isOpen\":1}", "{\"isOpen\":null}",
        "{\"@id\":\"" + artifactId + "\"}"}) {
      HttpResponse<String> refused = request("PATCH", path, body, "*");
      Assertions.assertEquals(400, refused.statusCode(), body + " -> " + refused.body());
    }
    HttpResponse<String> folderRefused = request("PATCH", "/folders/" + enc(folderId), "{}", "*");
    Assertions.assertEquals(400, folderRefused.statusCode(), folderRefused.body());
  }

  /** Each kind's route changes only an artifact of that kind. */
  @Test
  void anArtifactRouteRefusesAnotherKindsIdentifier() throws Exception {
    for (String collection : new String[]{"template-elements", "template-fields", "template-instances"}) {
      HttpResponse<String> refused = request("PATCH", "/" + collection + "/" + enc(artifactId), OPEN, "*");
      Assertions.assertEquals(404, refused.statusCode(), collection + " -> " + refused.body());
    }
  }

  /** The four open-state commands were removed when the change became a PATCH on the resource. */
  @Test
  void theRemovedOpenStateCommandsAnswerNotFound() throws Exception {
    String body = "{\"@id\":\"" + artifactId + "\"}";
    for (String command : new String[]{"make-artifact-open", "make-artifact-not-open", "make-folder-open",
        "make-folder-not-open"}) {
      HttpResponse<String> gone = request("POST", "/command/" + command, body, "*");
      Assertions.assertEquals(404, gone.statusCode(), command + " -> " + gone.body());
    }
  }

  @Test
  void folderVisibilityRequiresItsFolderEtagAndRejectsOneOfTwoConcurrentWriters() throws Exception {
    String folderPath = "/folders/" + enc(folderId);
    HttpResponse<String> found = request("GET", folderPath, null, null);
    Assertions.assertEquals(200, found.statusCode(), found.body());
    String initialEtag = etag(found);

    Assertions.assertEquals(428, request("PATCH", folderPath, OPEN, null).statusCode());

    CompletableFuture<HttpResponse<String>> open = requestAsync(folderPath, OPEN, initialEtag);
    CompletableFuture<HttpResponse<String>> close = requestAsync(folderPath, NOT_OPEN, initialEtag);
    List<Integer> statuses = List.of(open.get().statusCode(), close.get().statusCode()).stream().sorted().toList();
    Assertions.assertEquals(List.of(200, 412), statuses);

    HttpResponse<String> after = request("GET", folderPath, null, null);
    Assertions.assertEquals(200, after.statusCode(), after.body());
    Assertions.assertEquals("\"2\"", etag(after));
  }

  /**
   * A folder listing offered no OpenView change at all, so an artifact made not open could not be
   * made open again from the workspace; and the change never reindexed, so search listings kept
   * offering the change the artifact no longer allowed. Through a close and a reopen, the listing
   * must offer exactly what the details offer, and the index must receive each new state.
   */
  @Test
  void anArtifactCanBeReopenedAndEveryViewFollowsItsOpenState() throws Exception {
    String detailsPath = "/templates/" + enc(listedArtifactId) + "/details";
    assertListingMatchesDetails(listedFolderId, listedArtifactId, detailsPath, "enableOpenView");

    for (boolean open : new boolean[]{true, false, true}) {
      String etag = etag(request("GET", detailsPath, null, null));
      HttpResponse<String> changed = request("PATCH", "/templates/" + enc(listedArtifactId),
          open ? OPEN : NOT_OPEN, etag);
      Assertions.assertEquals(200, changed.statusCode(), changed.body());

      assertListingMatchesDetails(listedFolderId, listedArtifactId, detailsPath,
          open ? "disableOpenView" : "enableOpenView");
      Assertions.assertNotNull(indexing.lastIndexed(listedArtifactId), "the patch must reindex the artifact");
      Assertions.assertEquals(open, indexing.lastIndexed(listedArtifactId).isOpen(),
          "the index must receive the artifact's new open state");
    }
  }

  @Test
  void aFolderCanBeReopenedAndEveryViewFollowsItsOpenState() throws Exception {
    String folderPath = "/folders/" + enc(listedFolderId);
    assertListingMatchesDetails(homeFolderId, listedFolderId, folderPath, "enableOpenView");

    for (boolean open : new boolean[]{true, false, true}) {
      String etag = etag(request("GET", folderPath, null, null));
      HttpResponse<String> changed = request("PATCH", folderPath, open ? OPEN : NOT_OPEN, etag);
      Assertions.assertEquals(200, changed.statusCode(), changed.body());

      assertListingMatchesDetails(homeFolderId, listedFolderId, folderPath,
          open ? "disableOpenView" : "enableOpenView");
      Assertions.assertNotNull(indexing.lastIndexed(listedFolderId), "the patch must reindex the folder");
      Assertions.assertEquals(open, indexing.lastIndexed(listedFolderId).isOpen(),
          "the index must receive the folder's new open state");
    }
  }

  private static void assertListingMatchesDetails(String parentId, String resourceId, String detailsPath,
                                                  String expectedOpenViewAction) throws Exception {
    HttpResponse<String> details = request("GET", detailsPath, null, null);
    Assertions.assertEquals(200, details.statusCode(), details.body());
    Set<String> detailsActions = actions(JsonMapper.STRICT_MAPPER.readTree(details.body()));

    HttpResponse<String> listing = request("GET", "/folders/" + enc(parentId) + "/contents?limit=100", null, null);
    Assertions.assertEquals(200, listing.statusCode(), listing.body());
    JsonNode listed = null;
    for (JsonNode resource : JsonMapper.STRICT_MAPPER.readTree(listing.body()).path("resources")) {
      if (resourceId.equals(resource.path("@id").asText())) {
        listed = resource;
      }
    }
    Assertions.assertNotNull(listed, listing.body());
    Set<String> listingActions = actions(listed);

    Assertions.assertTrue(detailsActions.contains(expectedOpenViewAction), detailsActions.toString());
    Assertions.assertEquals(detailsActions, listingActions,
        "a listed resource must offer the actions its details offer");
  }

  private static Set<String> actions(JsonNode resource) {
    Set<String> actions = new TreeSet<>();
    resource.path("currentUserPermissions").path("availableActions").forEach(a -> actions.add(a.asText()));
    return actions;
  }

  private static CompletableFuture<HttpResponse<String>> requestAsync(String path, String body, String ifMatch) {
    return CLIENT.sendAsync(requestBuilder("PATCH", path, body, ifMatch).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> request(String method, String path, String body, String ifMatch)
      throws Exception {
    return CLIENT.send(requestBuilder(method, path, body, ifMatch).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder requestBuilder(String method, String path, String body, String ifMatch) {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json");
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    return builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
  }

  private static String enc(String id) {
    return URLEncoder.encode(id, StandardCharsets.UTF_8);
  }

  private static String etag(HttpResponse<?> response) {
    return response.headers().firstValue("ETag").orElse(null);
  }
}
