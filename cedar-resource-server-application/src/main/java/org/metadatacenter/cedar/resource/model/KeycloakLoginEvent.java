package org.metadatacenter.cedar.resource.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.util.json.JsonMapper;

/**
 * The part of a Keycloak login event that the user provisioning callback reads.
 *
 * <p>CEDAR's Keycloak event listener posts the whole serialized Keycloak event, and the callback needs
 * only its client identifier, to confirm that the login came through CEDAR's own client. Reading the
 * event into this type rather than Keycloak's own keeps the Keycloak server SPI out of the resource
 * server. The read is tolerant, so a property a later Keycloak adds to its event cannot stop new users
 * being provisioned.
 */
public record KeycloakLoginEvent(String clientId) {

  public static KeycloakLoginEvent read(JsonNode event) throws JsonProcessingException {
    return JsonMapper.TOLERANT_MAPPER.treeToValue(event, KeycloakLoginEvent.class);
  }

  public boolean isFromClient(String expectedClientId) {
    return expectedClientId != null && expectedClientId.equals(clientId);
  }
}
