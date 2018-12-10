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
import org.metadatacenter.model.*;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerNode;
import org.metadatacenter.model.folderserver.basic.FolderServerResource;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerFolderCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerResourceCurrentUserReport;
import org.metadatacenter.model.request.OutputFormatType;
import org.metadatacenter.model.request.OutputFormatTypeDetector;
import org.metadatacenter.model.trimmer.JsonLdDocument;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.UserServiceSession;
import org.metadatacenter.server.search.util.RegenerateRulesIndexTask;
import org.metadatacenter.server.search.util.RegenerateSearchIndexTask;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.auth.CurrentUserPermissions;
import org.metadatacenter.server.security.model.user.CedarSuperRole;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.security.model.user.CedarUserExtract;
import org.metadatacenter.server.security.util.CedarUserUtil;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.CedarNodeTypeUtil;
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
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.metadatacenter.constant.CedarQueryParameters.QP_FORMAT;
import static org.metadatacenter.constant.CedarQueryParameters.QP_RESOURCE_TYPE;
import static org.metadatacenter.model.ModelNodeNames.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource extends AbstractResourceServerResource {

  protected static final String MOVE_COMMAND = "move-node-to-folder";
  protected static final String CREATE_DRAFT_RESOURCE_COMMAND = "create-draft-resource";
  protected static final String PUBLISH_RESOURCE_COMMAND = "publish-resource";
  protected static final String COPY_RESOURCE_TO_FOLDER_COMMAND = "copy-resource-to-folder";
  protected static final String MAKE_RESOURCE_PUBLIC_COMMAND = "make-resource-public";
  protected static final String MAKE_RESOURCE_NOT_PUBLIC_COMMAND = "make-resource-not-public";
  private static final Logger log = LoggerFactory.getLogger(CommandResource.class);
  private static UserService userService;

  public CommandResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectUserService(UserService us) {
    userService = us;
  }

  private static void createHomeFolderAndUser(CedarRequestContext cedarRequestContext) {
    UserServiceSession userSession = CedarDataServices.getUserServiceSession(cedarRequestContext);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(cedarRequestContext);
    userSession.ensureUserExists();
    folderSession.ensureUserHomeExists();
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
  @Path("/" + COPY_RESOURCE_TO_FOLDER_COMMAND)
  public Response copyResourceToFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    JsonNode jsonBody = c.request().getRequestBody().asJson();

    String id = jsonBody.get("@id").asText();
    String folderId = jsonBody.get("folderId").asText();
    String titleTemplate = jsonBody.get("titleTemplate").asText();

    FolderServerResourceCurrentUserReport folderServerResource = userMustHaveReadAccessToResource(c, id);
    CedarNodeType nodeType = folderServerResource.getType();

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
          .errorMessage("Unknown node type:" + nodeType.getValue())
          .parameter("nodeType", nodeType.getValue())
          .build();
    }

    // Check read permission
    c.must(c.user()).have(permission1);

    // Check create permission
    c.must(c.user()).have(permission2);

    // Check if the user has write permission to the target folder
    FolderServerFolderCurrentUserReport targetFolder = userMustHaveWriteAccessToFolder(c, folderId);

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
        ((ObjectNode) jsonNode).put(SCHEMA_NAME, newTitle);
        ((ObjectNode) jsonNode).put(PAV_VERSION, ResourceVersion.ZERO_ZERO_ONE.getValue());
        ((ObjectNode) jsonNode).put(BIBO_STATUS, BiboStatus.DRAFT.getValue());
        ((ObjectNode) jsonNode).put(PAV_DERIVED_FROM, id);
        originalDocument = jsonNode.toString();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

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

          String resourceUrl = microserviceUrlUtil.getWorkspace().getCommand(COPY_RESOURCE_TO_FOLDER_COMMAND);
          //System.out.println(resourceUrl);
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", targetFolder.getId());
          resourceRequestBody.put("id", createdId);
          resourceRequestBody.put("oldId", id);
          resourceRequestBody.put("nodeType", nodeType.getValue());
          resourceRequestBody.put("name", ModelUtil.extractNameFromResource(nodeType, jsonNode).getValue());
          resourceRequestBody.put("description", ModelUtil.extractDescriptionFromResource(nodeType, jsonNode)
              .getValue());
          resourceRequestBody.put("identifier", ModelUtil.extractIdentifierFromResource(nodeType, jsonNode)
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
                createIndexResource(WorkspaceObjectBuilder.artifact(resourceCreateResponse.getEntity().getContent()),
                    c);
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
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    JsonNode jsonBody = c.request().getRequestBody().asJson();

    String sourceId = jsonBody.get("sourceId").asText();
    String folderId = jsonBody.get("folderId").asText();

    FolderServerNode folderServerNode = userMustHaveReadAccessToNode(c, sourceId);

    CedarNodeType nodeType = folderServerNode.getType();

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
          .errorMessage("Unknown node type:" + nodeType.getValue())
          .parameter("nodeType", nodeType.getValue())
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
      folderRequestBody.put("nodeType", nodeType.getValue());
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
    CedarRequestContext adminContext = buildRequestContext();
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

  @POST
  @Timed
  @Path("/regenerate-search-index")
  public Response regenerateSearchIndex() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.SEARCH_INDEX_REINDEX);

    CedarRequestBody requestBody = c.request().getRequestBody();
    CedarParameter forceParam = requestBody.get("force");
    final boolean force = forceParam.booleanValue();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      RegenerateSearchIndexTask task = new RegenerateSearchIndexTask(cedarConfig);
      try {
        CedarRequestContext cedarAdminRequestContext =
            CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
        task.regenerateSearchIndex(force, cedarAdminRequestContext);
      } catch (CedarProcessingException e) {
        //TODO: handle this, log it separately
        log.error("Error in index regeneration executor", e);
      }
    });

    return Response.ok().build();
  }

  @POST
  @Timed
  @Path("/regenerate-rules-index")
  public Response regenerateRulesIndex() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.RULES_INDEX_REINDEX);

    CedarRequestBody requestBody = c.request().getRequestBody();
    CedarParameter forceParam = requestBody.get("force");
    final boolean force = forceParam.booleanValue();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      RegenerateRulesIndexTask task = new RegenerateRulesIndexTask(cedarConfig);
      try {
        CedarRequestContext cedarAdminRequestContext =
            CedarRequestContextFactory.fromAdminUser(cedarConfig, userService);
        task.regenerateRulesIndex(force, cedarAdminRequestContext);
      } catch (CedarProcessingException e) {
        //TODO: handle this, log it separately
        log.error("Error in index regeneration executor", e);
      }
    });

    return Response.ok().build();
  }

  @POST
  @Timed
  @Path("/convert")
  public Response convertResource(@QueryParam(QP_FORMAT) Optional<String> format) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
