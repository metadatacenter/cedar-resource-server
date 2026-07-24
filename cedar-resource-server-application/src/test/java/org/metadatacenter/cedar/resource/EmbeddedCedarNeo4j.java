package org.metadatacenter.cedar.resource;

import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.util.test.TestAuthUtil;
import org.metadatacenter.util.test.TestUtil;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.util.HashMap;
import java.util.Map;

/**
 * An in-process Neo4j for integration tests, replacing the live graph database. Call
 * startAndRedirectEnvironment from a static initializer, before the DropwizardAppRule starts the
 * application: it boots the embedded server on a random port (so it can never collide with, or
 * write into, a real Neo4j) and redirects the CEDAR Neo4j environment variables, which the
 * application reads when it builds its configuration. The embedded server runs without
 * authentication, so the configured credentials are accepted as-is.
 *
 * After the application has started, call seed to create the graph skeleton the way
 * provisioning does: the global objects (root folders, Everybody group, root category) and the
 * test users with their home folders and Everybody membership.
 */
public final class EmbeddedCedarNeo4j {

  private static Neo4j embedded;

  private EmbeddedCedarNeo4j() {
  }

  public static synchronized void startAndRedirectEnvironment() {
    if (embedded == null) {
      embedded = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
      Map<String, String> environment = new HashMap<>(System.getenv());
      environment.put("CEDAR_NEO4J_HOST", embedded.boltURI().getHost());
      environment.put("CEDAR_NEO4J_BOLT_PORT", String.valueOf(embedded.boltURI().getPort()));
      // Alternate server ports, so the test instance never collides with a running dev server
      environment.put("CEDAR_RESOURCE_HTTP_PORT", "19007");
      environment.put("CEDAR_RESOURCE_ADMIN_PORT", "19107");
      environment.put("CEDAR_RESOURCE_STOP_PORT", "19207");
      TestUtil.setEnv(environment);
    }
  }

  public static void seed(CedarConfig cedarConfig) throws Exception {
    CedarUser admin = TestAuthUtil.getAdminUser(cedarConfig);
    CedarDataServices.getNeoUserService().createUser(admin);
    CedarRequestContext adminContext = CedarRequestContextFactory.fromUser(admin);
    CedarDataServices.getAdminServiceSession(adminContext).ensureGlobalObjectsExists();

    seedUser(TestAuthUtil.getTestUser1(cedarConfig));
    seedUser(TestAuthUtil.getTestUser2(cedarConfig));
  }

  private static void seedUser(CedarUser user) throws Exception {
    CedarDataServices.getNeoUserService().createUser(user);
    CedarRequestContext context = CedarRequestContextFactory.fromUser(user);
    CedarDataServices.getUserServiceSession(context).addUserToEverybodyGroup(user.getResourceId());
    CedarDataServices.getFolderServiceSession(context).ensureUserHomeExists();
  }

}
