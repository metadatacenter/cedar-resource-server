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
import org.metadatacenter.model.BiboStatus;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerElement;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.permission.resource.ResourceRole;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUserPermissionPair;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Who may see, and who may rewrite, the artifacts that include a changed one.
 *
 * <p>Both endpoints once answered for artifacts that were none of the caller's business. The preview
 * returned every artifact in the system that included the source, with their names and owners, because the
 * graph query matched on the INCLUDES arc alone; the update rewrote every target the caller named, having
 * checked read access to the source and nothing else.
 *
 * <p>The two halves are now enforced in different places, and these tests hold the endpoints to the result
 * rather than to the mechanism. Reading is settled in the graph query, which carries the permission
 * conditions and is covered directly by {@code InclusionSubgraphListingPermissionIntegrationTest}; writing
 * is settled here, by the check this endpoint makes on every target before it writes any of them. Asserting
 * the visible behaviour at the endpoint keeps these cases honest if that division ever moves again.
 *
 * <p>The same endpoint also wrote artifacts that publication had fixed. Propagation goes straight to the
 * artifact server, so the check the ordinary artifact PUT makes never ran, and a template released at
 * 1.0.0 came back from a propagation carrying a child it had not been published with. That check now runs
 * here too, beside the capability check and before the same first write.
 *
 * <p>The fixtures are one element and four templates that include it. User 2 can read one template and not
 * another, and can write neither — the arrangement that separates "may not see it" from "may see it but
 * may not change it", which the first two failures respectively allowed. User 2 can write the remaining
 * two, one a draft and one published, which separates a target that may take the change from one whose
 * content publication has fixed.
 *
 * <p>No artifact server runs here, and none is needed: every case asserted below must be settled before
 * the first write goes out. A test that reached the artifact server would be recording the bug.
 */
public class InclusionSubgraphAuthorizationTest {

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

  private static CedarConfig cedarConfig;
  private static CedarRequestContext user1Context;
  private static CedarUser user1;
  private static CedarUser user2;
  private static String user2AuthHeader;

