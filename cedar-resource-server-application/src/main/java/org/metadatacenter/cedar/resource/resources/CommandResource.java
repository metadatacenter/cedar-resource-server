package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.FolderServerProxy;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerResource;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.UserServiceSession;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.security.model.user.CedarUserExtract;
import org.metadatacenter.server.security.model.user.CedarUserRole;
import org.metadatacenter.server.security.util.CedarUserUtil;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource extends AbstractResourceServerResource {

  protected static final String MOVE_COMMAND = "command/move-node-to-folder";

  private static UserService userService;

  public CommandResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectUserService(UserService us) {
    userService = us;
  }

  @POST
  @Timed
  @Path("/copy-resource-to-folder")
  public Response copyResourceToFolder() throws CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    JsonNode jsonBody = c.request().getRequestBody().asJson();

    String id = jsonBody.get("@id").asText();
    String nodeTypeString = jsonBody.get("nodeType").asText();
    String folderId = jsonBody.get("folderId").asText();
    String titleTemplate = jsonBody.get("titleTemplate").asText();

    CedarNodeType nodeType = CedarNodeType.forValue(nodeTypeString);
    if (nodeType == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.UNKNOWN_NODE_TYPE)
          .parameter("nodeType", nodeTypeString)
          .errorMessage("Unknown nodeType:" + nodeTypeString + ":")
          .build();
    }

    CedarPermission permission1 = null;
    CedarPermission permission2 = null;
    switch (nodeType) {
      case FIELD:
        permission1 = CedarPermission.TEMPLATE_FIELD_READ;
        permission2 = CedarPermission.TEMPLATE_FIELD_CREATE;
        break;
      case ELEMENT:
        permission1 = CedarPermission.TEMPLATE_ELEMENT_READ;
        permission2 = CedarPermission.TEMPLATE_ELEMENT_CREATE;
        break;
      case TEMPLATE:
        permission1 = CedarPermission.TEMPLATE_READ;
        permission2 = CedarPermission.TEMPLATE_CREATE;
        break;
      case INSTANCE:
        permission1 = CedarPermission.TEMPLATE_INSTANCE_READ;
        permission2 = CedarPermission.TEMPLATE_INSTANCE_CREATE;
        break;
    }

    if (permission1 == null || permission2 == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.UNKNOWN_NODE_TYPE)
          .errorMessage("Unknown node type:" + nodeTypeString)
          .parameter("nodeType", nodeTypeString)
          .build();
    }

    // Check read permission
    c.must(c.user()).have(permission1);

    // Check create permission
    c.must(c.user()).have(permission2);

    if (!userHasReadAccessToResource(folderBase, id)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_RESOURCE)
          .errorMessage("You do not have read access to the source resource")
          .parameter("sourceId", id)
          .build();
    }

    // Check if the user has write permission to the target folder
    if (!userHasWriteAccessToFolder(folderBase, folderId)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_FOLDER)
          .errorMessage("You do not have read access to the target folder")
          .parameter("folderId", folderId)
          .build();
    }

    String originalDocument = null;
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + CedarUrlUtil.urlEncode(id);
      System.out.println(url);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        originalDocument = EntityUtils.toString(entity);
        JsonNode jsonNode = JsonMapper.MAPPER.readTree(originalDocument);
        ((ObjectNode) jsonNode).remove("@id");
        JsonNode titleNode = ((ObjectNode) jsonNode).at("/_ui/title");
        if (!titleNode.isMissingNode()) {
          String newTitle = titleTemplate.replace("{{title}}", titleNode.asText());
          JsonNode ui = jsonNode.get("_ui");
          if (ui != null) {
            ((ObjectNode) ui).put("title", newTitle);
          }
        }
        originalDocument = jsonNode.toString();
      }
    } catch (Exception e) {
      throw new CedarAssertionException(e);
    }

    /*if (originalDocument != null) {
      System.out.println("Original document:");
      System.out.println(originalDocument);
    }*/

    // TODO : from this point, this block is repeated 90% in:
    // AbstractResourceServerController.executeResourcePostByProxy
    // refactor, if possible
    try {
      FolderServerFolder targetFolder = getCedarFolderById(folderId);
      if (targetFolder == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
            .errorMessage("The folder with the given id can not be found!")
            .parameter("folderId", folderId)
            .build();
      }

      String url = templateBase + nodeType.getPrefix();

      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, request, originalDocument);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // resource was not created
        return generateStatusResponse(proxyResponse);
      } else {
        // resource was created
        HttpEntity entity = proxyResponse.getEntity();
        if (entity != null) {
          Header locationHeader = proxyResponse.getFirstHeader(HttpHeaders.LOCATION);
          String entityContent = EntityUtils.toString(entity);
          JsonNode jsonNode = JsonMapper.MAPPER.readTree(entityContent);
          String createdId = jsonNode.get("@id").asText();

          String resourceUrl = folderBase + PREFIX_RESOURCES;
          //System.out.println(resourceUrl);
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", targetFolder.getId());
          resourceRequestBody.put("id", createdId);
          resourceRequestBody.put("nodeType", nodeType.getValue());
          resourceRequestBody.put("name", extractNameFromResponseObject(nodeType, jsonNode));
          resourceRequestBody.put("description", extractDescriptionFromResponseObject(nodeType, jsonNode));
          String resourceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(resourceRequestBody);

          HttpResponse resourceCreateResponse = ProxyUtil.proxyPost(resourceUrl, request,
              resourceRequestBodyAsString);
          int resourceCreateStatusCode = resourceCreateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceCreateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_CREATED == resourceCreateStatusCode) {
              if (locationHeader != null) {
                response.setHeader(locationHeader.getName(), locationHeader.getValue());
              }
              if (proxyResponse.getEntity() != null) {
                // index the resource that has been created
                searchService.indexResource(JsonMapper.MAPPER.readValue
                        (resourceCreateResponse.getEntity().getContent(), FolderServerResource.class), jsonNode,
                    request);
                // TODO: we should return a URL in this call.
                return Response.created(null).entity(proxyResponse.getEntity().getContent()).build();
              } else {
                return Response.ok().build();
              }
            } else {
              return Response.status(resourceCreateStatusCode).entity(resourceEntity.getContent()).build();
            }
          } else {
            return Response.status(resourceCreateStatusCode).build();
          }
        } else {
          return Response.ok().build();
        }
      }
    } catch (Exception e) {
      throw new CedarAssertionException(e);
    }
  }

  @POST
  @Timed
  @Path("/move-node-to-folder ")
  public Response moveNodeToFolder() throws CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    JsonNode jsonBody = c.request().getRequestBody().asJson();

    String sourceId = jsonBody.get("sourceId").asText();
    String nodeTypeString = jsonBody.get("nodeType").asText();
    String folderId = jsonBody.get("folderId").asText();

    CedarNodeType nodeType = CedarNodeType.forValue(nodeTypeString);
    if (nodeType == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.UNKNOWN_NODE_TYPE)
          .parameter("nodeType", nodeTypeString)
          .errorMessage("Unknown nodeType:" + nodeTypeString + ":")
          .build();
    }

    CedarPermission permission1 = null;
    CedarPermission permission2 = null;
    switch (nodeType) {
      case FIELD:
        permission1 = CedarPermission.TEMPLATE_FIELD_CREATE;
        permission2 = CedarPermission.TEMPLATE_FIELD_DELETE;
        break;
      case ELEMENT:
        permission1 = CedarPermission.TEMPLATE_ELEMENT_CREATE;
        permission2 = CedarPermission.TEMPLATE_ELEMENT_DELETE;
        break;
      case TEMPLATE:
        permission1 = CedarPermission.TEMPLATE_CREATE;
        permission2 = CedarPermission.TEMPLATE_DELETE;
        break;
      case INSTANCE:
        permission1 = CedarPermission.TEMPLATE_INSTANCE_CREATE;
        permission2 = CedarPermission.TEMPLATE_INSTANCE_DELETE;
        break;
      case FOLDER:
        permission1 = CedarPermission.FOLDER_CREATE;
        permission2 = CedarPermission.FOLDER_DELETE;
        break;
    }

    if (permission1 == null || permission2 == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.UNKNOWN_NODE_TYPE)
          .errorMessage("Unknown node type:" + nodeTypeString)
          .parameter("nodeType", nodeTypeString)
          .build();
    }

    // Check read permission
    c.must(c.user()).have(permission1);

    // Check create permission
    c.must(c.user()).have(permission2);

    String folderURL = folderBase + CedarNodeType.Prefix.FOLDERS;

    // Check if the source node exists
    if (nodeType == CedarNodeType.FOLDER) {
      FolderServerFolder sourceFolder = FolderServerProxy.getFolder(folderURL, sourceId, request);
      if (sourceFolder == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.SOURCE_FOLDER_NOT_FOUND)
            .errorMessage("The source folder can not be found:" + sourceId)
            .parameter("sourceId", sourceId)
            .build();
      }
    } else {
      String resourceURL = folderBase + "/" + PREFIX_RESOURCES;
      FolderServerResource sourceResource = FolderServerProxy.getResource(resourceURL, sourceId, request);
      if (sourceResource == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.SOURCE_RESOURCE_NOT_FOUND)
            .errorMessage("The source resource can not be found:" + sourceId)
            .parameter("sourceId", sourceId)
            .build();
      }
    }

    // Check if the target folder exists
    FolderServerFolder targetFolder = FolderServerProxy.getFolder(folderURL, folderId, request);
    if (targetFolder == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.TARGET_FOLDER_NOT_FOUND)
          .errorMessage("The target folder can not be found:" + folderId)
          .parameter("folderId", folderId)
          .build();
    }

    // Check if the user has write/delete permission to the source node
    if (nodeType == CedarNodeType.FOLDER) {
      if (!userHasWriteAccessToFolder(folderBase, sourceId)) {
        return CedarResponse.forbidden()
            .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_FOLDER)
            .errorMessage("You do not have write access to the source folder")
            .parameter("sourceId", sourceId)
            .build();
      }
    } else {
      if (!userHasWriteAccessToResource(folderBase, sourceId)) {
        return CedarResponse.forbidden()
            .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_RESOURCE)
            .errorMessage("You do not have write access to the source resource")
            .parameter("sourceId", sourceId)
            .build();
      }
    }

    // Check if the user has write permission to the target folder
    if (!userHasWriteAccessToFolder(folderBase, folderId)) {
      return CedarResponse.forbidden()
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_FOLDER)
          .errorMessage("You do not have write access to the target folder")
          .parameter("folderId", folderId)
          .build();
    }

    try {
      String resourceUrl = folderBase + MOVE_COMMAND;
      ObjectNode folderRequestBody = JsonNodeFactory.instance.objectNode();

      folderRequestBody.put("sourceId", sourceId);
      folderRequestBody.put("nodeType", nodeTypeString);
      folderRequestBody.put("folderId", folderId);

      String folderRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(folderRequestBody);

      HttpResponse nodeMoveResponse = ProxyUtil.proxyPost(resourceUrl, request, folderRequestBodyAsString);
      int nodeMoveStatusCode = nodeMoveResponse.getStatusLine().getStatusCode();
      HttpEntity folderEntity = nodeMoveResponse.getEntity();
      if (folderEntity != null) {
        if (HttpStatus.SC_CREATED == nodeMoveStatusCode) {
          if (folderEntity.getContent() != null) {
            // index the resource that has been created
            //TODO: have created url here
            return Response.created(null).entity(folderEntity.getContent()).build();
          } else {
            return Response.created(null).build();
          }
        } else {
          return Response.status(nodeMoveStatusCode).entity(folderEntity.getContent()).build();
        }
      } else {
        return Response.status(nodeMoveStatusCode).build();
      }
    } catch (Exception e) {
      throw new CedarAssertionException(e);
    }
  }

  @POST
  @Timed
  @Path("/auth-user-callback ")
  public Response authUserCallback() throws CedarException {

    //TODO: we will have a different request here, we will need to extract the user ID from the content
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    // TODO : we should check if the user is the admin, it has sufficient roles to create user related objects

    JsonNode jsonBody = c.request().getRequestBody().asJson();

    if (jsonBody != null) {
      try {
        Event event = JsonMapper.MAPPER.treeToValue(jsonBody.get("event"), Event.class);
        CedarUserExtract eventUser = JsonMapper.MAPPER.treeToValue(jsonBody.get("eventUser"), CedarUserExtract.class);

        String clientId = event.getClientId();
        if (!"admin-cli".equals(clientId)) {
          CedarUser user = createUserRelatedObjects(userService, eventUser);
          createHomeFolderAndUser(c);
          updateHomeFolderPath(c, userService, user);
        }
      } catch (Exception e) {
        throw new CedarAssertionException(e);
      }
    }

    //TODO: return created url
    return Response.created(null).build();
  }

  private CedarUser createUserRelatedObjects(UserService userService, CedarUserExtract eventUser) throws
      CedarException {
    CedarUser existingUser = null;
    try {
      existingUser = userService.findUser(eventUser.getId());
    } catch (Exception e) {
      throw new CedarAssertionException(e);
    }

    if (existingUser != null) {
      return existingUser;
    }

    List<CedarUserRole> roles = null;
    CedarUser user = CedarUserUtil.createUserFromBlueprint(eventUser, roles);

    try {
      CedarUser u = userService.createUser(user);
      return u;
    } catch (IOException e) {
      throw new CedarAssertionException(e);
    }
  }

  private void updateHomeFolderPath(CedarRequestContext cedarRequestContext, UserService userService,
                                    CedarUser user) {
    FolderServiceSession neoSession = CedarDataServices.getFolderServiceSession(cedarRequestContext);

    String homeFolderPath = neoSession.getHomeFolderPath();
    FolderServerFolder userHomeFolder = neoSession.findFolderByPath(homeFolderPath);

    if (userHomeFolder != null) {
      user.setHomeFolderId(userHomeFolder.getId());
      try {
        userService.updateUser(user.getId(), JsonMapper.MAPPER.valueToTree(user));
      } catch (Exception e) {
        e.printStackTrace();
        System.out.println("Error while updating user: " + user.getEmail());
      }
    }
  }

  private static void createHomeFolderAndUser(CedarRequestContext cedarRequestContext) {
    UserServiceSession userSession = CedarDataServices.getUserServiceSession(cedarRequestContext);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(cedarRequestContext);
    userSession.ensureUserExists();
    folderSession.ensureUserHomeExists();
  }

  @POST
  @Timed
  @Path("/auth-admin-callback")
  public Response authAdminCallback() throws CedarAssertionException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    // TODO : we should check if the user is the admin, it has sufficient roles to create user related objects

    JsonNode jsonBody = c.request().getRequestBody().asJson();

    if (jsonBody != null) {
      try {
        AdminEvent event = JsonMapper.MAPPER.treeToValue(jsonBody.get("event"), AdminEvent.class);
        CedarUserExtract eventUser = JsonMapper.MAPPER.treeToValue(jsonBody.get("eventUser"), CedarUserExtract.class);

        //TODO: read KK user, update enabled status in CEDAR - mongo and NEO
      } catch (JsonProcessingException e) {
        throw new CedarAssertionException(e);
      }
    }

    //TODO: handle this. this is probably an error, having the null body here
    return Response.noContent().build();
  }
}