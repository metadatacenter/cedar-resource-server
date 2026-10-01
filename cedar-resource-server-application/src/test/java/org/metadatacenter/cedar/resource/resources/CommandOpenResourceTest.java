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
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/** Integration coverage for conditional OpenView visibility changes against embedded Neo4j. */
public class CommandOpenResourceTest {

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class,
          ResourceHelpers.resourceFilePath("test-config.yml"));
  private static final HttpClient CLIENT = HttpClient.newHttpClient();

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
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    CedarRequestContext context = CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig));
    FolderServiceSession session = CedarDataServices.getInstance().getFolderServiceSession(context);
    CedarFolderId homeId = session.findHomeFolderOf().getResourceId();
    homeFolderId = homeId.getId();

    folderId = createFolder(session, cedarConfig, homeId, "Conditional Open Folder");
    artifactId = createTemplate(session, cedarConfig, folderId, "Conditional Open Template");
    // The listing tests change open state on fixtures of their own, so the revision counts the
    // tests above assert do not depend on the order the methods run in.
    listedFolderId = createFolder(session, cedarConfig, homeId, "Listed Open Folder");
    listedArtifactId = createTemplate(session, cedarConfig, listedFolderId, "Listed Open Template");
  }

  private static String createFolder(FolderServiceSession session, CedarConfig cedarConfig, CedarFolderId parentId,
                                     String name) {
    FolderServerFolder folder = new FolderServerFolder();
    folder.setName(name);
    folder.setDescription("Open command integration fixture");
    CedarFolderId newFolderId = CedarFolderId.build(
        cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.FOLDER));
    return session.createFolderAsChildOfId(folder, parentId, newFolderId).getId();
  }

  private static String createTemplate(FolderServiceSession session, CedarConfig cedarConfig, String parentId,
                                       String name) {
    FolderServerTemplate template = new FolderServerTemplate();
    template.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE));
    template.setName(name);
    template.setDescription("Open command integration fixture");
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
    HttpResponse<String> details = request("GET", detailsPath, null, null);
    Assertions.assertEquals(200, details.statusCode(), details.body());
    Assertions.assertEquals("\"1\"", etag(details));

    String body = "{\"@id\":\"" + artifactId + "\"}";
    Assertions.assertEquals(428,
        request("POST", "/command/make-artifact-open", body, null).statusCode());

    HttpResponse<String> opened = request("POST", "/command/make-artifact-open", body, "\"1\"");
    Assertions.assertEquals(200, opened.statusCode(), opened.body());
    Assertions.assertEquals("\"2\"", etag(opened));
    Assertions.assertTrue(JsonMapper.STRICT_MAPPER.readTree(opened.body()).path("isOpen").asBoolean());

    HttpResponse<String> staleClose = request("POST", "/command/make-artifact-not-open", body, "\"1\"");
    Assertions.assertEquals(412, staleClose.statusCode(), staleClose.body());
    Assertions.assertEquals("\"2\"",
        JsonMapper.STRICT_MAPPER.readTree(staleClose.body()).path("parameters").path("currentETag").asText(),
        staleClose.body());

    HttpResponse<String> closed = request("POST", "/command/make-artifact-not-open", body, "\"2\"");
    Assertions.assertEquals(200, closed.statusCode(), closed.body());
    Assertions.assertEquals("\"3\"", etag(closed));
    Assertions.assertFalse(JsonMapper.STRICT_MAPPER.readTree(closed.body()).path("isOpen").asBoolean());

    HttpResponse<String> wildcard = request("POST", "/command/make-artifact-open", body, "*");
    Assertions.assertEquals(200, wildcard.statusCode(), wildcard.body());
    Assertions.assertEquals("\"4\"", etag(wildcard));
  }

  /**
   * A visibility command names the artifact or folder it changes and nothing else. A body carrying
   * anything more was read past in silence, so a caller could not tell a property the endpoint
   * ignores from one it acts on.
   */
  @Test
  void aVisibilityCommandRefusesPropertiesItDoesNotAccept() throws Exception {
    String withTheResourceType = "{\"@id\":\"" + artifactId + "\",\"resourceType\":\"template\"}";
    HttpResponse<String> refused = request("POST", "/command/make-artifact-open", withTheResourceType, "*");
    Assertions.assertEquals(400, refused.statusCode(), refused.body());
    Assertions.assertTrue(refused.body().contains("resourceType"), refused.body());
  }

  @Test
  void folderVisibilityRequiresItsFolderEtagAndRejectsOneOfTwoConcurrentWriters() throws Exception {
    String folderPath = "/folders/" + enc(folderId);
    HttpResponse<String> found = request("GET", folderPath, null, null);
    Assertions.assertEquals(200, found.statusCode(), found.body());
    String initialEtag = etag(found);
    String body = "{\"@id\":\"" + folderId + "\"}";

    Assertions.assertEquals(428,
        request("POST", "/command/make-folder-open", body, null).statusCode());

    CompletableFuture<HttpResponse<String>> open = requestAsync(
        "/command/make-folder-open", body, initialEtag);
    CompletableFuture<HttpResponse<String>> close = requestAsync(
        "/command/make-folder-not-open", body, initialEtag);
    List<Integer> statuses = List.of(open.get().statusCode(), close.get().statusCode()).stream().sorted().toList();
    Assertions.assertEquals(List.of(200, 412), statuses);

    HttpResponse<String> after = request("GET", folderPath, null, null);
    Assertions.assertEquals(200, after.statusCode(), after.body());
    Assertions.assertEquals("\"2\"", etag(after));
  }

  /**
   * A folder listing offered no OpenView change at all, so an artifact made not open could not be
   * made open again from the workspace; and the command never reindexed, so search listings kept
   * offering the change the artifact no longer allowed. Through a close and a reopen, the listing
   * must offer exactly what the details offer, and the index must receive each new state.
   */
  @Test
  void anArtifactCanBeReopenedAndEveryViewFollowsItsOpenState() throws Exception {
    String detailsPath = "/templates/" + enc(listedArtifactId) + "/details";
    String body = "{\"@id\":\"" + listedArtifactId + "\"}";
    assertListingMatchesDetails(listedFolderId, listedArtifactId, detailsPath, "enableOpenView");

    for (boolean open : new boolean[]{true, false, true}) {
      String etag = etag(request("GET", detailsPath, null, null));
      HttpResponse<String> changed = request("POST",
          open ? "/command/make-artifact-open" : "/command/make-artifact-not-open", body, etag);
      Assertions.assertEquals(200, changed.statusCode(), changed.body());

      assertListingMatchesDetails(listedFolderId, listedArtifactId, detailsPath,
          open ? "disableOpenView" : "enableOpenView");
      Assertions.assertNotNull(indexing.lastIndexed(listedArtifactId), "the command must reindex the artifact");
      Assertions.assertEquals(open, indexing.lastIndexed(listedArtifactId).isOpen(),
          "the index must receive the artifact's new open state");
    }
  }

  @Test
  void aFolderCanBeReopenedAndEveryViewFollowsItsOpenState() throws Exception {
    String folderPath = "/folders/" + enc(listedFolderId);
    String body = "{\"@id\":\"" + listedFolderId + "\"}";
    assertListingMatchesDetails(homeFolderId, listedFolderId, folderPath, "enableOpenView");

    for (boolean open : new boolean[]{true, false, true}) {
      String etag = etag(request("GET", folderPath, null, null));
      HttpResponse<String> changed = request("POST",
          open ? "/command/make-folder-open" : "/command/make-folder-not-open", body, etag);
      Assertions.assertEquals(200, changed.statusCode(), changed.body());

      assertListingMatchesDetails(homeFolderId, listedFolderId, folderPath,
          open ? "disableOpenView" : "enableOpenView");
      Assertions.assertNotNull(indexing.lastIndexed(listedFolderId), "the command must reindex the folder");
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
    return CLIENT.sendAsync(requestBuilder("POST", path, body, ifMatch).build(),
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
