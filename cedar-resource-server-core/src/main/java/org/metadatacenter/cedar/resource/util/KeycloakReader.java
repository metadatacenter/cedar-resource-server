package org.metadatacenter.cedar.resource.util;

import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;
import org.codehaus.jackson.jaxrs.JacksonJsonProvider;
import org.codehaus.jackson.map.DeserializationContext;
import org.codehaus.jackson.map.DeserializationProblemHandler;
import org.codehaus.jackson.map.JsonDeserializer;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.idm.UserRepresentation;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.KeycloakConstants;

import java.io.IOException;
import java.io.InputStream;

public class KeycloakReader {

  protected CedarConfig cedarConfig;

  protected String adminUserUUID;
  protected String keycloakBaseURI;
  protected String keycloakRealmName;
  protected String cedarAdminUserPassword;
  protected String keycloakClientId;
  protected String cedarAdminUserName;

  public KeycloakReader(CedarConfig cedarConfig) {
    this.cedarConfig = cedarConfig;
  }

  public void initKeycloak() {
    adminUserUUID = cedarConfig.getAdminUserConfig().getUuid();

    cedarAdminUserName = cedarConfig.getAdminUserConfig().getUserName();
    cedarAdminUserPassword = cedarConfig.getAdminUserConfig().getPassword();
    keycloakClientId = cedarConfig.getKeycloakConfig().getClientId();

    InputStream keycloakConfig = Thread.currentThread().getContextClassLoader().getResourceAsStream(KeycloakConstants
        .JSON);
    KeycloakDeployment keycloakDeployment = KeycloakDeploymentBuilder.build(keycloakConfig);

    keycloakRealmName = keycloakDeployment.getRealm();
    keycloakBaseURI = keycloakDeployment.getAuthServerBaseUrl();
  }

  private JacksonJsonProvider getCustomizedJacksonJsonProvider() {
    ObjectMapper m = new ObjectMapper();
    JacksonJsonProvider jacksonJsonProvider =
        new JacksonJaxbJsonProvider();
    jacksonJsonProvider.setMapper(m);
    m.getDeserializationConfig().addHandler(new DeserializationProblemHandler() {
      @Override
      public boolean handleUnknownProperty(DeserializationContext ctxt, JsonDeserializer<?> deserializer, Object
          beanOrClass, String propertyName) throws IOException, JsonProcessingException {
        if ("access_token".equals(propertyName)) {
          if (beanOrClass instanceof AccessTokenResponse) {
            AccessTokenResponse atr = (AccessTokenResponse) beanOrClass;
            String text = ctxt.getParser().getText();
            atr.setToken(text);
          }
          return true;
        } else {
          boolean success = super.handleUnknownProperty(ctxt, deserializer, beanOrClass, propertyName);
          return true;
        }
      }
    });
    return jacksonJsonProvider;
  }

  protected Keycloak buildKeycloak() {
    JacksonJsonProvider jacksonJsonProvider = getCustomizedJacksonJsonProvider();

    ResteasyClient resteasyClient = new ResteasyClientBuilder().connectionPoolSize(10).register(jacksonJsonProvider)
        .build();

    return KeycloakBuilder.builder()
        .serverUrl(keycloakBaseURI)
        .realm(keycloakRealmName)
        .username(cedarAdminUserName)
        .password(cedarAdminUserPassword)
        .clientId(keycloakClientId)
        .resteasyClient(resteasyClient)
        .build();
  }

  public UserRepresentation getUserFromKeycloak(String userId) {
    Keycloak kc = buildKeycloak();
    UserResource userResource = kc.realm(keycloakRealmName).users().get(userId);
    return userResource.toRepresentation();
  }

}