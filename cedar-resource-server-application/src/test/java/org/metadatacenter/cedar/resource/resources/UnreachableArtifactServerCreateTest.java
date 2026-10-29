package org.metadatacenter.cedar.resource.resources;

import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.neo4j.Neo4jConfig;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;
import org.metadatacenter.util.test.TestHttpClient;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A create sent while the artifact server refuses connections never reached it, so it stored nothing.
 * The create is answered as the outage it is, and leaves no cleanup job behind for a person to inspect.
 */
@Tag("integration")
class UnreachableArtifactServerCreateTest {

  static {
    int closedPort;
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_ARTIFACT_SERVER_HOST", "127.0.0.1",
        "CEDAR_ARTIFACT_HTTP_PORT", Integer.toString(closedPort),
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1",
        "CEDAR_OPENSEARCH_REST_PORT", "1"));
  }

  private static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static CedarConfig cedarConfig;
  private static String userAuthHeader;
  private static CedarFolderId homeFolderId;

  @BeforeAll
  static void start() throws Exception {
    SERVER.before();
    cedarConfig = CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE));
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    userAuthHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);
    homeFolderId = CedarDataServices.getInstance()
        .getFolderServiceSession(CedarRequestContextFactory.fromUser(TestAuthUtil.getTestUser1(cedarConfig)))
        .findHomeFolderOf().getResourceId();
  }

  @AfterAll
  static void stop() {
    SERVER.after();
  }

  @Test
  void aCreateByPostLeavesNoCleanupJob() throws Exception {
    long since = graphTime();
    HttpResponse<String> response = send("POST", "/templates", template(null, "Minted while unreachable"));
    Assertions.assertEquals(503, response.statusCode(), response.body());
    assertNothingLeft(since, "Minted while unreachable");
  }

  @Test
  void aCreateAtAChosenIdentityLeavesNoCleanupJob() throws Exception {
    String id = cedarConfig.getLinkedDataUtil().buildNewLinkedDataId(CedarResourceType.TEMPLATE);
    long since = graphTime();
    HttpResponse<String> response = send("PUT", "/templates/" + encode(id), template(id, "Chosen while unreachable"));
    Assertions.assertEquals(503, response.statusCode(), response.body());
    assertNothingLeft(since, "Chosen while unreachable");
  }

  /**
   * The graph's clock, which stamps every cleanup job. Other test classes share this JVM's graph and
   * leave jobs of their own in it, so only the jobs created since the request began are this create's.
   */
  private static long graphTime() {
    try (var driver = graph(); var session = driver.session()) {
      return session.run("RETURN timestamp() AS now").single().get("now").asLong();
    }
  }

  private static void assertNothingLeft(long since, String name) {
    try (var driver = graph(); var session = driver.session()) {
      Assertions.assertEquals(0, session.run("MATCH (j:CedarArtifactCreateCleanup) WHERE j.createdAt >= $since "
          + "RETURN count(j) AS n", Map.of("since", since)).single().get("n").asLong(),
          "a create that reached nothing left a cleanup job");
      Assertions.assertEquals(0, session.run("MATCH (a:Artifact) WHERE a.`schema:name` = $name RETURN count(a) AS n",
          Map.of("name", name)).single().get("n").asLong(), "a create that reached nothing was registered");
    }
  }

  private static org.neo4j.driver.Driver graph() {
    Neo4jConfig neo = Neo4jConfig.fromCedarConfig(cedarConfig);
    return GraphDatabase.driver(neo.getUri(), AuthTokens.basic(neo.getUserName(), neo.getUserPassword()));
  }

  private static String template(String id, String name) {
    TemplateSchemaArtifact.Builder builder = TemplateSchemaArtifact.builder()
        .withName(name)
        .withDescription("Unreachable artifact server fixture")
        .withVersion(Version.fromString("0.0.1"))
        .withStatus(Status.DRAFT);
    if (id != null) {
      builder.withJsonLdId(URI.create(id));
    }
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(builder.build()).toString();
  }

  private static HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path + "?folder_id="
            + encode(homeFolderId.getId())))
        .header("Authorization", userAuthHeader)
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build();
    return TestHttpClient.send(request);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
