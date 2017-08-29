package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jsonldjava.core.JsonLdError;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.keycloak.events.Event;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.FolderServerProxy;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerResource;
import org.metadatacenter.model.request.OutputFormatType;
import org.metadatacenter.model.request.OutputFormatTypeDetector;
import org.metadatacenter.model.trimmer.JsonLdDocument;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.UserServiceSession;
import org.metadatacenter.server.search.util.RegenerateSearchIndexTask;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.user.CedarSuperRole;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.security.model.user.CedarUserExtract;
import org.metadatacenter.server.security.util.CedarUserUtil;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.ModelUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.QP_FORMAT;
import static org.metadatacenter.constant.CedarQueryParameters.QP_RESOURCE_TYPE;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandResource.class);

  protected static final String MOVE_COMMAND = "move-node-to-folder";

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

    if (nodeType == CedarNodeType.FOLDER) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.FOLDER_COPY_NOT_ALLOWED)
          .errorMessage("Folder copy is not allowed")
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

    userMustHaveReadAccessToResource(c, id);

    // Check if the user has write permission to the target folder
    FolderServerFolder targetFolder = userMustHaveWriteAccessToFolder(c, folderId);

    String originalDocument = null;
    try {
      String url = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        originalDocument = EntityUtils.toString(entity);
        JsonNode jsonNode = JsonMapper.MAPPER.readTree(originalDocument);
        ((ObjectNode) jsonNode).remove("@id");
        String oldTitle = ModelUtil.extractNameFromResource(nodeType, jsonNode).getValue();
        if (oldTitle != null) {
          oldTitle = "";
        }
        String newTitle = titleTemplate.replace("{{title}}", oldTitle);
        if (nodeType != CedarNodeType.INSTANCE) {
          JsonNode ui = jsonNode.get("_ui");
          if (ui != null) {
            ((ObjectNode) ui).put("title", newTitle);
          }
        } else {
          ((ObjectNode) jsonNode).put("schema:name", newTitle);
        }
        originalDocument = jsonNode.toString();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

    // TODO : from this point, this block is repeated 90% in:
    // AbstractResourceServerController.executeResourcePostByProxy
    // refactor, if possible
    try {
      String url = microserviceUrlUtil.getTemplate().getNodeType(nodeType);

      HttpResponse templateProxyResponse = ProxyUtil.proxyPost(url, c, originalDocument);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);

      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // resource was not created
        return generateStatusResponse(templateProxyResponse);
      } else {
        // resource was created
        HttpEntity entity = templateProxyResponse.getEntity();
        if (entity != null) {
          Header locationHeader = templateProxyResponse.getFirstHeader(HttpHeaders.LOCATION);
          String entityContent = EntityUtils.toString(entity);
          JsonNode jsonNode = JsonMapper.MAPPER.readTree(entityContent);
          String createdId = jsonNode.get("@id").asText();

          String resourceUrl = microserviceUrlUtil.getWorkspace().getResources();
          //System.out.println(resourceUrl);
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", targetFolder.getId());
          resourceRequestBody.put("id", createdId);
          resourceRequestBody.put("nodeType", nodeType.getValue());
          resourceRequestBody.put("name", ModelUtil.extractNameFromResource(nodeType, jsonNode).getValue());
          resourceRequestBody.put("description", ModelUtil.extractDescriptionFromResource(nodeType, jsonNode)
              .getValue());
          String resourceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(resourceRequestBody);

          HttpResponse resourceCreateResponse = ProxyUtil.proxyPost(resourceUrl, c, resourceRequestBodyAsString);
          int resourceCreateStatusCode = resourceCreateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceCreateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_CREATED == resourceCreateStatusCode) {
              if (locationHeader != null) {
                response.setHeader(locationHeader.getName(), locationHeader.getValue());
              }
              if (templateProxyResponse.getEntity() != null) {
                // index the resource that has been created
                createIndexResource(JsonMapper.MAPPER.readValue(resourceCreateResponse.getEntity().getContent
                    (), FolderServerResource.class), jsonNode, c);
                URI location = CedarUrlUtil.getLocationURI(templateProxyResponse);
                return Response.created(location).entity(templateProxyResponse.getEntity().getContent()).build();
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
      throw new CedarProcessingException(e);
    }
  }

  @POST
  @Timed
  @Path("/" + MOVE_COMMAND)
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

    String folderURL = microserviceUrlUtil.getWorkspace().getFolders();

    // Check if the source node exists
    if (nodeType == CedarNodeType.FOLDER) {
      FolderServerFolder sourceFolder = FolderServerProxy.getFolder(folderURL, sourceId, c);
      if (sourceFolder == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.SOURCE_FOLDER_NOT_FOUND)
            .errorMessage("The source folder can not be found:" + sourceId)
            .parameter("sourceId", sourceId)
            .build();
      }
    } else {
      String resourceURL = microserviceUrlUtil.getWorkspace().getResources();
      FolderServerResource sourceResource = FolderServerProxy.getResource(resourceURL, sourceId, c);
      if (sourceResource == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.SOURCE_RESOURCE_NOT_FOUND)
            .errorMessage("The source resource can not be found:" + sourceId)
            .parameter("sourceId", sourceId)
            .build();
      }
    }

    // Check if the target folder exists
    FolderServerFolder targetFolder = FolderServerProxy.getFolder(folderURL, folderId, c);
    if (targetFolder == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.TARGET_FOLDER_NOT_FOUND)
          .errorMessage("The target folder can not be found:" + folderId)
          .parameter("folderId", folderId)
          .build();
    }

    // Check if the user has write/delete permission to the source node
    if (nodeType == CedarNodeType.FOLDER) {
      userMustHaveWriteAccessToFolder(c, sourceId);
    } else {
      userMustHaveWriteAccessToResource(c, sourceId);
    }

    // Check if the user has write permission to the target folder
    userMustHaveWriteAccessToFolder(c, folderId);

    try {
      String resourceUrl = microserviceUrlUtil.getWorkspace().getCommand(MOVE_COMMAND);
      ObjectNode folderRequestBody = JsonNodeFactory.instance.objectNode();

      folderRequestBody.put("sourceId", sourceId);
      folderRequestBody.put("nodeType", nodeTypeString);
      folderRequestBody.put("folderId", folderId);

      String folderRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(folderRequestBody);

      HttpResponse nodeMoveResponse = ProxyUtil.proxyPost(resourceUrl, c, folderRequestBodyAsString);
      int nodeMoveStatusCode = nodeMoveResponse.getStatusLine().getStatusCode();
      HttpEntity folderEntity = nodeMoveResponse.getEntity();
      if (folderEntity != null) {
        if (HttpStatus.SC_CREATED == nodeMoveStatusCode) {
          URI location = CedarUrlUtil.getLocationURI(nodeMoveResponse);
          if (folderEntity.getContent() != null) {
            // there is no need to index the resource itself, since the content and meta is unchanged
            if (nodeType == CedarNodeType.FOLDER) {
              searchPermissionEnqueueService.folderMoved(sourceId);
            } else {
              searchPermissionEnqueueService.resourceMoved(sourceId);
            }
            return Response.created(location).entity(folderEntity.getContent()).build();
          } else {
            return Response.created(location).build();
          }
        } else {
          return Response.status(nodeMoveStatusCode).entity(folderEntity.getContent()).build();
        }
      } else {
        return Response.status(nodeMoveStatusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  @POST
  @Timed
  @Path("/auth-user-callback")
  public Response authUserCallback() throws CedarException {
    CedarRequestContext adminContext = CedarRequestContextFactory.fromRequest(request);
    adminContext.must(adminContext.user()).be(LoggedIn);

    // TODO : we should check if the user is the admin, it has sufficient roles to create user related objects
    JsonNode jsonBody = adminContext.request().getRequestBody().asJson();

    if (jsonBody != null) {
      try {
        Event event = JsonMapper.MAPPER.treeToValue(jsonBody.get("event"), Event.class);
        CedarUserExtract targetUser = JsonMapper.MAPPER.treeToValue(jsonBody.get("eventUser"), CedarUserExtract.class);

        String clientId = event.getClientId();
        if (cedarConfig.getKeycloakConfig().getResource().equals(clientId)) {
          CedarUser user = createUserRelatedObjects(userService, targetUser);
          CedarRequestContext userContext = CedarRequestContextFactory.fromUser(user);
          createHomeFolderAndUser(userContext);
          updateHomeFolderId(userContext, userService, user);
        }
      } catch (Exception e) {
        throw new CedarProcessingException(e);
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
      throw new CedarProcessingException(e);
    }

    if (existingUser != null) {
      return existingUser;
    }

    CedarUser user = CedarUserUtil.createUserFromBlueprint(cedarConfig.getBlueprintUserProfile(), eventUser,
        CedarSuperRole.NORMAL, cedarConfig, null);
    try {
      return userService.createUser(user);
    } catch (IOException e) {
      throw new CedarProcessingException(e);
    }
  }

  private void updateHomeFolderId(CedarRequestContext cedarRequestContext, UserService userService, CedarUser user) {
    FolderServiceSession neoSession = CedarDataServices.getFolderServiceSession(cedarRequestContext);

    FolderServerFolder userHomeFolder = neoSession.findHomeFolderOf();

    if (userHomeFolder != null) {
      user.setHomeFolderId(userHomeFolder.getId());
      try {
        userService.updateUser(user.getId(), user);
      } catch (Exception e) {
        log.error("Error while updating user: " + user.getEmail(), e);
      }
    }
  }

  private static void createHomeFolderAndUser(CedarRequestContext cedarRequestContext) {
    UserServiceSession userSession = CedarDataServices.getUserServiceSession(cedarRequestContext);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(cedarRequestContext);
    userSession.ensureUserExists();
    folderSession.ensureUserHomeExists();
  }

  // TODO: Think about this method. What do we want to achieve.
  // What can we handle, and how.
  /*
  @POST
  @Timed
  @Path("/auth-admin-callback")
  public Response authAdminCallback() throws CedarException {
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
        throw new CedarProcessingException(e);
      }
    }

    //TODO: handle this. this is probably an error, having the null body here
    return Response.noContent().build();
  }
  */

  @POST
  @Timed
  @Path("/regenerate-search-index")
  public Response regenerateSearchIndex() throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.SEARCH_INDEX_REINDEX);

    boolean force = false;

    CedarRequestBody requestBody = c.request().getRequestBody();
    CedarParameter forceParam = requestBody.get("force");
    if (!forceParam.isMissing()) {
      if ("true".equals(forceParam.stringValue())) {
        force = true;
      }
    }

    RegenerateSearchIndexTask task = new RegenerateSearchIndexTask(cedarConfig);
    task.regenerateSearchIndex(force, c);

    return Response.ok().build();
  }

  @POST
  @Timed
  @Path("/convert")
  public Response convertResource(@QueryParam(QP_FORMAT) Optional<String> format) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
//    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ); // XXX Need a permission to convert?

    OutputFormatType formatType = OutputFormatTypeDetector.detectFormat(format);
    JsonNode resourceNode = c.request().getRequestBody().asJson();

    Response response = doConvert(resourceNode, formatType);
    return response;
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

  @POST
  @Timed
  @Path("/validate")
  public Response validateResource(@QueryParam(QP_RESOURCE_TYPE) String resourceType) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
//    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE); // XXX Permission for validation?

    String url = microserviceUrlUtil.getTemplate().getValidateCommand(resourceType);

    try {
      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      Response response = createServiceResponse(proxyResponse);
      return response;
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  private Response createServiceResponse(HttpResponse proxyResponse) throws IOException {
    HttpEntity entity = proxyResponse.getEntity();
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    String mediaType = entity.getContentType().getValue();
    return Response.status(statusCode).type(mediaType).entity(entity.getContent()).build();
  }

  @POST
  @Timed
  @Path("/rename-node")
  public Response renameResource() throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    CedarParameter nameParam = c.request().getRequestBody().get("name");
    CedarParameter descriptionParam = c.request().getRequestBody().get("description");
    CedarParameter nodeTypeParam = c.request().getRequestBody().get("nodeType");
    CedarParameter idParam = c.request().getRequestBody().get("id");

    String id = idParam.stringValue();
    String nodeTypeString = nodeTypeParam.stringValue();
    String name = null;
    if (!nameParam.isEmpty()) {
      name = nameParam.stringValue();
    }
    String description = null;
    if (!descriptionParam.isEmpty()) {
      description = descriptionParam.stringValue();
    }

    CedarNodeType nodeType = CedarNodeType.forValue(nodeTypeString);
    if (nodeType == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.UNKNOWN_NODE_TYPE)
          .parameter("nodeType", nodeTypeString)
          .errorMessage("Unknown nodeType:" + nodeTypeString + ":")
          .build();
    }

    boolean isFolder = false;

    CedarPermission permission = null;
    switch (nodeType) {
      case FIELD:
        permission = CedarPermission.TEMPLATE_FIELD_UPDATE;
        break;
      case ELEMENT:
        permission = CedarPermission.TEMPLATE_ELEMENT_UPDATE;
        break;
      case TEMPLATE:
        permission = CedarPermission.TEMPLATE_UPDATE;
        break;
      case INSTANCE:
        permission = CedarPermission.TEMPLATE_INSTANCE_UPDATE;
        break;
      case FOLDER:
        permission = CedarPermission.FOLDER_UPDATE;
        isFolder = true;
        break;
    }

    if (permission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.UNKNOWN_NODE_TYPE)
          .errorMessage("Unknown node type:" + nodeTypeString)
          .parameter("nodeType", nodeTypeString)
          .build();
    }

    // Check read permission
    c.must(c.user()).have(permission);

    if (isFolder) {
      return updateFolderNameAndDescriptionOnFolderServer(c, id);
    } else {
      String templateServerUrl = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id);

      HttpResponse templateCurrentProxyResponse = ProxyUtil.proxyGet(templateServerUrl, c);
      int currentStatusCode = templateCurrentProxyResponse.getStatusLine().getStatusCode();
      if (currentStatusCode != HttpStatus.SC_OK) {
        // resource was not created
        return generateStatusResponse(templateCurrentProxyResponse);
      } else {
        HttpEntity currentTemplateEntity = templateCurrentProxyResponse.getEntity();
        if (currentTemplateEntity != null) {
          try {
            String currentTemplateEntityContent = EntityUtils.toString(currentTemplateEntity);
            JsonNode currentTemplateJsonNode = JsonMapper.MAPPER.readTree(currentTemplateEntityContent);
            String currentName = ModelUtil.extractNameFromResource(nodeType, currentTemplateJsonNode).getValue();
            String currentDescription = ModelUtil.extractDescriptionFromResource(nodeType, currentTemplateJsonNode)
                .getValue();
            boolean changeName = false;
            boolean changeDescription = false;
            if (name != null && !name.equals(currentName)) {
              changeName = true;
            }
            if (description != null && !description.equals(currentDescription)) {
              changeDescription = true;
            }
            if (changeName || changeDescription) {
              if (changeName) {
                updateNameInObject(nodeType, currentTemplateJsonNode, name);
              }
              if (changeDescription) {
                updateDescriptionInObject(nodeType, currentTemplateJsonNode, description);
              }
              return executeResourcePutByProxy(nodeType, id, c, JsonMapper.MAPPER.writeValueAsString
                  (currentTemplateJsonNode));
            } else {
              return CedarResponse.badRequest()
                  .errorKey(CedarErrorKey.NOTHING_TO_DO)
                  .errorMessage("The name and the description are unchanged. There is nothing to do!")
                  .parameter("name", name)
                  .parameter("description", description)
                  .build();
            }
          } catch (IOException e) {
            throw new CedarProcessingException(e);
          }
        }
        return CedarResponse.internalServerError().build();
      }
    }
  }

}