  /** The element every fixture template includes. User 2 may read it. */
  private static FolderServerArtifact sourceElement;
  /** Includes the source, readable by user 2, writable by nobody but user 1. */
  private static FolderServerArtifact readableTemplate;
  /** Includes the source, and user 2 has no grant on it at all. */
  private static FolderServerArtifact invisibleTemplate;
  /** Includes the source, still a draft, and user 2 may write it. A legitimate propagation target. */
  private static FolderServerArtifact writableDraftTemplate;
  /** Includes the source, published, and user 2 may write it. Publication alone must refuse it. */
  private static FolderServerArtifact publishedTemplate;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    user2AuthHeader = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);

    EmbeddedCedarNeo4j.seed(cedarConfig);

    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));

    user1 = TestAuthUtil.getTestUser1(cedarConfig);
    user2 = TestAuthUtil.getTestUser2(cedarConfig);
    user1Context = CedarRequestContextFactory.fromUser(user1);
    CedarFolderId user1HomeId =
        CedarDataServices.getInstance().getFolderServiceSession(user1Context).findHomeFolderOf().getResourceId();

    sourceElement = create(new FolderServerElement(), CedarResourceType.ELEMENT, "ISA source element", user1HomeId);
    readableTemplate = create(new FolderServerTemplate(), CedarResourceType.TEMPLATE, "ISA readable template", user1HomeId);
    invisibleTemplate = create(new FolderServerTemplate(), CedarResourceType.TEMPLATE, "ISA invisible template", user1HomeId);
    writableDraftTemplate = create(new FolderServerTemplate(), CedarResourceType.TEMPLATE, "ISA writable draft template", user1HomeId);
    publishedTemplate = create(new FolderServerTemplate(), CedarResourceType.TEMPLATE, "ISA published template",
        user1HomeId, BiboStatus.PUBLISHED);

    grantToUser2(sourceElement, ResourceRole.VIEWER);
    grantToUser2(readableTemplate, ResourceRole.VIEWER);
    grantToUser2(writableDraftTemplate, ResourceRole.EDITOR);
    grantToUser2(publishedTemplate, ResourceRole.EDITOR);

    // Every template includes the element. The arc runs from the including artifact to the included one.
    includes(readableTemplate, sourceElement);
    includes(invisibleTemplate, sourceElement);
    includes(writableDraftTemplate, sourceElement);
    includes(publishedTemplate, sourceElement);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  /**
   * The preview must show user 2 only what they may read. The unreadable template still includes the
   * element and still comes back from the graph query, so its absence here is the endpoint filtering.
   */
  @Test
  public void previewOmitsTheArtifactsTheCallerCannotRead() throws Exception {
    HttpResponse<String> response = post("/command/inclusions-subgraph-preview", requestBody());

    Assertions.assertEquals(200, response.statusCode(), "user 2 may read the source element");
    JsonNode templates = JsonMapper.STRICT_MAPPER.readTree(response.body()).get("templates");
    Assertions.assertTrue(templates.has(readableTemplate.getId()),
        "the template user 2 may read should be in the affected tree");
    Assertions.assertFalse(templates.has(invisibleTemplate.getId()),
        "the affected tree disclosed a template user 2 has no grant on: the graph query returns every "
            + "including artifact in the system, so the endpoint has to filter it");
  }

  /**
   * Read access to a target is not authority to rewrite it. User 2 may see this template and may not
   * change it, so naming it as a propagation target is refused.
   */
  @Test
  public void updateRefusesATargetTheCallerCannotWrite() throws Exception {
    HttpResponse<String> response = post("/command/inclusions-subgraph-update", requestBody(readableTemplate.getId()));

    Assertions.assertEquals(403, response.statusCode(),
        "user 2 holds only READ on the target, so the propagation must be refused before anything is written");
  }

  /**
   * A target the caller cannot read is not a target at all: it never enters the tree, so no work is
   * planned for it. The request succeeds having done nothing, rather than quietly writing someone else's
   * artifact.
   */
  @Test
  public void updateDoesNoWorkForATargetTheCallerCannotRead() throws Exception {
    HttpResponse<String> response = post("/command/inclusions-subgraph-update", requestBody(invisibleTemplate.getId()));

    Assertions.assertEquals(200, response.statusCode(), response.body());
    JsonNode outcomes = JsonMapper.STRICT_MAPPER.readTree(response.body()).get("outcomes");
    Assertions.assertTrue(outcomes.isEmpty(),
        "nothing should have been planned for a template user 2 cannot even see, but the response reported "
            + outcomes);
  }

  /**
   * Publication fixes an artifact's content, so a published artifact is no target for a propagation. The
   * caller here holds EDITOR on it, which isolates the refusal to the publication status: the propagation
   * once rewrote such a template in place, leaving it at the version it was released as while carrying a
   * child it had never been published with.
   */
  @Test
  public void updateRefusesAPublishedTarget() throws Exception {
    HttpResponse<String> response = post("/command/inclusions-subgraph-update", requestBody(publishedTemplate.getId()));

    Assertions.assertEquals(400, response.statusCode(),
        "a published artifact must not take a propagated change, whatever the caller may do to a draft");
    Assertions.assertEquals("publishedArtifactCanNotBeChanged",
        JsonMapper.STRICT_MAPPER.readTree(response.body()).path("errorKey").asText(), response.body());
  }

  /**
   * One published target is enough to refuse the whole request, as the endpoint promises. The draft
   * alongside it is a target the caller may write, and the artifact server that would have received it
   * is not running, so a request that reached the writes would answer with a server error rather than
   * this refusal.
   */
  @Test
  public void updateWritesNothingWhenOneTargetIsPublished() throws Exception {
    HttpResponse<String> response = post("/command/inclusions-subgraph-update",
        requestBody(writableDraftTemplate.getId(), publishedTemplate.getId()));

    Assertions.assertEquals(400, response.statusCode(),
        "the published target must be refused before the first write, not after the draft beside it was "
            + "already rewritten");
    Assertions.assertEquals("publishedArtifactCanNotBeChanged",
        JsonMapper.STRICT_MAPPER.readTree(response.body()).path("errorKey").asText(), response.body());
  }

  /**
   * The preview carries each artifact's publication status, so a selector can offer no tick for a target
   * the update will refuse rather than let the caller choose one and meet the refusal afterwards.
   */
  @Test
  public void previewReportsThePublicationStatusOfEachTarget() throws Exception {
    HttpResponse<String> response = post("/command/inclusions-subgraph-preview", requestBody());

    Assertions.assertEquals(200, response.statusCode(), response.body());
    JsonNode templates = JsonMapper.STRICT_MAPPER.readTree(response.body()).get("templates");
    Assertions.assertEquals("bibo:published",
        templates.path(publishedTemplate.getId()).path("bibo:status").asText(), response.body());
    Assertions.assertEquals("bibo:draft",
        templates.path(writableDraftTemplate.getId()).path("bibo:status").asText(), response.body());
  }

  /**
   * The selector posts the preview straight back as the update request, and the update reads its body
   * strictly, so every member the preview writes has to be one the update accepts. Returned verbatim, a
   * preview plans no work: nothing in it is marked for update.
   */
  @Test
  public void updateAcceptsAPreviewResponseVerbatim() throws Exception {
    HttpResponse<String> preview = post("/command/inclusions-subgraph-preview", requestBody());
    Assertions.assertEquals(200, preview.statusCode(), preview.body());

    HttpResponse<String> response = post("/command/inclusions-subgraph-update", preview.body());

    Assertions.assertEquals(200, response.statusCode(),
        "the update rejected the body the preview had just produced, which is the body the selector "
            + "sends: " + response.body());
    Assertions.assertTrue(JsonMapper.STRICT_MAPPER.readTree(response.body()).get("outcomes").isEmpty(),
        response.body());
  }

  // ── fixtures and helpers ───────────────────────────────────────────────────

  /** A propagation request rooted at the source element, marking the named templates for update. */
  private static String requestBody(String... templateIdsToUpdate) {
    StringBuilder templates = new StringBuilder("{");
    for (String templateId : templateIdsToUpdate) {
      if (templates.length() > 1) {
        templates.append(',');
      }
      templates.append('"').append(templateId).append("\":{\"operation\":\"update\"}");
    }
    templates.append('}');
    return "{\"@id\":\"" + sourceElement.getId() + "\",\"templates\":" + templates + "}";
  }

  private static HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", user2AuthHeader)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static FolderServerArtifact create(FolderServerArtifact artifact, CedarResourceType type, String name,
                                             CedarFolderId parent) {
    return create(artifact, type, name, parent, BiboStatus.DRAFT);
  }

  private static FolderServerArtifact create(FolderServerArtifact artifact, CedarResourceType type, String name,
                                             CedarFolderId parent, BiboStatus publicationStatus) {
    artifact.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(type));
    artifact.setName(name);
    artifact.setDescription("Created by InclusionSubgraphAuthorizationTest");
    if (artifact instanceof FolderServerSchemaArtifact schema) {
      schema.setVersion("1.0.0");
      schema.setPublicationStatus(publicationStatus.getValue());
      schema.setLatestVersion(true);
      schema.setLatestDraftVersion(publicationStatus == BiboStatus.DRAFT);
      schema.setLatestPublishedVersion(publicationStatus == BiboStatus.PUBLISHED);
    }
    FolderServerArtifact created = CedarDataServices.getInstance().getFolderServiceSession(user1Context)
        .createResourceAsChildOfId(artifact, parent);
    Assertions.assertNotNull(created, "the fixture " + name + " should have been created");
    return created;
  }

  private static void grantToUser2(FolderServerArtifact artifact, ResourceRole permission) {
    ResourcePermissionsRequest request = new ResourcePermissionsRequest();
    request.setOwner(new ResourcePermissionUser(user1.getId()));
    request.getUserPermissions().add(new ResourcePermissionUserPermissionPair(
        new ResourcePermissionUser(user2.getId()), permission));
    BackendCallResult result = CedarDataServices.getInstance().getResourcePermissionServiceSession(user1Context)
        .updateResourcePermissions(artifact.getResourceId(), request);
    Assertions.assertFalse(result.isError(), "the grant should succeed");
  }

  private static void includes(FolderServerArtifact includer, FolderServerArtifact included) {
    boolean arcs = CedarDataServices.getInstance().getInclusionSubgraphServiceSession(user1Context)
        .updateInclusionArcs(includer.getResourceId(), List.of(included.getId()));
    Assertions.assertTrue(arcs, "the inclusion arc should have been created");
  }

}
