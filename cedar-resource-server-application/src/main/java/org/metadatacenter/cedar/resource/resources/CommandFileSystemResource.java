package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.util.http.CedarError;
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
import org.metadatacenter.http.CedarResponseStatus;
import org.metadatacenter.model.BiboStatus;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.ResourceVersion;
import org.metadatacenter.model.folderserver.basic.*;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.RevisionConflictException;
import org.metadatacenter.server.RevisionPrecondition;
import org.metadatacenter.server.SiblingNameConflictException;
import org.metadatacenter.server.VersionedResource;
import org.metadatacenter.server.resource.ArtifactCopyOperations;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.ModelUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.http.RevisionPreconditionParser;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.metadatacenter.model.ModelNodeNames.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandFileSystemResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandFileSystemResource.class);

  public CommandFileSystemResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/copy-artifact-to-folder")
  @Operation(summary = "Copy artifact", description = "Copy artifact to a given folder. A copy of the given artifact will be created in the given folder, "
          + "with a new name Only artifacts (fields, elements, templates, instances) can be copied.", tags = {"Command", "File Operations"})
  @RequestBody(description = "Parameters of the copy operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CopyRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error"),
      @ApiResponse(responseCode = "502", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Invalid response from artifact service")
  })
  public Response copyResourceToFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    // Read through CedarParameter rather than straight off the JsonNode: a missing field used to be a
    // null dereference, which reached the caller as 500 for what is plainly a bad request.
    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter targetFolderParam = c.request().getRequestBody().get("targetFolderId");
    CedarParameter nameTemplateParam = c.request().getRequestBody().get("nameTemplate");
    c.must(idParam).be(NonEmpty);
    c.must(targetFolderParam).be(NonEmpty);
    c.must(nameTemplateParam).be(NonEmpty);

    String id = idParam.stringValue();
    String folderId = targetFolderParam.stringValue();
    String nameTemplate = nameTemplateParam.stringValue();


    CedarUntypedArtifactId untypedSourceArtifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
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

    if (permission1 == null) {
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

    String originalDocument;
    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, sourceArtifactId);
      ClassicHttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      int statusCode = proxyResponse.getCode();
      if (statusCode != HttpStatus.SC_OK) {
        return generateStatusResponse(proxyResponse);
      }

      HttpEntity entity = proxyResponse.getEntity();
      if (entity == null) {
        return CedarResponse.badGateway()
            .errorMessage("Artifact service returned an empty source artifact")
            .id(sourceArtifactId)
            .build();
      }

      originalDocument = EntityUtils.toString(entity, StandardCharsets.UTF_8);
      if (originalDocument.isBlank()) {
        return CedarResponse.badGateway()
            .errorMessage("Artifact service returned an empty source artifact")
            .id(sourceArtifactId)
            .build();
      }
      JsonNode jsonNode = JsonMapper.MAPPER.readTree(originalDocument);
      // Null rather than removed: the artifact server assigns the identifier, and the key carrying
      // null is how anything asks for one — an absent key cannot be told from a forgotten one.
      ((ObjectNode) jsonNode).putNull("@id");
      String oldName = ModelUtil.extractNameFromResource(resourceType, jsonNode).getValue();
      if (oldName == null) {
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
    } catch (CedarException e) {
      throw e;
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

    try {
      String url = microserviceUrlUtil.getArtifact().getResourceType(resourceType);

      ClassicHttpResponse templateProxyResponse = ProxyUtil.proxyPost(url, c, originalDocument);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);

      int statusCode = templateProxyResponse.getCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // artifact was not created
        return generateStatusResponse(templateProxyResponse);
      } else {
        // artifact was created
        HttpEntity entity = templateProxyResponse.getEntity();
        Header locationHeader = templateProxyResponse.getFirstHeader(HttpHeaders.LOCATION);
        String entityContent = EntityUtils.toString(entity, StandardCharsets.UTF_8);
        JsonNode jsonNode = JsonMapper.MAPPER.readTree(entityContent);
        String createdId = jsonNode.get("@id").asText();
        CedarArtifactId newId = CedarArtifactId.build(createdId, resourceType);

        FolderServerArtifact folderServerCreatedResource =
            ArtifactCopyOperations.registerCopy(folderSession, sourceArtifactId, newId, targetFolderId,
                resourceType,
                ModelUtil.extractNameFromResource(resourceType, jsonNode).getValue(),
                ModelUtil.extractDescriptionFromResource(resourceType, jsonNode).getValue(),
                ModelUtil.extractIdentifierFromResource(resourceType, jsonNode).getValue(), null, null);

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
    } catch (CedarException e) {
      throw e;
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  @POST
  @Timed
  @Path("/move-resource-to-folder")
  @Operation(summary = "Move resource", description = "Move a folder or artifact to a given folder. Send the "
      + "ETag returned by the source resource's details endpoint (or GET /folders/{id}) in If-Match. "
      + "A successful move advances the source resource ETag and the revisions of both affected parent folders.",
      tags = {"Command", "File Operations"}, parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "Parameters of the move operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.MoveRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Resource moved",
          headers = @io.swagger.v3.oas.annotations.headers.Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response moveResourceToFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    // As above: a missing field is a bad request, not a server fault.
    CedarParameter sourceParam = c.request().getRequestBody().get(LinkedData.ID);
    CedarParameter targetParam = c.request().getRequestBody().get("targetFolderId");
    c.must(sourceParam).be(NonEmpty);
    c.must(targetParam).be(NonEmpty);

    String sId = sourceParam.stringValue();
    String fId = targetParam.stringValue();

    CedarFolderId targetFolderId = CedarFolderId.build(fId);

    CedarResourceId untypedResourceId = CedarUntypedResourceId.build(sId);
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);

    CedarResourceType sourceResourceType = folderSession.getResourceType(untypedResourceId);
    if (sourceResourceType == null) {
      throw new CedarObjectNotFoundException("Source resource not found by id")
          .errorKey(CedarErrorKey.SOURCE_RESOURCE_NOT_FOUND)
          .parameter("resourceId", untypedResourceId);
    }
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

    if (permissionCreate == null) {
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

    String ifMatch = c.getIfMatchHeader();
    if (ifMatch == null || ifMatch.isBlank()) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_REQUIRED)
          .id(sourceId)
          .errorMessage("Moving a resource requires the source resource ETag in If-Match")
          .build();
    }
    RevisionPrecondition precondition = RevisionPreconditionParser.parse(ifMatch);

    VersionedResource<? extends FileSystemResource> moved;
    try {
      if (sourceResourceType == CedarResourceType.FOLDER) {
        CedarFolderId sourceFolderId = sourceId.asFolderId();
        moved = folderSession.moveFolder(sourceFolderId, targetFolderId, precondition);
      } else {
        CedarArtifactId sourceArtifactId = sourceId.asArtifactId();
        moved = folderSession.moveResource(sourceArtifactId, targetFolderId, precondition);
      }
    } catch (SiblingNameConflictException e) {
      return siblingNameConflictResponse(sourceResourceType == CedarResourceType.FOLDER
          ? sourceFolder.getName() : sourceResource.getName());
    } catch (RevisionConflictException e) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
          .id(sourceId)
          .parameter("currentETag", RevisionPreconditionParser.format(e.getCurrentRevision()))
          .errorMessage("The resource has changed since it was read")
          .build();
    }
    if (moved == null) {
      BackendCallResult<?> backendCallResult = new BackendCallResult<>();
      backendCallResult.addError(CedarErrorType.SERVER_ERROR)
          .errorKey(CedarErrorKey.NODE_NOT_MOVED)
          .message("There was an error while moving the resource");
      throw new CedarBackendException(backendCallResult);
    } else {
      if (sourceResourceType == CedarResourceType.FOLDER) {
        searchPermissionEnqueueService.folderMoved(sourceId.getId());
      } else {
        searchPermissionEnqueueService.resourceMoved(sourceId.getId());
      }
      FileSystemResource movedNode = folderSession.findResourceById(sourceId);
      UriBuilder builder = uriInfo.getAbsolutePathBuilder();
      URI uri = builder.build();
      return Response.created(uri).header(HttpHeaders.ETAG, RevisionPreconditionParser.format(moved.revision()))
          .entity(movedNode).build();
    }
  }

  @POST
  @Timed
  @Path("/rename-resource")
  @Operation(summary = "Rename resource", description = "Change name and/or description of a resource. Folders or artifacts (fields, elements, templates, "
          + "instances) can be altered.", tags = {"Command", "File Operations"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "Parameters of the rename operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.RenameRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response renameResource() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter nameParam = c.request().getRequestBody().get(SCHEMA_ORG_NAME);
    CedarParameter descriptionParam = c.request().getRequestBody().get(SCHEMA_ORG_DESCRIPTION);
    CedarParameter idParam = c.request().getRequestBody().get(LinkedData.ID);
    // Was read without checking, so a body with no identifier became a 500 further down.
    c.must(idParam).be(NonEmpty);

    String id = idParam.stringValue();

    CedarResourceId untypedResourceId = CedarUntypedResourceId.build(id);
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);

    CedarResourceType resourceType = folderSession.getResourceType(untypedResourceId);
    if (resourceType == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.NODE_NOT_FOUND)
          .parameter("resourceId", untypedResourceId);
    }
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

    String expectedEtag = c.getIfMatchHeader();
    if (expectedEtag == null || expectedEtag.isBlank()) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_REQUIRED)
          .errorMessage("Renaming a resource requires the ETag returned by GET in If-Match")
          .build();
    }

    if (isFolder) {
      return updateFolderNameAndDescriptionInGraphDb(c, (CedarFolderId) fsResourceId);
    } else {
      String artifactServerUrl = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, (CedarArtifactId) fsResourceId);

      ClassicHttpResponse templateCurrentProxyResponse = ProxyUtil.proxyGet(artifactServerUrl, c);
      int currentStatusCode = templateCurrentProxyResponse.getCode();
      if (currentStatusCode != HttpStatus.SC_OK) {
        // artifact was not created
        return generateStatusResponse(templateCurrentProxyResponse);
      } else {
        HttpEntity currentTemplateEntity = templateCurrentProxyResponse.getEntity();
        if (currentTemplateEntity != null) {
          try {
            String currentTemplateEntityContent = EntityUtils.toString(currentTemplateEntity, StandardCharsets.UTF_8);
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
              return executeResourceCreateOrUpdateViaPut(c, resourceType, (CedarArtifactId) fsResourceId,
                  Optional.empty(), JsonMapper.MAPPER.writeValueAsString(currentTemplateJsonNode), false,
                  expectedEtag);
            } else {
              return CedarResponse.badRequest()
                  .errorKey(CedarErrorKey.NOTHING_TO_DO)
                  .errorMessage("The name and the description are unchanged. There is nothing to do!")
                  .parameter(SCHEMA_ORG_NAME, name)
                  .parameter(SCHEMA_ORG_DESCRIPTION, description)
                  .build();
            }
          } catch (IOException | ParseException e) {
            throw new CedarProcessingException(e);
          }
        }
        return CedarResponse.internalServerError().build();
      }
    }
  }

}
