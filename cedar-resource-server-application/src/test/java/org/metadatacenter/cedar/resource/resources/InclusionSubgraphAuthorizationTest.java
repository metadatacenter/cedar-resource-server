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
import org.metadatacenter.server.security.model.permission.resource.FilesystemResourcePermission;
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
 * <p>The graph query behind the affected tree matches on the INCLUDES arc alone and carries no permission
 * clause, so it returns every including artifact in the system. That is the fact these tests are built
 * around: the endpoints have to do the filtering the query does not, and both once failed to. The preview
 * returned artifacts the caller could not read, together with their names and owners; the update wrote
 * every target the caller named, having checked read access to the source and nothing else.
 *
 * <p>The fixtures are one element and two templates that include it. User 2 can read one template and not
 * the other, and can write neither — the arrangement that separates "may not see it" from "may see it but
 * may not change it", which the two failures respectively allowed.
 *
 * <p>No artifact server runs here, and none is needed: every case asserted below must be settled before
 * the first write goes out. A test that reached the artifact server would be recording the bug.
 */
public class InclusionSubgraphAuthorizationTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // distinct from the dev server and from every other booting test class in this module.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19087",
        "CEDAR_RESOURCE_ADMIN_PORT", "19187",
        "CEDAR_RESOURCE_STOP_PORT", "19287",
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

    grantToUser2(sourceElement, FilesystemResourcePermission.READ);
    grantToUser2(readableTemplate, FilesystemResourcePermission.READ);

    // Both templates include the element. The arc runs from the including artifact to the included one.
    includes(readableTemplate, sourceElement);
    includes(invisibleTemplate, sourceElement);
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
    HttpResponse<String> response = post("/command/inclusions-subgraph-preview", requestBody(null));

    Assertions.assertEquals(200, response.statusCode(), "user 2 may read the source element");
    JsonNode templates = JsonMapper.MAPPER.readTree(response.body()).get("templates");
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
    JsonNode outcomes = JsonMapper.MAPPER.readTree(response.body()).get("outcomes");
    Assertions.assertTrue(outcomes.isEmpty(),
        "nothing should have been planned for a template user 2 cannot even see, but the response reported "
            + outcomes);
  }

  // ── fixtures and helpers ───────────────────────────────────────────────────

  /** A propagation request rooted at the source element, optionally marking one template for update. */
  private static String requestBody(String templateIdToUpdate) {
    String templates = templateIdToUpdate == null ? "{}"
        : "{\"" + templateIdToUpdate + "\":{\"operation\":\"update\"}}";
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
    artifact.setId(cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(type));
    artifact.setName(name);
    artifact.setDescription("Created by InclusionSubgraphAuthorizationTest");
    if (artifact instanceof FolderServerSchemaArtifact schema) {
      schema.setVersion("1.0.0");
      schema.setPublicationStatus("bibo:draft");
      schema.setLatestVersion(true);
      schema.setLatestDraftVersion(true);
      schema.setLatestPublishedVersion(false);
    }
    FolderServerArtifact created = CedarDataServices.getInstance().getFolderServiceSession(user1Context)
        .createResourceAsChildOfId(artifact, parent);
    Assertions.assertNotNull(created, "the fixture " + name + " should have been created");
    return created;
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

  private static void includes(FolderServerArtifact includer, FolderServerArtifact included) {
    boolean arcs = CedarDataServices.getInstance().getInclusionSubgraphServiceSession(user1Context)
        .updateInclusionArcs(includer.getResourceId(), List.of(included.getId()));
    Assertions.assertTrue(arcs, "the inclusion arc should have been created");
  }

}
