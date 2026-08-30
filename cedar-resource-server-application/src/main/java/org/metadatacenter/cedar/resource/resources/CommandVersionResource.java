package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.hc.core5.http.HttpStatus;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.util.TemplateVersionFreezer;
import org.metadatacenter.cedar.resource.util.TerminologyVersionResolver;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.cedar.artifact.ArtifactServerUtil;
import org.metadatacenter.cedar.deltafinder.Delta;
import org.metadatacenter.cedar.deltafinder.DeltaFinder;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorType;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarObjectNotFoundException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.http.CedarResponseStatus;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarSchemaArtifactId;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.id.CedarUntypedSchemaArtifactId;
import org.metadatacenter.model.*;
import org.metadatacenter.model.folderserver.basic.FileSystemResource;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerTemplate;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerArtifactCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerSchemaArtifactCurrentUserReport;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.server.resource.CloneInstancesEnqueueService;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.auth.CurrentUserResourcePermissions;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
import org.metadatacenter.util.CedarResourceTypeUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_TEMPLATE_ID;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_NAME;
import static org.metadatacenter.constant.CedarQueryParameters.QP_RESOURCE_TYPES;
import static org.metadatacenter.model.ModelNodeNames.*;
import static org.metadatacenter.model.ModelPaths.AT_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandVersionResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(CommandVersionResource.class);
  private static CloneInstancesEnqueueService cloneInstanceEnqueueService;

  public CommandVersionResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectCloneInstancesEnqueueServices(CloneInstancesEnqueueService cies) {
    cloneInstanceEnqueueService = cies;
  }

  @POST
  @Timed
  @Path("/publish-artifact")
  @Operation(summary = "Publish artifact.", description = "Publish artifact. The 'bibo:status' of the artifact will be changed from 'bibo:draft' to "
          + "'bibo:published'. The 'pav:version' will be also set.", tags = {"Validation", "Command", "Versioning"})
  @RequestBody(description = "Info about the publishing process", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.PublishArtifactRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response publishArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter newVersionParam = c.request().getRequestBody().get("newVersion");

    String id = idParam.stringValue();
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);

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

    return publishArtifact(c, aid, newVersion);
  }

  private Response publishArtifact(CedarRequestContext c, CedarUntypedSchemaArtifactId aid,
                                   ResourceVersion newVersion) throws CedarException {
    userMustHaveReadAccessToArtifact(c, aid);

    FolderServerArtifactCurrentUserReport folderServerResourceOld = getArtifactReport(c, aid);

    CurrentUserResourcePermissions currentUserResourcePermissions = folderServerResourceOld.getCurrentUserPermissions();
    if (!currentUserResourcePermissions.isCanPublish()) {
      return CedarResponse.badRequest()
          .errorKey(currentUserResourcePermissions.getPublishErrorKey())
          .parameter("id", aid.getId())
          .build();
    }

    CedarResourceType resourceType = folderServerResourceOld.getType();

    CedarPermission updatePermission = CedarPermission.getUpdateForVersionedArtifactType(resourceType);
    if (updatePermission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_RESOURCE_TYPE)
          .errorMessage("You passed an illegal artifact type for versioning:'" + resourceType.getValue() + "'. The " +
              "allowed values are:" +
              CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .parameter("invalidResourceType", resourceType.getValue())
          .parameter("allowedResourceTypes", CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .build();
    }

    // Check update permission
    c.must(c.user()).have(updatePermission);

    var artifactContent = ArtifactServerUtil.getSchemaArtifactWithEtagFromArtifactServer(resourceType, aid, c,
        microserviceUrlUtil, response);
    String getResponse = artifactContent.content();
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

          // Only a draft may be published. The isCanPublish() flag checked above is computed upstream
          // and is wrongly true for an already-published artifact (the status guard behind it is
          // skipped when the object does not carry publication status), so re-publishing slipped
          // through. Check the real status read back from the artifact server, which is the source of
          // truth.
          JsonNode oldStatusNode = getJsonNode.get(BIBO_STATUS);
          if (oldStatusNode != null && BiboStatus.PUBLISHED.getValue().equals(oldStatusNode.textValue())) {
            return CedarResponse.badRequest()
                .errorKey(CedarErrorKey.PUBLISH_ONLY_DRAFT)
                .errorMessage("Only a draft artifact can be published; this artifact is already published.")
                .parameter("id", aid.getId())
                .build();
          }

          //publish on the artifact server
          ((ObjectNode) getJsonNode).put(PAV_VERSION, newVersion.getValue());
          ((ObjectNode) getJsonNode).put(BIBO_STATUS, BiboStatus.PUBLISHED.getValue());

          // Freeze-on-publish (VERSIONING-ROADMAP "The Model" §7): pin every unpinned controlled-term constraint to
          // its vocabulary's current version, so the published artifact reproduces its exact term
          // state instead of drifting with "latest". Fully fail-safe -- a resolver error, or an
          // unreachable/off terminology store, leaves the artifact unchanged and never blocks publish.
          try {
            String terminologyBase = cedarConfig.getServers().getTerminology().getBase();
            TerminologyVersionResolver resolver =
                new TerminologyVersionResolver(terminologyBase, c.getAuthorizationHeader());
            TemplateVersionFreezer.freeze(getJsonNode, resolver);
            if (resolver.getSkippedResolutionCount() > 0) {
              log.warn("Freeze-on-publish skipped {} terminology version lookup(s) for {}; publishing without those pins",
                  resolver.getSkippedResolutionCount(), aid.getId());
            }
          } catch (Exception freezeSkipped) {
            log.warn("Freeze-on-publish skipped for {}; publishing without terminology version pins: {}",
                aid.getId(), freezeSkipped.toString());
          }

          String content = JsonMapper.MAPPER.writeValueAsString(getJsonNode);
          Response putResponse = ArtifactServerUtil.putSchemaArtifactToArtifactServer(resourceType, aid, c, content,
              microserviceUrlUtil, artifactContent.etag());
          int putStatus = putResponse.getStatus();

          if (putStatus == HttpStatus.SC_OK) {
            boolean graphUpdated = false;
            try {
              // publish in Neo4j server
              FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);

              if (folderServerResourceOld instanceof FolderServerSchemaArtifactCurrentUserReport) {
                FolderServerSchemaArtifactCurrentUserReport schemaArtifact =
                    (FolderServerSchemaArtifactCurrentUserReport) folderServerResourceOld;
                schemaArtifact.setLatestPublishedVersion(true);
              }

              Map<NodeProperty, String> updates = new HashMap<>();
              updates.put(NodeProperty.VERSION, newVersion.getValue());
              updates.put(NodeProperty.PUBLICATION_STATUS, BiboStatus.PUBLISHED.getValue());
              FolderServerArtifact publishedResource =
                  folderSession.updateArtifactById(aid, resourceType, updates);
              if (publishedResource == null) {
                throw new CedarProcessingException("The published artifact could not be updated in the graph");
              }
              graphUpdated = true;

              if (resourceType.isVersioned()) {
                folderSession.setLatestVersion(aid);
                folderSession.unsetLatestDraftVersion(aid);
                folderSession.setLatestPublishedVersion(aid);
                if (folderServerResourceOld instanceof FolderServerSchemaArtifactCurrentUserReport schemaArtifact) {
                  if (schemaArtifact.getPreviousVersion() != null) {
                    folderSession.unsetLatestPublishedVersion(schemaArtifact.getPreviousVersion());
                  }
                }
              }

              FolderServerArtifact updatedResource = folderSession.findArtifactById(aid);
              updateIndexResource(updatedResource, c);

              // read the updated previous version
              if (folderServerResourceOld instanceof FolderServerSchemaArtifactCurrentUserReport schemaArtifact) {
                if (schemaArtifact.hasPreviousVersion()) {
                  CedarSchemaArtifactId prevId = schemaArtifact.getPreviousVersion();
                  FolderServerArtifact folderServerResourcePrev = folderSession.findArtifactById(prevId);
                  updateIndexResource(folderServerResourcePrev, c);
                }
              }

              return Response.ok().entity(updatedResource).build();
            } finally {
              if (!graphUpdated) {
                restorePublishedArtifact(c, resourceType, aid, getResponse,
                    putResponse.getHeaderString(HttpHeaders.ETAG));
              }
            }

          }
        }
      } catch (Exception e) {
        log.error("Error while publishing the artifact", e);
      }
    }
    return CedarResponse.internalServerError()
        .errorMessage("There was an error while publishing the artifact")
        .parameter("id", aid)
        .build();
  }

  /** Restore a publish write only while its exact replacement ETag is still current. */
  private void restorePublishedArtifact(CedarRequestContext context, CedarResourceType resourceType,
                                        CedarSchemaArtifactId artifactId, String preImage,
                                        String publishedEtag) {
    if (publishedEtag == null || publishedEtag.isBlank()) {
      log.error("Failed publish left {} changed on the artifact server: conditional rollback is unavailable",
          artifactId);
      return;
    }
    try {
      Response rollback = ArtifactServerUtil.putSchemaArtifactToArtifactServer(resourceType, artifactId, context,
          preImage, microserviceUrlUtil, publishedEtag);
      if (rollback.getStatus() != HttpStatus.SC_OK) {
        log.error("Failed publish left {} changed on the artifact server: conditional rollback answered {}",
            artifactId, rollback.getStatus());
      }
    } catch (Exception e) {
      log.error("Failed publish left {} changed on the artifact server: conditional rollback failed", artifactId, e);
    }
  }

  private void createCopyOfInstancesWithNewTemplate(CedarRequestContext context, CedarTemplateId oldId,
                                                    CedarTemplateId newId, String newFolderName) {
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(context);
    long instanceCount = folderSession.getNumberOfInstances(CedarTemplateId.build(oldId.getId()));
    if (instanceCount > 0) {
      cloneInstanceEnqueueService.cloneInstances(oldId, newId, newFolderName);
    }
  }

  @POST
  @Timed
  @Path("/create-draft-artifact")
  @Operation(summary = "Create draft artifact.", description = "Create draft artifact out of a published artifact. A new artifact will be created in the supplied "
          + "folder. The version of the new artifact must be set, and must follow the current published version. "
          + "The sharing settings of the old artifact can be copied over to the new artifact..", tags = {"Validation", "Command", "Versioning"})
  @RequestBody(description = "Info about the creation process", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CreateDraftArtifactRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response createDraftArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter newVersionParam = c.request().getRequestBody().get("newVersion");
    CedarParameter folderIdParam = c.request().getRequestBody().get("folderId");
    CedarParameter propagateSharingParam = c.request().getRequestBody().get("propagateSharing");
    CedarParameter newFolderNameParam = c.request().getRequestBody().get("newFolderName");

    String id = idParam.stringValue();
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);
    String folderId = folderIdParam.stringValue();
    CedarFolderId fid = CedarFolderId.build(folderId);
    String propagateSharingString = propagateSharingParam.stringValue();
    String newFolderNameString = newFolderNameParam.stringValue();

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

    boolean propagateSharing = Boolean.parseBoolean(propagateSharingString);

    return createDraftArtifact(c, aid, newVersion, fid, propagateSharing, newFolderNameString);
  }

  private Response createDraftArtifact(CedarRequestContext c, CedarUntypedSchemaArtifactId aid,
                                       ResourceVersion newVersion, CedarFolderId fid, boolean propagateSharing,
                                       String newFolderName) throws CedarException {

    userMustHaveReadAccessToArtifact(c, aid);

    FolderServerArtifactCurrentUserReport folderServerResourceOld = getArtifactReport(c, aid);

    CurrentUserResourcePermissions currentUserResourcePermissions = folderServerResourceOld.getCurrentUserPermissions();
    if (!currentUserResourcePermissions.isCanCreateDraft()) {
      return CedarResponse.badRequest()
          .errorKey(currentUserResourcePermissions.getCreateDraftErrorKey())
          .parameter("id", aid.getId())
          .build();
    }

    CedarResourceType artifactType = folderServerResourceOld.getType();

    CedarPermission updatePermission = CedarPermission.getUpdateForVersionedArtifactType(artifactType);
    if (updatePermission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_ARTIFACT_TYPE)
          .errorMessage("You passed an illegal artifact type for versioning:'" + artifactType.getValue() + "'. The " +
              "allowed values are:" +
              CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .parameter("invalidResourceType", artifactType.getValue())
          .parameter("allowedResourceTypes", CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .build();

    }

    // Check update permission
    c.must(c.user()).have(updatePermission);

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToFolder(c, fid);

    // Check if the user has write permission to the target folder
    userMustHaveWriteAccessToFolder(c, fid);

    String getResponse = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(artifactType, aid, c,
        microserviceUrlUtil, response);
    if (getResponse != null) {
      JsonNode getJsonNode = null;
      try {
        getJsonNode = JsonMapper.MAPPER.readTree(getResponse);
        if (getJsonNode != null) {

          // Only a published artifact may be the source of a draft. As with publishing above, the
          // permission report is computed from graph metadata and is not a sufficient state guard:
          // a newly-created draft was observed with isCanCreateDraft() set, allowing a draft to mint
          // another draft. The artifact server document is the content-state source of truth.
          JsonNode oldStatusNode = getJsonNode.get(BIBO_STATUS);
          if (oldStatusNode == null
              || !BiboStatus.PUBLISHED.getValue().equals(oldStatusNode.textValue())) {
            return CedarResponse.badRequest()
                .errorKey(CedarErrorKey.CREATE_DRAFT_ONLY_FROM_PUBLISHED)
                .errorMessage("A draft can only be created from a published artifact.")
                .parameter("id", aid.getId())
                .build();
          }

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
          newDocument.put(ModelNodeNames.PAV_PREVIOUS_VERSION, aid.getId());
          // Null rather than removed: the artifact server assigns the identifier of the draft it is
          // about to create, and the key carrying null is how anything asks for one.
          newDocument.putNull(ModelNodeNames.JSON_LD_ID);

          if (newDocument.has(ModelNodeNames.ANNOTATIONS) && newDocument.get(ModelNodeNames.ANNOTATIONS).isObject()) {
            ObjectNode annotationsNode = (ObjectNode) newDocument.get(ModelNodeNames.ANNOTATIONS);
            annotationsNode.remove(ModelNodeNames.DATACITE_DOI_URI);
            if (annotationsNode.isEmpty()) {
              newDocument.remove(ModelNodeNames.ANNOTATIONS);
            }
          }

          userMustHaveWriteAccessToFolder(c, fid);

          String artifactServerPostRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(newDocument);

          Response artifactServerPostResponse = executeResourcePostToArtifactServer(c, artifactType,
              artifactServerPostRequestBodyAsString);

          int artifactServerPostStatus = artifactServerPostResponse.getStatus();
          InputStream is = (InputStream) artifactServerPostResponse.getEntity();
          JsonNode artifactServerPostResponseNode = JsonMapper.MAPPER.readTree(is);
          if (artifactServerPostStatus == CedarResponseStatus.CREATED.getStatusCode()) {
            JsonNode atId = artifactServerPostResponseNode.at(AT_ID);
            String newIdString = atId.asText();
            CedarUntypedSchemaArtifactId newId = CedarUntypedSchemaArtifactId.build(newIdString);

            boolean draftReachedTheGraph = false;
            try {
              FolderServerArtifact sourceResource = folderSession.findSchemaArtifactById(aid);

              BiboStatus status = BiboStatus.DRAFT;

              FolderServerArtifact brandNewResource = GraphDbObjectBuilder.forResourceType(artifactType, newId,
                  sourceResource.getName(),
                  sourceResource.getDescription(), sourceResource.getIdentifier(), newVersion, status);
              if (brandNewResource instanceof FolderServerSchemaArtifact schemaArtifact) {
                schemaArtifact.setPreviousVersion(aid);
                schemaArtifact.setLatestVersion(true);
                schemaArtifact.setLatestDraftVersion(true);
                schemaArtifact.setLatestPublishedVersion(false);
              }

              FolderServerArtifact newResource = folderSession.createResourceAsChildOfId(brandNewResource, fid);
              if (newResource == null) {
                BackendCallResult backendCallResult = new BackendCallResult();
                backendCallResult.addError(CedarErrorType.SERVER_ERROR)
                    .errorKey(CedarErrorKey.DRAFT_NOT_CREATED)
                    .message("There was an error while creating the draft version of the artifact");
                throw new CedarBackendException(backendCallResult);
              }
              draftReachedTheGraph = true;

              // Do not demote the source until its successor exists in both stores. Previously this
              // happened before graph creation, so a failed create left no draft and no latest source.
              folderSession.unsetLatestVersion(aid);
              if (propagateSharing) {
                ResourcePermissionServiceSession permissionSession =
                    dataServices.getResourcePermissionServiceSession(c);
                CedarNodePermissionsWithExtract permissions = permissionSession.getResourcePermissions(aid);
                ResourcePermissionsRequest permissionsRequest = permissions.toRequest();
                ResourcePermissionUser newOwner = new ResourcePermissionUser();
                newOwner.setId(c.getCedarUser().getId());
                permissionsRequest.setOwner(newOwner);
                BackendCallResult backendCallResult = permissionSession.updateResourcePermissions(newId,
                    permissionsRequest);
                if (backendCallResult.isError()) {
                  throw new CedarBackendException(backendCallResult);
                }
              }

              FolderServerArtifact createdNewResource = folderSession.findArtifactById(newId);
              createIndexArtifact(createdNewResource, c);
              FolderServerArtifact updatedSourceResource = folderSession.findArtifactById(aid);
              updateIndexResource(updatedSourceResource, c);

              if (artifactType == CedarResourceType.TEMPLATE && newFolderName != null && !newFolderName.isEmpty()) {
                createCopyOfInstancesWithNewTemplate(c, CedarTemplateId.build(aid.getId()),
                    CedarTemplateId.build(newId.getId()), newFolderName);
              }

              UriBuilder builder = uriInfo.getAbsolutePathBuilder();
              URI uri = builder.build();

              return Response.created(uri).entity(createdNewResource).build();
            } finally {
              if (!draftReachedTheGraph) {
                discardArtifactAfterFailedCreate(c, artifactType, newId);
              }
            }

            /// this is the end of Neo4j creation
          } else {
            return CedarResponse.internalServerError()
                .errorMessage("There was an error while creating the artifact on the artifact server")
                .parameter("responseCode", artifactServerPostStatus)
                .parameter("responseDocument", artifactServerPostResponseNode)
                .build();
          }
        }
      } catch (Exception e) {
        log.error("Error while creating the draft version of the artifact", e);
      }
    }
    return CedarResponse.internalServerError()
        .errorMessage("There was an error while creating the draft version of the artifact")
        .parameter("id", aid)
        .build();
  }

  @POST
  @Timed
  @Path("/check-update-template/{template_id}")
  @Operation(summary = "Check whether a template can be updated", description = "Check whether an existing template can be updated with the supplied template definition. The "
          + "destructive and non-destructive changes between the stored template and the supplied template are "
          + "computed, and the number of affected instances is reported.", tags = {"Command", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response checkUpdateTemplate(
      @Parameter(description = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    userMustHaveReadAccessToArtifact(c, tid);

    Map<String, Object> resp = new HashMap<>();

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    long instanceCount = folderSession.getNumberOfInstances(CedarTemplateId.build(id));

    if (instanceCount == 0) {
      resp.put("canBeUpdated", true);
      return Response.ok().entity(resp).build();
    }

    String getResponse = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(CedarResourceType.TEMPLATE, tid, c,
        microserviceUrlUtil, response);
    if (getResponse == null || getResponse.isBlank()) {
      throw new CedarObjectNotFoundException(tid.getId());
    }

    try {
      JsonNode oldTemplateJsonNode;
      JsonNode newTemplateJsonNode;
      oldTemplateJsonNode = JsonMapper.MAPPER.readTree(getResponse);
      newTemplateJsonNode = JsonMapper.MAPPER.readTree(c.request().getRequestBody().asJsonString());
      if (!(oldTemplateJsonNode instanceof ObjectNode oldTemplateObjectNode)
          || !(newTemplateJsonNode instanceof ObjectNode newTemplateObjectNode)) {
        throw new IllegalArgumentException("Both stored and submitted templates must be JSON objects");
      }

      JsonArtifactReader reader = new JsonArtifactReader();
      TemplateSchemaArtifact oldModelArtifact = reader.readTemplateSchemaArtifact(oldTemplateObjectNode);
      TemplateSchemaArtifact newModelArtifact = reader.readTemplateSchemaArtifact(newTemplateObjectNode);

      DeltaFinder finder = new DeltaFinder();
      Delta delta = finder.findDelta(oldModelArtifact, newModelArtifact);

      List<Change> destructive = delta.getDestructiveChanges();
      List<Change> nonDestructive = delta.getNonDestructiveChanges();
      resp.put("destructiveChanges", destructive.size());
      resp.put("nonDestructiveChanges", nonDestructive.size());
      resp.put("canBeUpdated", destructive.isEmpty() && nonDestructive.isEmpty());
      resp.put("numberOfInstances", instanceCount);

      // The version this call reports as "old", and the next version it predicts, both come from the
      // stored template. Reading them off the submitted body instead made the prediction a function
      // of what the client sent: /publish-create-draft-template derives the same pair from the
      // stored document, so a client whose submitted pav:version differed from what is stored was
      // told one next version here and given another when it published.
      ResourceVersion oldVersion =
          ResourceVersion.forValueWithValidation(oldModelArtifact.version()
              .orElseThrow(() -> new IllegalArgumentException("Stored template has no pav:version"))
              .toString());
      ResourceVersion newVersion = oldVersion.nextPatchVersion();

      resp.put("oldVersion", oldVersion);
      resp.put("pav:version", newVersion);
      resp.put("schema:name", oldModelArtifact.name());
      return Response.ok().entity(resp).build();
    } catch (CedarException e) {
      throw e;
    } catch (Exception e) {
      log.error("Error while checking template {} for update", tid.getId(), e);
      throw new CedarProcessingException("There was an error while checking the template for update", e);
    }
  }

  @POST
  @Timed
  @Path("/publish-create-draft-template/{template_id}")
  @Operation(summary = "Publish a template and create a new draft", description = "Publish the given template, then create a new draft version from it and apply the supplied template "
          + "definition. Instances of the source template can be copied into a new folder.", tags = {"Command", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response publishCreateDraftTemplate(
      @Parameter(description = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id,
      @Parameter(description = "Name of the folder to copy the template instances into.")
      @QueryParam(QP_FOLDER_NAME) Optional<String> folderName) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    userMustHaveReadAccessToArtifact(c, tid);

    String getResponse = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(CedarResourceType.TEMPLATE, tid, c,
        microserviceUrlUtil, response);
    if (getResponse != null) {
      JsonNode oldTemplateJsonNode;
      JsonNode newTemplateJsonNode;
      try {
        oldTemplateJsonNode = JsonMapper.MAPPER.readTree(getResponse);
        newTemplateJsonNode = JsonMapper.MAPPER.readTree(c.request().getRequestBody().asJsonString());
        if (oldTemplateJsonNode != null && newTemplateJsonNode != null) {
          JsonArtifactReader reader = new JsonArtifactReader();
          TemplateSchemaArtifact oldModelArtifact = reader.readTemplateSchemaArtifact((ObjectNode) oldTemplateJsonNode);
          TemplateSchemaArtifact newModelArtifact = reader.readTemplateSchemaArtifact((ObjectNode) newTemplateJsonNode);

          JsonNode jsonNode = oldTemplateJsonNode.get(PAV_VERSION);
          String oldVersionString = jsonNode.asText();
          ResourceVersion oldVersion = ResourceVersion.forValueWithValidation(oldVersionString);

          ResourceVersion newVersion = oldVersion.nextPatchVersion();

          FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
          ResourcePermissionServiceSession permissionSession = dataServices.getResourcePermissionServiceSession(c);

          FileSystemResource artifact = folderSession.findArtifactById(tid);
          List<FolderServerResourceExtract> pathInfo = PathInfoBuilder.getResourcePathExtract(c, folderSession,
              permissionSession, artifact);
          FolderServerResourceExtract parentFolderExtract = pathInfo.get(pathInfo.size() - 2);

          CedarFolderId fid = CedarFolderId.build(parentFolderExtract.getId());

          Response publishResponse = publishArtifact(c, CedarUntypedSchemaArtifactId.build(tid.getId()), oldVersion);
          if (publishResponse.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            return publishResponse;
          }

          Response createResponse = createDraftArtifact(c, CedarUntypedSchemaArtifactId.build(tid.getId()),
              newVersion, fid, true, folderName.orElse(null));
          if (createResponse.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            return createResponse;
          }

          if (!(createResponse.getEntity() instanceof FolderServerTemplate entity)) {
            log.error("Draft creation for template {} returned a successful response without a template entity", tid);
            return CedarResponse.internalServerError()
                .errorMessage("Draft creation returned an invalid response")
                .parameter("id", tid)
                .build();
          }
          String newTemplateIdString = entity.getId();
          CedarTemplateId newTemplateId = CedarTemplateId.build(newTemplateIdString);

          ((ObjectNode) newTemplateJsonNode).put(JSON_LD_ID, newTemplateIdString);
          ((ObjectNode) newTemplateJsonNode).put(PAV_VERSION, newVersion.getValue());
          String newDraftEtag = ArtifactServerUtil.getSchemaArtifactWithEtagFromArtifactServer(
              CedarResourceType.TEMPLATE, newTemplateId, c, microserviceUrlUtil, null).etag();
          return executeResourceUpdateOnArtifactServerAndGraphDb(c, CedarResourceType.TEMPLATE, newTemplateId,
              JsonMapper.MAPPER.writeValueAsString(newTemplateJsonNode), false, newDraftEtag);
        }
      } catch (CedarException e) {
        throw e;
      } catch (Exception e) {
        log.error("Error while publishing template {} and creating its draft", tid, e);
        return CedarResponse.internalServerError()
            .errorMessage("There was an error while publishing the template and creating its draft")
            .parameter("id", tid)
            .build();
      }
    }
    return CedarResponse.internalServerError()
        .errorMessage("There was an error while publishing the template and creating its draft")
        .parameter("id", tid)
        .build();
  }
}
