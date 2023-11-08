package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jsonldjava.core.JsonLdError;
import org.apache.commons.lang.CharEncoding;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.keycloak.events.Event;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.LinkedData;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorType;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarObjectNotFoundException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.*;
import org.metadatacenter.model.BiboStatus;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.GraphDbObjectBuilder;
import org.metadatacenter.model.ResourceVersion;
import org.metadatacenter.model.folderserver.basic.*;
import org.metadatacenter.model.request.OutputFormatType;
import org.metadatacenter.model.request.OutputFormatTypeDetector;
import org.metadatacenter.model.trimmer.JsonLdDocument;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.UserServiceSession;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.user.CedarSuperRole;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.security.model.user.CedarUserExtract;
import org.metadatacenter.server.security.util.CedarUserUtil;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.CedarResourceTypeUtil;
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
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.QP_FORMAT;
import static org.metadatacenter.constant.CedarQueryParameters.QP_RESOURCE_TYPE;
import static org.metadatacenter.model.ModelNodeNames.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
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

  @POST
  @Timed
  @Path("/copy-artifact-to-folder")
  public Response copyResourceToFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    JsonNode jsonBody = c.request().getRequestBody().asJson();

    String id = jsonBody.get("@id").asText();
    String folderId = jsonBody.get("targetFolderId").asText();
    String nameTemplate = jsonBody.get("nameTemplate").asText();


    CedarUntypedArtifactId untypedSourceArtifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    CedarResourceType resourceType = folderSession.getResourceType(untypedSourceArtifactId);
    CedarArtifactId sourceArtifactId = CedarArtifactId.build(id, resourceType);

    CedarFolderId targetFolderId = CedarFolderId.build(folderId);

    userMustHaveReadAccessToArtifact(c, sourceArtifactId);


    if (resourceType == CedarResourceType.FOLDER) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.FOLDER_COPY_NOT_ALLOWED)
          .errorMessage("Folder copy is not allowed")
          .build();
    }

    CedarPermission permission1 = null;
    CedarPermission permission2 = null;
    switch (resourceType) {
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
          .errorKey(CedarErrorKey.UNKNOWN_RESOURCE_TYPE)
          .errorMessage("Unknown resource type:" + resourceType.getValue())
          .parameter("resourceType", resourceType.getValue())
          .build();
    }

    // Check read permission
    c.must(c.user()).have(permission1);

    // Check create permission
    c.must(c.user()).have(permission2);

    // Check if the user has write permission to the target folder
    userMustHaveWriteAccessToFolder(c, targetFolderId);

    String originalDocument = null;
    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, sourceArtifactId);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        originalDocument = EntityUtils.toString(entity, CharEncoding.UTF_8);
        JsonNode jsonNode = JsonMapper.MAPPER.readTree(originalDocument);
        ((ObjectNode) jsonNode).remove("@id");
        String oldName = ModelUtil.extractNameFromResource(resourceType, jsonNode).getValue();
        if (oldName != null) {
          oldName = "";
        }
        String newName = nameTemplate.replace("{{name}}", oldName);
        ((ObjectNode) jsonNode).put(PAV_DERIVED_FROM, id);
        if (resourceType.isVersioned()) {
          ((ObjectNode) jsonNode).put(PAV_VERSION, ResourceVersion.ZERO_ZERO_ONE.getValue());
          ((ObjectNode) jsonNode).put(BIBO_STATUS, BiboStatus.DRAFT.getValue());
        }
        if (jsonNode.get(SCHEMA_ORG_IDENTIFIER) != null) {
          String schemaId = jsonNode.get(SCHEMA_ORG_IDENTIFIER).asText();
          // Since we are creating a copy, we remove the schema:identifier to avoid confusion with the original artifact
          ((ObjectNode) jsonNode).remove(SCHEMA_ORG_IDENTIFIER);
          // CDE artifacts have the schema:identifier between brackets as part of their name so we need to remove it too
          newName = newName.replace("(" + schemaId + ")", "").trim();
        }
        ((ObjectNode) jsonNode).put(SCHEMA_ORG_NAME, newName);
        originalDocument = jsonNode.toString();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

    try {
      String url = microserviceUrlUtil.getArtifact().getResourceType(resourceType);

      HttpResponse templateProxyResponse = ProxyUtil.proxyPost(url, c, originalDocument);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);

      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // artifact was not created
        return generateStatusResponse(templateProxyResponse);
      } else {
        // artifact was created
        HttpEntity entity = templateProxyResponse.getEntity();
        Header locationHeader = templateProxyResponse.getFirstHeader(HttpHeaders.LOCATION);
        String entityContent = EntityUtils.toString(entity, CharEncoding.UTF_8);
        JsonNode jsonNode = JsonMapper.MAPPER.readTree(entityContent);
        String createdId = jsonNode.get("@id").asText();
        CedarArtifactId newId = CedarArtifactId.build(createdId, resourceType);

        FolderServerArtifact folderServerCreatedResource =
            copyArtifactToFolderInGraphDb(c, sourceArtifactId, newId, targetFolderId, resourceType,
                ModelUtil.extractNameFromResource(resourceType, jsonNode).getValue(),
                ModelUtil.extractDescriptionFromResource(resourceType, jsonNode).getValue(),
                ModelUtil.extractIdentifierFromResource(resourceType, jsonNode).getValue());

        if (locationHeader != null) {
          response.setHeader(locationHeader.getName(), locationHeader.getValue());
        }
        if (templateProxyResponse.getEntity() != null) {
          // index the artifact that has been created
          createIndexArtifact(folderServerCreatedResource, c);
          createValuerecommenderResource(folderServerCreatedResource);
          URI location = CedarUrlUtil.getLocationURI(templateProxyResponse);
          return Response.created(location).entity(templateProxyResponse.getEntity().getContent()).build();
        } else {
          return Response.ok().build();
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }


  private FolderServerArtifact copyArtifactToFolderInGraphDb(CedarRequestContext c, CedarArtifactId oldId, CedarArtifactId newId,
                                                             CedarFolderId targetFolderId, CedarResourceType resourceType, String name,
                                                             String description, String identifier) throws CedarException {

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    if (CedarResourceTypeUtil.isNotValidForRestCall(resourceType)) {
      throw new CedarProcessingException("You passed an illegal resourceType:'" + resourceType.getValue() +
          "'. The allowed values are:" + CedarResourceTypeUtil.getValidResourceTypesForRestCalls()).badRequest()
          .errorKey(CedarErrorKey.INVALID_RESOURCE_TYPE)
          .parameter("invalidResourceTypes", resourceType.getValue())
          .parameter("allowedResourceTypes", CedarResourceTypeUtil.getValidResourceTypeValuesForRestCalls());
    }

    ResourceVersion version = ResourceVersion.ZERO_ZERO_ONE;
    BiboStatus publicationStatus = BiboStatus.DRAFT;

    // check existence of parent folder
    FolderServerArtifact newResource = null;
    FolderServerFolder parentFolder = folderSession.findFolderById(targetFolderId);

    String candidatePath = null;
    if (parentFolder == null) {
      throw new CedarObjectNotFoundException("The parent folder is not present!")
          .parameter("targetFolderId", targetFolderId)
          .errorKey(CedarErrorKey.PARENT_FOLDER_NOT_FOUND);
    } else {
      // Later we will guarantee some kind of uniqueness for the artifact names
      // Currently we allow duplicate names, the id is the PK
      FolderServerArtifact oldResource = folderSession.findArtifactById(oldId);
      if (oldResource == null) {
        throw new CedarObjectNotFoundException("The source artifact was not found!")
            .parameter("@id", oldId)
            .parameter("resourceType", resourceType.getValue())
            .errorKey(CedarErrorKey.ARTIFACT_NOT_FOUND);
      } else {
        FolderServerArtifact brandNewResource = GraphDbObjectBuilder.forResourceType(resourceType, newId, name, description, identifier, version, publicationStatus);
        if (brandNewResource instanceof FolderServerSchemaArtifact schemaArtifact) {
          schemaArtifact.setLatestVersion(true);
          schemaArtifact.setLatestDraftVersion(publicationStatus == BiboStatus.DRAFT);
          schemaArtifact.setLatestPublishedVersion(publicationStatus == BiboStatus.PUBLISHED);
        }
        if (resourceType == CedarResourceType.INSTANCE) {
          ((FolderServerInstance) brandNewResource).setIsBasedOn(((FolderServerInstance) oldResource).getIsBasedOn());
        }
        newResource = folderSession.createResourceAsChildOfId(brandNewResource, targetFolderId);
      }
    }

    if (newResource != null) {
      folderSession.setDerivedFrom(newId, oldId);
      return newResource;
    } else {
      throw new CedarProcessingException("The artifact was not created!")
          .parameter("@id", oldId)
          .parameter("targetFolderId", parentFolder)
          .errorKey(CedarErrorKey.RESOURCE_NOT_CREATED);
    }
  }

  @POST
  @Timed
  @Path("/move-resource-to-folder")
  public Response moveResourceToFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    JsonNode jsonBody = c.request().getRequestBody().asJson();

    String sId = jsonBody.get(LinkedData.ID).asText();
    String fId = jsonBody.get("targetFolderId").asText();

    CedarFolderId targetFolderId = CedarFolderId.build(fId);

    CedarResourceId untypedResourceId = CedarUntypedResourceId.build(sId);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    CedarResourceType sourceResourceType = folderSession.getResourceType(untypedResourceId);
    CedarFilesystemResourceId sourceId = CedarFilesystemResourceId.build(sId, sourceResourceType);

    userMustHaveWriteAccessToFilesystemResource(c, sourceId);

    CedarPermission permissionCreate = null;
    CedarPermission permissionDelete = null;
    switch (sourceResourceType) {
      case FIELD:
        permissionCreate = CedarPermission.TEMPLATE_FIELD_CREATE;
        permissionDelete = CedarPermission.TEMPLATE_FIELD_DELETE;
        break;
      case ELEMENT:
        permissionCreate = CedarPermission.TEMPLATE_ELEMENT_CREATE;
        permissionDelete = CedarPermission.TEMPLATE_ELEMENT_DELETE;
        break;
      case TEMPLATE:
        permissionCreate = CedarPermission.TEMPLATE_CREATE;
        permissionDelete = CedarPermission.TEMPLATE_DELETE;
        break;
      case INSTANCE:
        permissionCreate = CedarPermission.TEMPLATE_INSTANCE_CREATE;
        permissionDelete = CedarPermission.TEMPLATE_INSTANCE_DELETE;
        break;
      case FOLDER:
        permissionCreate = CedarPermission.FOLDER_CREATE;
        permissionDelete = CedarPermission.FOLDER_DELETE;
        break;
    }

    if (permissionCreate == null || permissionDelete == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.UNKNOWN_RESOURCE_TYPE)
          .errorMessage("Unknown resource type:" + sourceResourceType.getValue())
          .parameter("resourceType", sourceResourceType.getValue())
          .build();
    }

    // Check create permission
    c.must(c.user()).have(permissionCreate);

    // Check delete permission
    c.must(c.user()).have(permissionDelete);

    FolderServerFolder sourceFolder = null;
    FolderServerArtifact sourceResource = null;
    // Check if the source resource exists
    if (sourceResourceType == CedarResourceType.FOLDER) {
      sourceFolder = folderSession.findFolderById(sourceId.asFolderId());
      if (sourceFolder == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.SOURCE_FOLDER_NOT_FOUND)
            .errorMessage("The source folder can not be found:" + sourceId)
            .parameter("@id", sourceId)
            .build();
      }
    } else {
      sourceResource = folderSession.findArtifactById(sourceId.asArtifactId());
      if (sourceResource == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.SOURCE_RESOURCE_NOT_FOUND)
            .errorMessage("The source artifact can not be found:" + sourceId)
            .parameter("@id", sourceId)
            .build();
      }
    }

    // Check if the target folder exists
    FolderServerFolder targetFolder = folderSession.findFolderById(targetFolderId);
    if (targetFolder == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.TARGET_FOLDER_NOT_FOUND)
          .errorMessage("The target folder can not be found:" + targetFolderId)
          .parameter("targetFolderId", targetFolderId)
          .build();
    }

    // Check if the user has write/delete permission to the source resource
    if (sourceResourceType == CedarResourceType.FOLDER) {
      userMustHaveWriteAccessToFolder(c, sourceId.asFolderId());
    } else {
      userMustHaveWriteAccessToArtifact(c, sourceId.asArtifactId());
    }

    // Check if the user has write permission to the target folder
    userMustHaveWriteAccessToFolder(c, targetFolderId);

    boolean moved;
    if (sourceResourceType == CedarResourceType.FOLDER) {
      CedarFolderId sourceFolderId = sourceId.asFolderId();
      moved = folderSession.moveFolder(sourceFolderId, targetFolderId);
      searchPermissionEnqueueService.folderMoved(sourceId.getId());
    } else {
      CedarArtifactId sourceArtifactId = sourceId.asArtifactId();
      moved = folderSession.moveResource(sourceArtifactId, targetFolderId);
      searchPermissionEnqueueService.resourceMoved(sourceId.getId());
    }
    if (!moved) {
      BackendCallResult<?> backendCallResult = new BackendCallResult<>();
      backendCallResult.addError(CedarErrorType.SERVER_ERROR)
          .errorKey(CedarErrorKey.NODE_NOT_MOVED)
          .message("There was an error while moving the resource");
      throw new CedarBackendException(backendCallResult);
    } else {
      FileSystemResource movedNode = folderSession.findResourceById(sourceId);
      UriBuilder builder = uriInfo.getAbsolutePathBuilder();
      URI uri = builder.build();
      return Response.created(uri).entity(movedNode).build();
    }
  }

  // This is the endpoint called by the Keycloak Event Listener
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

          UserServiceSession userSession = CedarDataServices.getUserServiceSession(userContext);
          userSession.addUserToEverybodyGroup(user.getResourceId());

          FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(userContext);
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
    FolderServiceSession neoSession = CedarDataServices.getFolderServiceSession(cedarRequestContext);

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
  public Response convertResource(@QueryParam(QP_FORMAT) Optional<String> format) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
