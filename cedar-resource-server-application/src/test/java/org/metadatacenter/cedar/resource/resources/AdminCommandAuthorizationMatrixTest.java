package org.metadatacenter.cedar.resource.resources;

import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.resource.ResourceServerApplication;
import org.metadatacenter.cedar.resource.ResourceServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.server.search.elasticsearch.service.NoOpNodeIndexingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.search.util.IndexUtils;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.PermissionMatrix;
import org.metadatacenter.util.test.TestAuthUtil;

import java.util.Map;

import static org.metadatacenter.util.test.PermissionMatrix.Actor.ANONYMOUS;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OTHER_USER;
import static org.metadatacenter.util.test.PermissionMatrix.Actor.OWNER;

/**
 * The authorization gate on the resource server's administrative index/ontology commands, asserted as
 * a table so no command can quietly lose its gate.
 *
 * <p>Each of these commands carries its own inline {@code c.must(c.user()).have(...)} check rather than
 * sharing one policy, and that per-route pattern has already slipped twice:
 * {@code load-valuesets-ontology} once shipped with its check commented out (reachable by any logged-in
 * user), and {@code generate-empty-rules-index} once asked for {@code SEARCH_INDEX_REINDEX} instead of
 * {@code RULES_INDEX_REINDEX}. Both are fixed; this table is the regression net that fails the moment a
 * gate is dropped or points at the wrong permission again.
 *
 * <p>Every mutating command must refuse an anonymous caller with 401 and an authenticated non-admin with
 * 403. The two admin permissions ({@code SEARCH_INDEX_REINDEX}, {@code RULES_INDEX_REINDEX}) are granted
 * only by the {@code SEARCH_REINDEXER} role, which the test users do not hold, so both regular users are
 * genuinely unprivileged here. ADMIN is deliberately never probed: it would pass the gate, and the point
 * is to assert the gate without ever letting a command that wipes or rebuilds the index actually run.
 *
 * <p>The one non-mutating row — the status poll — is intentionally not admin-gated (it needs only a
 * logged-in user). Asserting a regular user's 200 there is what proves the 403s are about the missing
 * admin permission, not a blanket block on {@code /command}.
 */
public class AdminCommandAuthorizationMatrixTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars. Ports are
    // distinct from the dev server and from every other booting test class.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_RESOURCE_HTTP_PORT", "19077",
        "CEDAR_RESOURCE_ADMIN_PORT", "19177",
        "CEDAR_RESOURCE_STOP_PORT", "19277",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<ResourceServerConfiguration> SERVER =
      new DropwizardTestSupport<>(ResourceServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static Map<PermissionMatrix.Actor, String> actors;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_RESOURCE);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);

    TestAuthUtil.installInMemoryUserService(cedarConfig);
    // OWNER and OTHER_USER are simply two logged-in non-admin users here; there is no resource owner for
    // an admin command. Both must be refused. ANONYMOUS carries no Authorization header by contract.
    actors = Map.of(
        OWNER, TestAuthUtil.getTestUser1AuthHeader(cedarConfig),
        OTHER_USER, TestAuthUtil.getTestUser2AuthHeader(cedarConfig));

    EmbeddedCedarNeo4j.seed(cedarConfig);

    // The admin gate is evaluated before any command body runs, so indexing is a no-op here.
    AbstractResourceServerResource.injectServices(
        new NoOpNodeIndexingService(cedarConfig),
        new IndexUtils(cedarConfig).getNodeSearchingService(),
        new SearchPermissionEnqueueService(cedarConfig),
        new ValuerecommenderReindexQueueService(cedarConfig.getCacheConfig().getPersistent()));
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @Test
  public void adminIndexCommandsRefuseNonAdmins() {
    PermissionMatrix matrix = new PermissionMatrix("http://localhost:" + SERVER.getLocalPort(), actors);

    // The five mutating index/ontology commands: admin-only, so 401 for anonymous and 403 for either
    // regular user. No ADMIN row — that would pass the gate and run a destructive rebuild/wipe.
    for (String path : new String[] {
        "/command/load-valuesets-ontology",
        "/command/regenerate-search-index",
        "/command/generate-empty-search-index",
        "/command/regenerate-rules-index",
        "/command/generate-empty-rules-index"}) {
      matrix.when("POST", path)
          .expect(ANONYMOUS, 401)
          .expect(OWNER, 403)
          .expect(OTHER_USER, 403);
    }

    // The status poll is logged-in-only, not admin-gated: anonymous is still refused, but a regular user
    // is allowed. This is the control that shows the 403s above are the admin gate, not a blanket block.
    matrix.when("GET", "/command/load-valuesets-ontology-status")
        .expect(ANONYMOUS, 401)
        .expect(OWNER, 200)
        .expect(OTHER_USER, 200);

    matrix.verify();
  }

}
