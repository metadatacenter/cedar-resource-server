package org.metadatacenter.cedar.resource.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.metadatacenter.util.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The user provisioning callback reads the login event CEDAR's Keycloak listener posts, and provisions
 * only when that event names CEDAR's own client.
 *
 * <p>The listener serializes Keycloak's whole event, so the callback receives every property Keycloak
 * defines, and a later Keycloak may define more. Each fixture below carries the ten properties Keycloak
 * 22's event serializes to, plus one it does not define, and the read must still succeed.
 */
class KeycloakLoginEventTest {

  private static final String CEDAR_CLIENT = "cedar-angular-app";

  private static JsonNode loginEvent(String clientIdProperty) throws Exception {
    return JsonMapper.STRICT_MAPPER.readTree("""
        {
          "id": "4f1c2d8e-0b7a-4e52-9a61-3c5d7e9f1a2b",
          "time": 1790000000000,
          "type": "LOGIN",
          "realmId": "CEDAR",
          %s
          "userId": "2d4f6a8c-1e3b-4d5f-8a7c-9b1d3f5e7a9c",
          "sessionId": "7a9c1e3b-5d7f-4a2c-8e6b-0d2f4a6c8e1b",
          "ipAddress": "127.0.0.1",
          "error": null,
          "details": {
            "auth_method": "openid-connect",
            "redirect_uri": "https://cedar.metadatacenter.orgx/",
            "username": "test1@test.com"
          },
          "propertyKeycloak22DoesNotDefine": {"nested": true}
        }
        """.formatted(clientIdProperty));
  }

  @Test
  void readsTheClientOfAnEventCarryingUndeclaredProperties() throws Exception {
    KeycloakLoginEvent event = KeycloakLoginEvent.read(loginEvent("\"clientId\": \"" + CEDAR_CLIENT + "\","));

    assertEquals(CEDAR_CLIENT, event.clientId());
    assertTrue(event.isFromClient(CEDAR_CLIENT));
  }

  @Test
  void aLoginThroughAnotherClientIsNotCedars() throws Exception {
    KeycloakLoginEvent event = KeycloakLoginEvent.read(loginEvent("\"clientId\": \"account-console\","));

    assertFalse(event.isFromClient(CEDAR_CLIENT));
  }

  @Test
  void anEventNamingNoClientIsNotCedars() throws Exception {
    assertFalse(KeycloakLoginEvent.read(loginEvent("\"clientId\": null,")).isFromClient(CEDAR_CLIENT));
    assertFalse(KeycloakLoginEvent.read(loginEvent("")).isFromClient(CEDAR_CLIENT));
  }

  @Test
  void noConfiguredClientMatchesNothing() throws Exception {
    assertFalse(KeycloakLoginEvent.read(loginEvent("\"clientId\": null,")).isFromClient(null));
  }
}
