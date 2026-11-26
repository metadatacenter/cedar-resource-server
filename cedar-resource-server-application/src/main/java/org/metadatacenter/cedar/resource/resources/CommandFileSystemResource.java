package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphRequest;
import org.metadatacenter.cedar.resource.model.InclusionSubgraphUpdateReport;
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
import org.metadatacenter.util.artifact.ArtifactDocument;
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
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.SiblingNameConflictException;
import org.metadatacenter.server.VersionedResource;
import org.metadatacenter.server.VersionedResourcePermissions;
import org.metadatacenter.server.resource.ArtifactCopyOperations;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.permission.resource.ResourceCapability;
import org.metadatacenter.util.ModelUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.http.ArtifactServiceClient;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.metadatacenter.model.ModelNodeNames.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandFileSystemResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandFileSystemResource.class);

  /** The body key under which a copy's caller may choose the copy's identity. */
  private static final String COPY_ID = "copyId";

  public CommandFileSystemResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/copy-artifact-to-folder")
  @Operation(summary = "Copy artifact", description = "Copy artifact to a given folder. A copy of the given artifact will be created in the given folder, "
          + "with a new name. Only artifacts (fields, elements, templates, instances) can be copied. The caller may "
          + "choose the copy's identifier in copyId; an identifier already in use is refused with 412 before "
          + "anything is written, so repeating a copy whose answer was lost cannot make a second one.",
      tags = {"Command", "File Operations"})
  @RequestBody(description = "Parameters of the copy operation", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CopyRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "The copy, as the artifact server stored it",
          content = @Content(schema = @Schema(implementation = ArtifactDocument.class)),
          headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Location",
              description = "Where the copy can be read.", schema = @Schema(type = "string"))),
      @ApiResponse(responseCode = "200",
          description = "Reached only when the artifact server returns no entity for the copy, which leaves nothing "
              + "to return. The response carries no body."),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "The identifier in copyId already names an artifact; nothing was written"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error"),
      @ApiResponse(responseCode = "502", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Invalid response from artifact service")
  })
  public Response copyResourceToFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    // Read through CedarParameter rather than straight off the JsonNode: a missing field used to be a
    // null dereference, which reached the caller as 500 for what is plainly a bad request.
    c.request().getRequestBody().mustHaveOnly(LinkedData.ID, "targetFolderId", "nameTemplate", COPY_ID);
    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter targetFolderParam = c.request().getRequestBody().get("targetFolderId");
    CedarParameter nameTemplateParam = c.request().getRequestBody().get("nameTemplate");
    CedarParameter copyIdParam = c.request().getRequestBody().get(COPY_ID);
    c.must(idParam).be(NonEmpty);
    c.must(targetFolderParam).be(NonEmpty);
    c.must(nameTemplateParam).be(NonEmpty);

    String id = linkedDataUtil.resolveResourceId(idParam.stringValue());
    String folderId = linkedDataUtil.resolveResourceId(CedarResourceType.FOLDER, targetFolderParam.stringValue());
    String nameTemplate = nameTemplateParam.stringValue();


    CedarUntypedArtifactId untypedSourceArtifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    CedarResourceType resourceType = folderSession.getResourceType(untypedSourceArtifactId);

    // A folder is not an artifact, so the artifact check below would answer that it does not exist. The folder's
    // own check still comes first, so the refusal tells nobody about a folder they cannot see.
    if (resourceType == CedarResourceType.FOLDER) {
      userMustHaveCapabilityOnFolder(c, CedarFolderId.build(id),
          org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.FOLDER_COPY_NOT_ALLOWED)
          .message("Folder copy is not allowed")
          .build();
    }

    CedarArtifactId sourceArtifactId = CedarArtifactId.build(id, resourceType);

    CedarFolderId targetFolderId = CedarFolderId.build(folderId);

    userMustHaveCapabilityOnArtifact(c, sourceArtifactId,
        org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);

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
          .message("Unknown resource type:" + resourceType.getValue())
          .parameter("resourceType", resourceType.getValue())
          .build();
    }

    // Check read permission
    c.must(c.user()).have(permission1);

    // Check create permission
    c.must(c.user()).have(permission2);

    userMustHaveCapabilityOnFolder(c, targetFolderId,
        org.metadatacenter.server.security.model.permission.resource.ResourceCapability.COPY_INTO_FOLDER);

    // A copy at the identity the caller chose can be looked up when the answer is lost, and a repeat of
    // the request finds the identity taken instead of making a second copy.
    Optional<CedarArtifactId> copyId = Optional.empty();
    if (!copyIdParam.isEmpty()) {
      copyId = Optional.of(suppliedArtifactIdentity(COPY_ID, copyIdParam.stringValue(), resourceType));
      Response occupied = occupiedIdentityRefusal(c, resourceType, copyId.get());
      if (occupied != null) {
        return occupied;
      }
    }

    String originalDocument;
    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, sourceArtifactId);
      ClassicHttpResponse proxyResponse = new ArtifactServiceClient(cedarConfig).get(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      int statusCode = proxyResponse.getCode();
      if (statusCode != HttpStatus.SC_OK) {
        return generateStatusResponse(proxyResponse);
      }

      HttpEntity entity = proxyResponse.getEntity();
      if (entity == null) {
        return CedarResponse.badGateway()
            .message("Artifact service returned an empty source artifact")
            .id(sourceArtifactId)
            .build();
      }

      originalDocument = EntityUtils.toString(entity, StandardCharsets.UTF_8);
      if (originalDocument.isBlank()) {
        return CedarResponse.badGateway()
            .message("Artifact service returned an empty source artifact")
            .id(sourceArtifactId)
            .build();
      }
      JsonNode jsonNode = JsonMapper.STRICT_MAPPER.readTree(originalDocument);
      ArtifactCopyOperations.prepareDerivedBody((ObjectNode) jsonNode);
      if (copyId.isPresent()) {
        ((ObjectNode) jsonNode).put(LinkedData.ID, copyId.get().getId());
      }
      String oldName = ModelUtil.extractNameFromResource(resourceType, jsonNode).getValue();
      if (oldName == null) {
        oldName = "";
      }
      String newName = nameTemplate.replace("{{name}}", oldName);
      ((ObjectNode) jsonNode).put(PAV_DERIVED_FROM, id);
      if (resourceType.isVersioned()) {
        ((ObjectNode) jsonNode).put(PAV_VERSION, ResourceVersion.ZERO_ZERO_ONE.getValue());
        ((ObjectNode) jsonNode).put(BIBO_STATUS, BiboStatus.DRAFT.getValue());
        ((ObjectNode) jsonNode).remove(PAV_PREVIOUS_VERSION);
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

    AtomicReference<Undo> undo = new AtomicReference<>();
    try {
      return describeUndo(copyThenRegister(c, resourceType, sourceArtifactId, targetFolderId, copyId, originalDocument,
          folderSession, undo), undo.get());
    } catch (CedarException e) {
      throw describeUndo(e, undo.get());
    }
  }

  /**
   * Stores the copy's document and then registers it in the graph. A document the graph does not register
   * is removed by the copy's cleanup, and {@code undo} then receives what became of it, for the failure the
   * copy is answered with.
   */
  private Response copyThenRegister(CedarRequestContext c, CedarResourceType resourceType,
                                    CedarArtifactId sourceArtifactId, CedarFolderId targetFolderId,
                                    Optional<CedarArtifactId> copyId, String originalDocument,
                                    FolderServiceSession folderSession, AtomicReference<Undo> undo)
      throws CedarException {
    String cleanupJob = artifactCreateCleanupService.prepare(resourceType, "copy", copyId);
    boolean stored = false;
    String storedId = copyId.map(CedarArtifactId::getId).orElse(null);
    boolean registered = false;
    try {
      ClassicHttpResponse templateProxyResponse =
          sendCreateToArtifactServer(c, resourceType, copyId, originalDocument, cleanupJob);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);

      int statusCode = templateProxyResponse.getCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        artifactCreateCleanupService.rejected(cleanupJob, statusCode);
        return generateStatusResponse(templateProxyResponse);
      } else {
        // artifact was created
        stored = true;
        HttpEntity entity = templateProxyResponse.getEntity();
        Header locationHeader = templateProxyResponse.getFirstHeader(HttpHeaders.LOCATION);
        String entityContent = EntityUtils.toString(entity, StandardCharsets.UTF_8);
        JsonNode jsonNode = JsonMapper.STRICT_MAPPER.readTree(entityContent);
        String createdId = jsonNode.get("@id").asText();
        storedId = createdId;
        CedarArtifactId newId = CedarArtifactId.build(createdId, resourceType);
        Header validator = templateProxyResponse.getFirstHeader(HttpHeaders.ETAG);
        artifactCreateCleanupService.created(cleanupJob, createdId, validator == null ? null : validator.getValue());

        FolderServerArtifact folderServerCreatedResource =
            ArtifactCopyOperations.registerCopy(folderSession, sourceArtifactId, newId, targetFolderId,
                resourceType,
                ModelUtil.extractNameFromResource(resourceType, jsonNode).getValue(),
                ModelUtil.extractDescriptionFromResource(resourceType, jsonNode).getValue(),
                ModelUtil.extractIdentifierFromResource(resourceType, jsonNode).getValue(), null, null, cleanupJob,
                org.metadatacenter.server.ArtifactCreationProvenance.fromDocument(jsonNode));

        if (folderServerCreatedResource == null) {
          return CedarResponse.badRequest().errorKey(CedarErrorKey.RESOURCE_NOT_CREATED).build();
        }
        registered = true;
        if (locationHeader != null) {
          response.setHeader(locationHeader.getName(), locationHeader.getValue());
        }
        if (templateProxyResponse.getEntity() != null) {
          // index the artifact that has been created
          createIndexArtifact(folderServerCreatedResource, c);
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
    } finally {
      artifactCreateCleanupService.cleanupNow(cleanupJob, c);
      if (stored && !registered) {
        undo.set(undoOfUnregisteredCreate(c, resourceType, storedId));
      }
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
          content = @Content(schema = @Schema(ref = "#/components/schemas/FolderOrArtifactRecord")),
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
    c.request().getRequestBody().mustHaveOnly(LinkedData.ID, "targetFolderId");
    CedarParameter sourceParam = c.request().getRequestBody().get(LinkedData.ID);
    CedarParameter targetParam = c.request().getRequestBody().get("targetFolderId");
    c.must(sourceParam).be(NonEmpty);
    c.must(targetParam).be(NonEmpty);

    String sId = linkedDataUtil.resolveResourceId(sourceParam.stringValue());
    String fId = linkedDataUtil.resolveResourceId(CedarResourceType.FOLDER, targetParam.stringValue());

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

    userMustHaveCapabilityOnFilesystemResource(c, sourceId,
        org.metadatacenter.server.security.model.permission.resource.ResourceCapability.MOVE_RESOURCE);

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
          .message("Unknown resource type:" + sourceResourceType.getValue())
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
            .message("The source folder can not be found:" + sourceId)
            .parameter("@id", sourceId)
            .build();
      }
    } else {
      sourceResource = folderSession.findArtifactById(sourceId.asArtifactId());
      if (sourceResource == null) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.SOURCE_RESOURCE_NOT_FOUND)
            .message("The source artifact can not be found:" + sourceId)
            .parameter("@id", sourceId)
            .build();
      }
    }

    // Check if the target folder exists
    FolderServerFolder targetFolder = folderSession.findFolderById(targetFolderId);
    if (targetFolder == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.TARGET_FOLDER_NOT_FOUND)
          .message("The target folder can not be found:" + targetFolderId)
          .parameter("targetFolderId", targetFolderId)
          .build();
    }

    userMustHaveCapabilityOnFolder(c, targetFolderId,
        org.metadatacenter.server.security.model.permission.resource.ResourceCapability.MOVE_INTO_FOLDER);

    String ifMatch = c.getIfMatchHeader();
    if (RevisionPreconditionParser.isAbsent(ifMatch)) {
      return CedarResponse.preconditionRequired()
          .id(sourceId)
          .message("Moving a resource requires the source resource ETag in If-Match")
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
          .message("The resource has changed since it was read")
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
  @Path("/create-draft-artifact")
  @Operation(summary = "Create draft artifact.", description = "Create draft artifact out of a published artifact. A new artifact will be created in the supplied "
          + "folder. The version of the new artifact must be set, and must follow the current published version. "
          + "The sharing settings of the old artifact can be copied over to the new artifact. The caller may "
          + "choose the draft's identifier in draftId; an identifier already in use is refused with 412 before "
          + "anything is written, so repeating a request whose answer was lost cannot make a second draft.",
      tags = {"Validation", "Command", "Versioning"})
  @RequestBody(description = "Info about the creation process", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CreateDraftArtifactRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "The draft artifact",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactRecord")),
          headers = @io.swagger.v3.oas.annotations.headers.Header(name = "Location", description = "Where the draft artifact can be read.",
              schema = @Schema(type = "string"))),
      @ApiResponse(responseCode = "200",
          description = "Reached only when the artifact server returns no entity for the draft, which leaves nothing "
              + "to return. The response carries no body."),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "Only the artifact's owner may create a draft from it, whatever role another user holds "
              + "(versioningOnlyByOwner). A caller who cannot read the artifact, cannot create in the target "
              + "folder, or lacks the permission to update artifacts of its type, is also refused."),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "The identifier in draftId already names an artifact; nothing was written"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response createDraftArtifact() throws CedarException {
    return operationResource(new CommandVersionResource(cedarConfig)).createDraftArtifact();
  }
  @POST
  @Timed
  @Path("/auth-user-callback")
  @Operation(summary = "Authentication user callback", description = "Endpoint called by the Keycloak Event Listener. Creates the CEDAR objects related to a user (home "
          + "folder, group membership) upon authentication. The caller must hold the user administration "
          + "permission, since the user to provision is named in the request body.")
  @RequestBody(description = "The authentication event and the user it concerns", required = true,
      content = @Content(schema = @Schema(ref = "#/components/schemas/AuthUserCallbackRequest")))
  @ApiResponses({
      @ApiResponse(responseCode = "201",
          description = "The user's objects were provisioned. The response carries no body."),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response authUserCallback() throws CedarException {
    return operationResource(new CommandGenericResource(cedarConfig)).authUserCallback();
  }
  @POST
  @Timed
  @Path("/update-inclusions-subgraph")
  @Operation(summary = "Update the inclusion subgraph of an artifact", description = "Propagate a change to the given artifact across the tree of affected artifacts, updating each "
          + "referencing artifact on the artifact server. The caller needs the readResource capability on the artifact "
          + "that changed and the updateResource capability on every artifact selected for update, and every artifact "
          + "selected for update must still be a draft, since publication fixes an artifact's content. If the caller "
          + "lacks either capability, or names a published target, the whole request is refused and nothing is "
          + "written. The response reports what became of each target.")
  @RequestBody(description = "The artifact that changed, and the elements and templates to consider", required = true,
      content = @Content(schema = @Schema(implementation = InclusionSubgraphRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Every target was written, or needed no write",
          content = @Content(schema = @Schema(implementation = InclusionSubgraphUpdateReport.class))),
      @ApiResponse(responseCode = "400", description = "The body is not JSON in the form of an inclusion subgraph "
          + "request (malformedJsonRequestBody), names no @id or a target that is not an artifact (invalidData), or "
          + "names one artifact twice. A target that is published (publishedArtifactCanNotBeChanged), or a template "
          + "whose instances the change would invalidate (invalidData), is refused too. Nothing was written.",
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "401", description = CommandInclusionSubgraphResource.UNAUTHORIZED,
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "403", description = "The caller cannot read the artifact that changed, or cannot "
          + "update a target. Nothing was written.",
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "404", description = "The artifact that changed, or a target, does not exist",
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "406", description = CommandInclusionSubgraphResource.NOT_ACCEPTABLE,
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "429", description = CommandInclusionSubgraphResource.RATE_LIMITED,
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "500", description = CommandInclusionSubgraphResource.INTERNAL_ERROR,
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "502", description = "The artifact server refused at least one of the writes "
          + "(upstreamServerError). The targets it accepted were updated. The body's objects.report holds the update "
          + "report, with the outcome of every target.",
          content = @Content(schema = @Schema(implementation = CedarError.class))),
      @ApiResponse(responseCode = "503", description = CommandInclusionSubgraphResource.UNAVAILABLE,
          content = @Content(schema = @Schema(implementation = CedarError.class)))
  })
  public Response updateInclusionSubgraph() throws CedarException, IOException {
    return operationResource(new CommandInclusionSubgraphResource(cedarConfig)).updateInclusionSubgraph();
  }
}