//    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_READ); // XXX Need a permission to convert?

    OutputFormatType formatType = OutputFormatTypeDetector.detectFormat(format);
    JsonNode resourceNode = c.request().getRequestBody().asJson();

    return doConvert(resourceNode, formatType);
  }

  @POST
  @Timed
  @Path("/validate")
  public Response validateResource(@QueryParam(QP_RESOURCE_TYPE) String resourceType) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
//    c.must(c.user()).have(CedarPermission.TEMPLATE_INSTANCE_CREATE); // XXX Permission for validation?

    String url = microserviceUrlUtil.getArtifact().getValidateCommand(resourceType);

    try {
      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      return createServiceResponse(proxyResponse);
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
  @Path("/rename-resource")
  public Response renameResource() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter nameParam = c.request().getRequestBody().get(SCHEMA_ORG_NAME);
    CedarParameter descriptionParam = c.request().getRequestBody().get(SCHEMA_ORG_DESCRIPTION);
    CedarParameter idParam = c.request().getRequestBody().get(LinkedData.ID);

    String id = idParam.stringValue();

    CedarResourceId untypedResourceId = CedarUntypedResourceId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    CedarResourceType resourceType = folderSession.getResourceType(untypedResourceId);
    CedarFilesystemResourceId fsResourceId = CedarFilesystemResourceId.build(id, resourceType);

    userMustHaveWriteAccessToFilesystemResource(c, fsResourceId);

    String name = null;
    if (!nameParam.isEmpty()) {
      name = nameParam.stringValue();
    }
    String description = null;
    if (!descriptionParam.isEmpty()) {
      description = descriptionParam.stringValue();
    }

    boolean isFolder = false;

    CedarPermission permission = null;
    switch (resourceType) {
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
          .errorKey(CedarErrorKey.UNKNOWN_RESOURCE_TYPE)
          .errorMessage("Unknown resource type:" + resourceType.getValue())
          .parameter("resourceType", resourceType.getValue())
          .build();
    }

    // Check read permission
    c.must(c.user()).have(permission);

    if (isFolder) {
      return updateFolderNameAndDescriptionInGraphDb(c, (CedarFolderId) fsResourceId);
    } else {
      String artifactServerUrl = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, (CedarArtifactId) fsResourceId);

      HttpResponse templateCurrentProxyResponse = ProxyUtil.proxyGet(artifactServerUrl, c);
      int currentStatusCode = templateCurrentProxyResponse.getStatusLine().getStatusCode();
      if (currentStatusCode != HttpStatus.SC_OK) {
        // artifact was not created
        return generateStatusResponse(templateCurrentProxyResponse);
      } else {
        HttpEntity currentTemplateEntity = templateCurrentProxyResponse.getEntity();
        if (currentTemplateEntity != null) {
          try {
            String currentTemplateEntityContent = EntityUtils.toString(currentTemplateEntity, CharEncoding.UTF_8);
            JsonNode currentTemplateJsonNode = JsonMapper.MAPPER.readTree(currentTemplateEntityContent);
            String currentName = ModelUtil.extractNameFromResource(resourceType, currentTemplateJsonNode).getValue();
            String currentDescription = ModelUtil.extractDescriptionFromResource(resourceType, currentTemplateJsonNode).getValue();
            String publicationStatusString = ModelUtil.extractPublicationStatusFromResource(resourceType, currentTemplateJsonNode).getValue();
            BiboStatus biboStatus = BiboStatus.forValue(publicationStatusString);
            if (biboStatus == BiboStatus.PUBLISHED) {
              return CedarResponse.badRequest()
                  .errorKey(CedarErrorKey.PUBLISHED_ARTIFACT_CAN_NOT_BE_CHANGED)
                  .errorMessage("The artifact can not be changed since it is published!")
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
                updateNameInObject(currentTemplateJsonNode, name);
              }
              if (changeDescription) {
                updateDescriptionInObject(currentTemplateJsonNode, description);
              }
              return executeResourceCreateOrUpdateViaPut(c, resourceType, (CedarArtifactId) fsResourceId, Optional.empty(), JsonMapper.MAPPER.writeValueAsString(currentTemplateJsonNode));
            } else {
              return CedarResponse.badRequest()
                  .errorKey(CedarErrorKey.NOTHING_TO_DO)
                  .errorMessage("The name and the description are unchanged. There is nothing to do!")
                  .parameter(SCHEMA_ORG_NAME, name)
                  .parameter(SCHEMA_ORG_DESCRIPTION, description)
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
