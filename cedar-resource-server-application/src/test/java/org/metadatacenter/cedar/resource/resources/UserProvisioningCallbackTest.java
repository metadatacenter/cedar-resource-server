package org.metadatacenter.cedar.resource.resources;

import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarGroupId;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionOutboxReader;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.security.model.user.CedarUserExtract;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

/**
 * First-login provisioning. When a user logs in through CEDAR's client, the Keycloak event listener
 * posts the login event to {@code /command/auth-user-callback} with the administrator's key, and the
 * resource server creates what a new account needs: the user record, membership of Everybody, and a
 * home folder whose identifier the user record keeps. A repeated login must change none of it.
 */
@Tag("integration")
public class UserProvisioningCallbackTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // assigned by the OS, so they cannot collide with the dev server or another test in this module.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "0",
        "CEDAR_RESOURCE_ADMIN_PORT", "0",
        "CEDAR_RESOURCE_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1",
        "CEDAR_OPENSEARCH_HOST", "127.0.0.1",
        "CEDAR_OPENSEARCH_REST_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static CedarConfig cedarConfig;
  private static UserService users;
  private static SearchPermissionOutboxReader outbox;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    EmbeddedCedarNeo4j.seed(cedarConfig);

    // The callback creates the user through the service the application was given at boot, which keeps
    // users in the graph, as a deployment does. Requests authenticate through the in-memory one.
    users = CedarDataServices.getInstance().getNeoUserService();
    outbox = new SearchPermissionOutboxReader(cedarConfig);
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        outbox.enqueueService());
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
    if (outbox != null) {
      outbox.close();
    }
  }

  @Test
  public void aNewUsersFirstLoginProvisionsTheirAccountOnce() throws Exception {
    CedarUserExtract newcomer = new CedarUserExtract(
        cedarConfig.getLinkedDataUtil().getUserId(UUID.randomUUID().toString()), "New", "Comer",
        "new.comer@example.org");
    Assertions.assertNull(users.findUser(newcomer.getResourceId()), "the user should not exist yet");

    HttpResponse<String> first = callback(newcomer, cedarConfig.getKeycloakConfig().getResource());
    Assertions.assertEquals(201, first.statusCode(), first.body());

    CedarUser created = users.findUser(newcomer.getResourceId());
    Assertions.assertNotNull(created, "the login should have created the user record");
    Assertions.assertEquals("new.comer@example.org", created.getEmail());
    CedarRequestContext asNewcomer = CedarRequestContextFactory.fromUser(created);
    FolderServerFolder home = CedarDataServices.getInstance().getFolderServiceSession(asNewcomer).findHomeFolderOf();
    Assertions.assertNotNull(home, "the login should have created the user's home folder");
    Assertions.assertEquals(home.getId(), created.getHomeFolderId(),
        "the user record should keep the identifier of the home folder the login created");
    Assertions.assertNotNull(CedarDataServices.getInstance().getUserServiceSession(asNewcomer)
        .getUser(newcomer.getResourceId()), "the login should have created the user's graph node");
    Assertions.assertEquals(1, everybodyMembershipsOf(asNewcomer, newcomer.getId()),
        "the login should have made the user a member of Everybody");

    HttpResponse<String> second = callback(newcomer, cedarConfig.getKeycloakConfig().getResource());
    Assertions.assertEquals(201, second.statusCode(), second.body());

    CedarUser afterSecondLogin = users.findUser(newcomer.getResourceId());
    Assertions.assertEquals(JsonMapper.STRICT_MAPPER.writeValueAsString(created),
        JsonMapper.STRICT_MAPPER.writeValueAsString(afterSecondLogin), "a second login must not change the user record");
    Assertions.assertEquals(home.getId(),
        CedarDataServices.getInstance().getFolderServiceSession(asNewcomer).findHomeFolderOf().getId(),
        "a second login must keep the same home folder");
    Assertions.assertEquals(1, everybodyMembershipsOf(asNewcomer, newcomer.getId()),
        "a second login must not add a second membership of Everybody");
  }

  /** A login through another Keycloak client, such as the account console, provisions nothing. */
  @Test
  public void aLoginThroughAnotherClientProvisionsNothing() throws Exception {
    CedarUserExtract stranger = new CedarUserExtract(
        cedarConfig.getLinkedDataUtil().getUserId(UUID.randomUUID().toString()), "Other", "Client",
        "other.client@example.org");

    HttpResponse<String> response = callback(stranger, "account-console");

    Assertions.assertEquals(201, response.statusCode(), response.body());
    Assertions.assertNull(users.findUser(stranger.getResourceId()),
        "a login through another client should not create a user record");
  }

  private static long everybodyMembershipsOf(CedarRequestContext context, String userId) {
    var groups = CedarDataServices.getInstance().getGroupServiceSession(context);
    String everybodyId = groups.findGroupByName(cedarConfig.getFolderStructureConfig().getEverybodyGroup().getName())
        .getId();
    return groups.findGroupUsers(CedarGroupId.build(everybodyId)).getUsers().stream()
        .filter(member -> userId.equals(member.getUser().getId()))
        .count();
  }

  /** The body CEDAR's Keycloak event listener posts: Keycloak's login event, and the user it concerns. */
  private static HttpResponse<String> callback(CedarUserExtract user, String clientId) throws Exception {
    String body = """
        {
          "event": {
            "id": "%s",
            "time": 1790000000000,
            "type": "LOGIN",
            "realmId": "CEDAR",
            "clientId": "%s",
            "userId": "%s",
            "sessionId": "%s",
            "ipAddress": "127.0.0.1",
            "error": null,
            "details": {"auth_method": "openid-connect", "username": "%s"}
          },
          "eventUser": %s
        }
        """.formatted(UUID.randomUUID(), clientId, UUID.randomUUID(), UUID.randomUUID(), user.getEmail(),
        JsonMapper.STRICT_MAPPER.writeValueAsString(user));
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/command/auth-user-callback"))
        .header("Authorization", TestAuthUtil.getAdminUserAuthHeader(cedarConfig))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