//    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ); // XXX Need a permission to convert?

    OutputFormatType formatType = OutputFormatTypeDetector.detectFormat(format);
    JsonNode resourceNode = c.request().getRequestBody().asJson();

    Response response = doConvert(resourceNode, formatType);
    return response;
  }

  @POST
  @Timed
  @Path("/validate")
  public Response validateResource(@QueryParam(QP_RESOURCE_TYPE) String resourceType) throws CedarException {
    CedarRequestContext c = buildRequestContext();
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
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter nameParam = c.request().getRequestBody().get("name");
    CedarParameter descriptionParam = c.request().getRequestBody().get("description");
    CedarParameter idParam = c.request().getRequestBody().get("id");

    String id = idParam.stringValue();

    FolderServerNode folderServerNode = userMustHaveWriteAccessToNode(c, id);

    String name = null;
    if (!nameParam.isEmpty()) {
      name = nameParam.stringValue();
    }
    String description = null;
    if (!descriptionParam.isEmpty()) {
      description = descriptionParam.stringValue();
    }

    CedarNodeType nodeType = folderServerNode.getType();
    if (nodeType == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.UNKNOWN_NODE_TYPE)
          .parameter("nodeType", nodeType.getValue())
          .errorMessage("Unknown nodeType:" + nodeType.getValue() + ":")
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
          .errorMessage("Unknown node type:" + nodeType.getValue())
          .parameter("nodeType", nodeType.getValue())
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
            String publicationStatusString = ModelUtil.extractPublicationStatusFromResource(nodeType,
                currentTemplateJsonNode).getValue();
            BiboStatus biboStatus = BiboStatus.forValue(publicationStatusString);
            if (biboStatus == BiboStatus.PUBLISHED) {
              return CedarResponse.badRequest()
                  .errorKey(CedarErrorKey.PUBLISHED_RESOURCES_CAN_NOT_BE_CHANGED)
                  .errorMessage("The resource can not be changed since it is published!")
                  .parameter("name", currentName)
                  .build();
            }
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
              return executeResourcePutByProxy(c, nodeType, id, null, JsonMapper.MAPPER.writeValueAsString
                  (currentTemplateJsonNode), null);
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

  @POST
  @Timed
  @Path("/" + PUBLISH_RESOURCE_COMMAND)
  public Response publishResource() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter newVersionParam = c.request().getRequestBody().get("newVersion");

    String id = idParam.stringValue();

    ResourceVersion newVersion = null;
    if (!newVersionParam.isEmpty()) {
      newVersion = ResourceVersion.forValueWithValidation(newVersionParam.stringValue());
    }
    if (newVersion == null || !newVersion.isValid()) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .parameter("newVersion", newVersionParam.stringValue())
          .build();
    }

    FolderServerResourceCurrentUserReport folderServerResourceOld = userMustHaveReadAccessToResource(c, id);
    CurrentUserPermissions currentUserPermissions = folderServerResourceOld.getCurrentUserPermissions();
    if (!currentUserPermissions.isCanPublish()) {
      return CedarResponse.badRequest()
          .errorKey(currentUserPermissions.getPublishErrorKey())
          .parameter("id", id)
          .build();
    }

    CedarNodeType nodeType = folderServerResourceOld.getType();

    CedarPermission updatePermission = CedarPermission.getUpdateForVersionedNodeType(nodeType);
    if (updatePermission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_NODE_TYPE)
          .errorMessage("You passed an illegal resource type for versioning:'" + nodeType.getValue() + "'. The " +
              "allowed values are:" +
              CedarNodeTypeUtil.getValidNodeTypeValuesForVersioning())
          .parameter("invalidResourceType", nodeType.getValue())
          .parameter("allowedResourceTypes", CedarNodeTypeUtil.getValidNodeTypeValuesForVersioning())
          .build();
    }

    // Check update permission
    c.must(c.user()).have(updatePermission);

    String getResponse = getResourceFromTemplateServer(nodeType, id, c);
    if (getResponse != null) {
      JsonNode getJsonNode = null;
      try {
        getJsonNode = JsonMapper.MAPPER.readTree(getResponse);
        if (getJsonNode != null) {

          ResourceVersion oldVersion = null;
          JsonNode oldVersionNode = getJsonNode.at(ModelPaths.PAV_VERSION);
          if (oldVersionNode != null) {
            oldVersion = ResourceVersion.forValueWithValidation(oldVersionNode.textValue());
          }

          if (newVersion.isBefore(oldVersion)) {
            return CedarResponse.badRequest()
                .errorKey(CedarErrorKey.INVALID_DATA)
                .errorMessage("The new version should be greater than or equal to the old version")
                .parameter("oldVersion", oldVersion.getValue())
                .parameter("newVersion", newVersion.getValue())
                .build();
          }

          //publish on template server
          ((ObjectNode) getJsonNode).put(PAV_VERSION, newVersion.getValue());
          ((ObjectNode) getJsonNode).put(BIBO_STATUS, BiboStatus.PUBLISHED.getValue());
          String content = JsonMapper.MAPPER.writeValueAsString(getJsonNode);
          Response putResponse = putResourceToTemplateServer(nodeType, id, c, content);
          int putStatus = putResponse.getStatus();

          if (putStatus == HttpStatus.SC_OK) {
            // publish on workspace server

            String workspaceUrl = microserviceUrlUtil.getWorkspace().getCommand(PUBLISH_RESOURCE_COMMAND);

            ObjectNode workspaceRequestBody = JsonNodeFactory.instance.objectNode();
            workspaceRequestBody.put("id", id);
            workspaceRequestBody.put("nodeType", nodeType.getValue());
            workspaceRequestBody.put("version", newVersion.getValue());

            String workspaceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(workspaceRequestBody);

            HttpResponse workspaceServerUpdateResponse = ProxyUtil.proxyPost(workspaceUrl, c,
                workspaceRequestBodyAsString);
            int workspaceServerUpdateStatusCode = workspaceServerUpdateResponse.getStatusLine().getStatusCode();
            HttpEntity workspaceEntity = workspaceServerUpdateResponse.getEntity();
            if (workspaceEntity != null) {
              if (HttpStatus.SC_CREATED == workspaceServerUpdateStatusCode) {
                if (workspaceEntity != null) {
                  // read the updated resource
                  FolderServerResource folderServerResourceNow =
                      WorkspaceObjectBuilder.artifact(workspaceEntity.getContent());
                  updateIndexResource(folderServerResourceNow, c);

                  // read the updated previous version
                  if (folderServerResourceOld.hasPreviousVersion()) {
                    String url = microserviceUrlUtil.getWorkspace().getResources();
                    String prevId = folderServerResourceOld.getPreviousVersion().getValue();
                    FolderServerResource folderServerResourcePrev = FolderServerProxy.getResource(url, prevId, c);
                    updateIndexResource(folderServerResourcePrev, c);
                  }
                  return Response.ok(folderServerResourceNow).build();
                } else {
                  return Response.ok().build();
                }
              } else {
                log.error("Resource draft not created #1, rollback resource and signal error");
                return Response.status(workspaceServerUpdateStatusCode).entity(workspaceEntity.getContent()).build();
              }
            } else {
              log.error("Resource draft not created #2, rollback resource and signal error");
              return Response.status(workspaceServerUpdateStatusCode).build();
            }
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
  }

  @POST
  @Timed
  @Path("/" + CREATE_DRAFT_RESOURCE_COMMAND)
  public Response createDraftResource() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter newVersionParam = c.request().getRequestBody().get("newVersion");
    CedarParameter folderIdParam = c.request().getRequestBody().get("folderId");
    CedarParameter propagateSharingParam = c.request().getRequestBody().get("propagateSharing");

    String id = idParam.stringValue();
    String folderId = folderIdParam.stringValue();
    String propagateSharingString = propagateSharingParam.stringValue();

    ResourceVersion newVersion = null;
    if (!newVersionParam.isEmpty()) {
      newVersion = ResourceVersion.forValueWithValidation(newVersionParam.stringValue());
    }
    if (newVersion == null || !newVersion.isValid()) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .parameter("newVersion", newVersionParam.stringValue())
          .build();
    }

    FolderServerResourceCurrentUserReport folderServerResourceOld = userMustHaveReadAccessToResource(c, id);
    CurrentUserPermissions currentUserPermissions = folderServerResourceOld.getCurrentUserPermissions();
    if (!currentUserPermissions.isCanCreateDraft()) {
      return CedarResponse.badRequest()
          .errorKey(currentUserPermissions.getCreateDraftErrorKey())
          .parameter("id", id)
          .build();
    }

    CedarNodeType nodeType = folderServerResourceOld.getType();

    boolean propagateSharing = Boolean.parseBoolean(propagateSharingString);

    CedarPermission updatePermission = CedarPermission.getUpdateForVersionedNodeType(nodeType);
    if (updatePermission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_NODE_TYPE)
          .errorMessage("You passed an illegal resource type for versioning:'" + nodeType.getValue() + "'. The " +
              "allowed values are:" +
              CedarNodeTypeUtil.getValidNodeTypeValuesForVersioning())
          .parameter("invalidResourceType", nodeType.getValue())
          .parameter("allowedResourceTypes", CedarNodeTypeUtil.getValidNodeTypeValuesForVersioning())
          .build();

    }

    // Check update permission
    c.must(c.user()).have(updatePermission);

    String folderURL = microserviceUrlUtil.getWorkspace().getFolders();

    // Check if the target folder exists
    FolderServerFolder targetFolder = FolderServerProxy.getFolder(folderURL, folderId, c);
    if (targetFolder == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.TARGET_FOLDER_NOT_FOUND)
          .errorMessage("The target folder can not be found:" + folderId)
          .parameter("folderId", folderId)
          .build();
    }

    // Check if the user has write permission to the target folder
    userMustHaveWriteAccessToFolder(c, folderId);

    String getResponse = getResourceFromTemplateServer(nodeType, id, c);
    if (getResponse != null) {
      JsonNode getJsonNode = null;
      try {
        getJsonNode = JsonMapper.MAPPER.readTree(getResponse);
        if (getJsonNode != null) {

          ResourceVersion oldVersion = null;
          JsonNode oldVersionNode = getJsonNode.at(ModelPaths.PAV_VERSION);
          if (oldVersionNode != null) {
            oldVersion = ResourceVersion.forValueWithValidation(oldVersionNode.textValue());
          }

          if (!oldVersion.isBefore(newVersion)) {
            return CedarResponse.badRequest()
                .errorKey(CedarErrorKey.INVALID_DATA)
                .errorMessage("The new version should be greater than the old version")
                .parameter("oldVersion", oldVersion.getValue())
                .parameter("newVersion", newVersion.getValue())
                .build();
          }

          ObjectNode newDocument = (ObjectNode) getJsonNode;
          newDocument.put(ModelNodeNames.PAV_VERSION, newVersion.getValue());
          newDocument.put(ModelNodeNames.BIBO_STATUS, BiboStatus.DRAFT.getValue());
          newDocument.put(ModelNodeNames.PAV_PREVIOUS_VERSION, id);
          newDocument.remove(ModelNodeNames.LD_ID);

          FolderServerFolderCurrentUserReport folder = userMustHaveWriteAccessToFolder(c, folderId);

          String templateServerPostRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(newDocument);

          Response templateServerPostResponse = executeResourcePostToTemplateServer(c, nodeType,
              templateServerPostRequestBodyAsString);

          int templateServerPostStatus = templateServerPostResponse.getStatus();
          InputStream is = (InputStream) templateServerPostResponse.getEntity();
          JsonNode templateServerPostResponseNode = JsonMapper.MAPPER.readTree(is);
          if (templateServerPostStatus == Response.Status.CREATED.getStatusCode()) {
            JsonNode atId = templateServerPostResponseNode.at(ModelPaths.AT_ID);
            String newId = atId.asText();

            // Create it on the workspace server
            // if propagateSharing, then copy the sharing over.
            String workspaceUrl = microserviceUrlUtil.getWorkspace().getCommand(CREATE_DRAFT_RESOURCE_COMMAND);

            ObjectNode workspaceRequestBody = JsonNodeFactory.instance.objectNode();
            workspaceRequestBody.put("oldId", id);
            workspaceRequestBody.put("newId", newId);
            workspaceRequestBody.put("folderId", folderId);
            workspaceRequestBody.put("nodeType", nodeType.getValue());
            workspaceRequestBody.put("propagateSharing", propagateSharing);
            workspaceRequestBody.put("version", newVersion.getValue());
            workspaceRequestBody.put("publicationStatus", BiboStatus.DRAFT.getValue());

            String workspaceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(workspaceRequestBody);

            HttpResponse workspaceServerUpdateResponse = ProxyUtil.proxyPost(workspaceUrl, c,
                workspaceRequestBodyAsString);
            int workspaceServerUpdateStatusCode = workspaceServerUpdateResponse.getStatusLine().getStatusCode();
            HttpEntity workspaceEntity = workspaceServerUpdateResponse.getEntity();
            if (workspaceEntity != null) {
              if (HttpStatus.SC_CREATED == workspaceServerUpdateStatusCode) {
                if (workspaceEntity != null) {
                  // re-read the old resource
                  String url = microserviceUrlUtil.getWorkspace().getResources();
                  FolderServerResource folderServerResourceNow = FolderServerProxy.getResource(url, id, c);
                  // update the old resource index, remove  latest version
                  updateIndexResource(folderServerResourceNow, c);

                  // update the new resource on the index
                  FolderServerResource folderServerResource =
                      WorkspaceObjectBuilder.artifact(workspaceEntity.getContent());
                  createIndexResource(folderServerResource, c);
                  return Response.ok(folderServerResource).build();
                } else {
                  return Response.ok().build();
                }
              } else {
                log.error("Resource draft not created #1, rollback resource and signal error");
                return Response.status(workspaceServerUpdateStatusCode).entity(workspaceEntity.getContent()).build();
              }
            } else {
              log.error("Resource draft not created #2, rollback resource and signal error");
              return Response.status(workspaceServerUpdateStatusCode).build();
            }
            /// this is the end of workspace creation
          } else {
            return CedarResponse.internalServerError()
                .errorMessage("There was an error while creating the resource on the template server")
                .parameter("responseCode", templateServerPostStatus)
                .parameter("responseDocument", templateServerPostResponseNode)
                .build();
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
  }

  @POST
  @Timed
  @Path("/" + MAKE_RESOURCE_PUBLIC_COMMAND)
  public Response makeResourcePublic() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter idParam = c.request().getRequestBody().get("@id");
    String id = idParam.stringValue();

    FolderServerResourceCurrentUserReport folderServerResource = userMustHaveWriteAccessToResource(c, id);

    String workspaceUrl = microserviceUrlUtil.getWorkspace().getCommand(MAKE_RESOURCE_PUBLIC_COMMAND);

    ObjectNode workspaceRequestBody = JsonNodeFactory.instance.objectNode();
    workspaceRequestBody.put("id", id);
    workspaceRequestBody.put("nodeType", folderServerResource.getType().getValue());

    try {
      String workspaceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(workspaceRequestBody);
      HttpResponse workspaceServerUpdateResponse = ProxyUtil.proxyPost(workspaceUrl, c, workspaceRequestBodyAsString);
      int workspaceServerUpdateStatusCode = workspaceServerUpdateResponse.getStatusLine().getStatusCode();
      HttpEntity workspaceEntity = workspaceServerUpdateResponse.getEntity();
      if (workspaceEntity != null) {
        if (HttpStatus.SC_CREATED == workspaceServerUpdateStatusCode) {
          if (workspaceEntity != null) {
            FolderServerResource folderServerResourceUpdated =
                WorkspaceObjectBuilder.artifact(workspaceEntity.getContent());
            // update the resource index
            updateIndexResource(folderServerResourceUpdated, c);
            return Response.ok(folderServerResourceUpdated).build();
          } else {
            return Response.ok().build();
          }
        } else {
          log.error("Resource not made public #1, rollback resource and signal error");
          return Response.status(workspaceServerUpdateStatusCode).entity(workspaceEntity.getContent()).build();
        }
      } else {
        log.error("Resource not made public #2, rollback resource and signal error");
        return Response.status(workspaceServerUpdateStatusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  @POST
  @Timed
  @Path("/" + MAKE_RESOURCE_NOT_PUBLIC_COMMAND)
  public Response makeResourceNotPublic() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter idParam = c.request().getRequestBody().get("@id");
    String id = idParam.stringValue();

    FolderServerResourceCurrentUserReport folderServerResource = userMustHaveWriteAccessToResource(c, id);

    String workspaceUrl = microserviceUrlUtil.getWorkspace().getCommand(MAKE_RESOURCE_NOT_PUBLIC_COMMAND);

    ObjectNode workspaceRequestBody = JsonNodeFactory.instance.objectNode();
    workspaceRequestBody.put("id", id);
    workspaceRequestBody.put("nodeType", folderServerResource.getType().getValue());

    try {
      String workspaceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(workspaceRequestBody);
      HttpResponse workspaceServerUpdateResponse = ProxyUtil.proxyPost(workspaceUrl, c, workspaceRequestBodyAsString);
      int workspaceServerUpdateStatusCode = workspaceServerUpdateResponse.getStatusLine().getStatusCode();
      HttpEntity workspaceEntity = workspaceServerUpdateResponse.getEntity();
      if (workspaceEntity != null) {
        if (HttpStatus.SC_CREATED == workspaceServerUpdateStatusCode) {
          if (workspaceEntity != null) {
            FolderServerResource folderServerResourceUpdated =
                WorkspaceObjectBuilder.artifact(workspaceEntity.getContent());
            // update the resource index
            updateIndexResource(folderServerResourceUpdated, c);
            return Response.ok(folderServerResourceUpdated).build();
          } else {
            return Response.ok().build();
          }
        } else {
          log.error("Resource not made not public #1, rollback resource and signal error");
          return Response.status(workspaceServerUpdateStatusCode).entity(workspaceEntity.getContent()).build();
        }
      } else {
        log.error("Resource not made not public #2, rollback resource and signal error");
        return Response.status(workspaceServerUpdateStatusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

}