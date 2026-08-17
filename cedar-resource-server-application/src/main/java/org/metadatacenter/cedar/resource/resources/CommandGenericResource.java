package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.jsonldjava.core.JsonLdError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.keycloak.events.Event;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.security.AdminCommand;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.request.OutputFormatType;
import org.metadatacenter.model.request.OutputFormatTypeDetector;
import org.metadatacenter.model.trimmer.JsonLdDocument;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.UserServiceSession;
import org.metadatacenter.server.security.model.user.CedarSuperRole;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.security.model.user.CedarUserExtract;
import org.metadatacenter.server.security.util.CedarUserUtil;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Consumes;
import org.metadatacenter.constant.HttpConstants;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.util.artifact.ArtifactYamlTranscoder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.QP_FORMAT;
import static org.metadatacenter.constant.CedarQueryParameters.QP_RESOURCE_TYPE;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandGenericResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandGenericResource.class);
  private static UserService userService;

  public CommandGenericResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectUserService(UserService us) {
    userService = us;
  }

  private static Response doConvert(JsonNode resourceNode, OutputFormatType formatType) throws CedarException {
    Object responseObject = null;
    String mediaType = null;
    if (formatType == OutputFormatType.JSONLD) {
      responseObject = resourceNode;
      mediaType = MediaType.APPLICATION_JSON;
    } else if (formatType == OutputFormatType.JSON) {
      responseObject = getJsonString(resourceNode);
      mediaType = MediaType.APPLICATION_JSON;
    } else if (formatType == OutputFormatType.RDF_NQUAD) {
      responseObject = getRdfString(resourceNode);
      mediaType = "application/n-quads";
    } else {
      throw new CedarException("Programming error: no handler is programmed for format type: " + formatType) {
      };
    }
    return Response.ok(responseObject, mediaType).build();
  }

  private static JsonNode getJsonString(JsonNode resourceNode) {
    return new JsonLdDocument(resourceNode).asJson();
  }

  private static String getRdfString(JsonNode resourceNode) throws CedarException {
    try {
      return new JsonLdDocument(resourceNode).asRdf();
    } catch (JsonLdError e) {
      throw new CedarProcessingException("Error while converting the instance to RDF", e);
    }
  }

  // This is the endpoint called by the Keycloak Event Listener
  @POST
  @Timed
  @Path("/auth-user-callback")
  @Operation(summary = "Authentication user callback", description = "Endpoint called by the Keycloak Event Listener. Creates the CEDAR objects related to a user (home "
          + "folder, group membership) upon authentication. The caller must hold the user administration "
          + "permission, since the user to provision is named in the request body.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response authUserCallback() throws CedarException {
    CedarRequestContext adminContext = buildRequestContext();
    AdminCommand.AUTH_USER_CALLBACK.enforce(adminContext);

    JsonNode jsonBody = adminContext.request().getRequestBody().asJson();

    if (jsonBody != null) {
      try {
        Event event = JsonMapper.MAPPER.treeToValue(jsonBody.get("event"), Event.class);
        CedarUserExtract targetUser = JsonMapper.MAPPER.treeToValue(jsonBody.get("eventUser"), CedarUserExtract.class);

        String clientId = event.getClientId();
        if (cedarConfig.getKeycloakConfig().getResource().equals(clientId)) {
          CedarUser user = createUserRelatedObjects(userService, targetUser);
          CedarRequestContext userContext = CedarRequestContextFactory.fromUser(user);

          UserServiceSession userSession = dataServices.getUserServiceSession(userContext);
          userSession.addUserToEverybodyGroup(user.getResourceId());

          FolderServiceSession folderSession = dataServices.getFolderServiceSession(userContext);
          folderSession.ensureUserHomeExists();

          updateHomeFolderId(userContext, userService, user);
        }
      } catch (Exception e) {
        throw new CedarProcessingException(e);
      }
    }

    //TODO: return created url
    return Response.created(null).build();
  }

  private CedarUser createUserRelatedObjects(UserService userService, CedarUserExtract eventUser) throws CedarException {
    CedarUser existingUser = null;
    try {
      existingUser = userService.findUser(eventUser.getResourceId());
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

    if (existingUser != null) {
      return existingUser;
    }

    CedarUser user = CedarUserUtil.createUserFromBlueprint(cedarConfig.getBlueprintUserProfile(), eventUser, CedarSuperRole.NORMAL, cedarConfig,
        null);
    return userService.createUser(user);
  }

  private void updateHomeFolderId(CedarRequestContext cedarRequestContext, UserService userService, CedarUser user) {
    FolderServiceSession neoSession = dataServices.getFolderServiceSession(cedarRequestContext);

    FolderServerFolder userHomeFolder = neoSession.findHomeFolderOf();

    if (userHomeFolder != null) {
      user.setHomeFolderId(userHomeFolder.getId());
      try {
        userService.updateUser(user);
      } catch (Exception e) {
        log.error("Error while updating user: " + user.getEmail(), e);
      }
    }
  }

  @POST
  @Timed
  @Path("/convert")
  @Operation(summary = "Convert a resource", description = "Convert the resource supplied in the request body to the requested output format.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response convertResource(
      @Parameter(description = "Output format type to display the content of the template instance. The allowed values "
          + "are: 'jsonld', 'json', 'rdf-nquad'")
      @QueryParam(QP_FORMAT) Optional<String> format) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    //c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ); // XXX Need a permission to convert?

    OutputFormatType formatType = OutputFormatTypeDetector.detectFormat(format);
    JsonNode resourceNode = c.request().getRequestBody().asJson();

    return doConvert(resourceNode, formatType);
  }

  @POST
  @Timed
  @Path("/validate")
  @Operation(summary = "Validate resources", description = "Validate CEDAR resources (i.e., templates, elements and instances) against the CEDAR meta-model. To "
          + "use this service you will need to append the resource text in the request body as the payload. "
          + "However, in the case of validating the template instance, you have an additional option to include "
          + "the template text by organizing them as follows: { \"schema\": <template text>, \"instance\": "
          + "<instance text> }. The validation service will return a report in JSON format as follows: { "
          + "\"validates\": \"\", \"warnings\": [], \"errors\": [] }", tags = {"Validation", "Command"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  @Consumes({MediaType.APPLICATION_JSON, HttpConstants.CONTENT_TYPE_APPLICATION_YAML, "application/yaml"})
  public Response validateResource(
      @Parameter(description = "The type of CEDAR resource. The allowed values are: 'field', 'element', 'template', "
          + "'instance'", required = true)
      @QueryParam(QP_RESOURCE_TYPE) String resourceType, String requestBody) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    //c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE); // XXX Permission for validation?

    String url = microserviceUrlUtil.getArtifact().getValidateCommand(resourceType);

    // A YAML body is converted here rather than forwarded: the proxy sends JSON, and a client that
    // authors in YAML has to be able to ask whether its work is valid before sending it. Anything else
    // is forwarded as it always was, which keeps the JSON-only composite body — an instance together
    // with the template to validate it against — working unchanged.
    //
    // Outside the try, deliberately. A body that cannot be read is the client's mistake and answers
    // 400; inside, the catch below would turn that into a 500, which is the fault this route had in the
    // first place.
    //
    // The body is forwarded from the parameter rather than from the request context, for both
    // serializations: taking it as a parameter is what lets a YAML body be read at all, and it also
    // means the entity stream is spent by the time the context is asked, so the context would hand the
    // proxy nothing. `artifactRequestBodyAsJson` normalizes a JSON body exactly as the context did.
    String bodyForArtifactServer = artifactRequestBodyAsJson(requestBody, validatedResourceType(resourceType));

    try {
      ClassicHttpResponse proxyResponse = ProxyUtil.proxyPost(url, c, bodyForArtifactServer);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      return createServiceResponse(proxyResponse);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  /**
   * The artifact kind a YAML body is to be read as.
   *
   * <p>The parameter is the client's, so it can name nothing at all. A kind that does not resolve is
   * left to the artifact server to refuse, as it always did, rather than answered differently here for
   * having arrived as YAML: reading the body as a template is the least surprising thing to attempt,
   * and the refusal that follows is the one a JSON body of the same request would have got.
   */
  private CedarResourceType validatedResourceType(String resourceType) {
    CedarResourceType named = CedarResourceType.forValue(resourceType == null ? "" : resourceType);
    return named == null ? CedarResourceType.TEMPLATE : named;
  }

  private Response createServiceResponse(ClassicHttpResponse proxyResponse) throws IOException {
    HttpEntity entity = proxyResponse.getEntity();
    int statusCode = proxyResponse.getCode();
    String mediaType = entity.getContentType();
    return Response.status(statusCode).type(mediaType).entity(entity.getContent()).build();
  }

}
