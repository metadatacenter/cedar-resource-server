package org.metadatacenter.cedar.resource.config;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariable;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.test.TestUtil;

import java.util.HashMap;
import java.util.Map;

public class CedarConfigResourceTest {

  @Before
  public void setEnvironment() {
    Map<String, String> env = new HashMap<>();

    env.put(CedarEnvironmentVariable.CEDAR_HOST.getName(), "metadatacenter.orgx");

    env.put(CedarEnvironmentVariable.CEDAR_NET_GATEWAY.getName(), "127.0.0.1");

    env.put(CedarEnvironmentVariable.CEDAR_ADMIN_USER_API_KEY.getName(), "1234");

    env.put(CedarEnvironmentVariable.CEDAR_NEO4J_USER_NAME.getName(), "neo4j");
    env.put(CedarEnvironmentVariable.CEDAR_NEO4J_USER_PASSWORD.getName(), "password");
    env.put(CedarEnvironmentVariable.CEDAR_NEO4J_HOST.getName(), "127.0.0.1");
    env.put(CedarEnvironmentVariable.CEDAR_NEO4J_BOLT_PORT.getName(), "7687");

    env.put(CedarEnvironmentVariable.CEDAR_MONGO_APP_USER_NAME.getName(), "cedarUser");
    env.put(CedarEnvironmentVariable.CEDAR_MONGO_APP_USER_PASSWORD.getName(), "password");
    env.put(CedarEnvironmentVariable.CEDAR_MONGO_HOST.getName(), "localhost");
    env.put(CedarEnvironmentVariable.CEDAR_MONGO_PORT.getName(), "27017");

    env.put(CedarEnvironmentVariable.CEDAR_REDIS_PERSISTENT_HOST.getName(), "127.0.0.1");
    env.put(CedarEnvironmentVariable.CEDAR_REDIS_PERSISTENT_PORT.getName(), "6379");

    env.put(CedarEnvironmentVariable.CEDAR_SALT_API_KEY.getName(), "saltme");

    env.put(CedarEnvironmentVariable.CEDAR_SUBMISSION_TEMPLATE_ID_1.getName(), "http://template-id-1");
    env.put(CedarEnvironmentVariable.CEDAR_SUBMISSION_TEMPLATE_ID_2.getName(), "http://template-id-2");

    env.put(CedarEnvironmentVariable.CEDAR_TRUSTED_FOLDERS.getName(),
        "{\\\"caDSR\\\":[\\\"https://repo.metadatacenter.orgx/folders/c3a7b03c-87bb-49c4-b311-2eb1bd398c4e\\\"]}");

    env.put(CedarEnvironmentVariable.CEDAR_OPENSEARCH_HOST.getName(), "127.0.0.1");
    env.put(CedarEnvironmentVariable.CEDAR_OPENSEARCH_TRANSPORT_PORT.getName(), "9300");

    env.put(CedarEnvironmentVariable.CEDAR_RESOURCE_HTTP_PORT.getName(), "9007");
    env.put(CedarEnvironmentVariable.CEDAR_RESOURCE_ADMIN_PORT.getName(), "9107");
    env.put(CedarEnvironmentVariable.CEDAR_RESOURCE_STOP_PORT.getName(), "9207");

    env.put(CedarEnvironmentVariable.CEDAR_ARTIFACT_HTTP_PORT.getName(), "9001");
    env.put(CedarEnvironmentVariable.CEDAR_USER_HTTP_PORT.getName(), "9005");

    TestUtil.setEnv(env);
  }

  @Test
  public void testGetInstance() throws Exception {
    SystemComponent systemComponent = SystemComponent.SERVER_RESOURCE;
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(systemComponent);
    CedarConfig instance = CedarConfig.getInstance(environment);
    Assert.assertNotNull(instance);
  }

}
