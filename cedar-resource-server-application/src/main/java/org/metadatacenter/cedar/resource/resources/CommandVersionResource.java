package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.server.ArtifactGraphUpdateResult;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import org.metadatacenter.util.artifact.SchemaArtifactDocument;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.util.TemplateVersionFreezer;
import org.metadatacenter.cedar.resource.util.TerminologyVersionResolver;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.cedar.resource.artifact.ArtifactServerUtil;
import org.metadatacenter.cedar.deltafinder.Delta;
import org.metadatacenter.cedar.deltafinder.DeltaFinder;
import org.metadatacenter.cedar.deltafinder.change.Change;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorPack;
import org.metadatacenter.error.CedarErrorType;
import org.metadatacenter.cedar.resource.artifact.ArtifactServerRefusal;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarBadRequestException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarObjectNotFoundException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.http.CedarResponseStatus;
import org.metadatacenter.id.CedarArtifactId;
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
import org.metadatacenter.server.resource.ArtifactCopyOperations;
import org.metadatacenter.server.resource.CloneInstancesEnqueueService;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.auth.CurrentUserResourcePermissions;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
import org.metadatacenter.util.CedarResourceTypeUtil;
import org.metadatacenter.util.ModelUtil;
import org.metadatacenter.cedar.resource.restore.ArtifactRestoreJob;
import org.metadatacenter.cedar.resource.restore.RestoreOutcome;
import org.metadatacenter.util.http.ArtifactServiceClient;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.http.RevisionPreconditionParser;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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

  /** The body key under which the caller of create-draft-artifact may choose the draft's identity. */
  private static final String DRAFT_ID = "draftId";

  /** The query parameter under which the caller of publish-create-draft-template may choose it. */
  private static final String QP_DRAFT_ID = "draft_id";

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
      @ApiResponse(responseCode = "200", description = "The published artifact",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactRecord"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "Only the artifact's owner may publish it, whatever role another user holds "
              + "(versioningOnlyByOwner). A caller who cannot read the artifact, or lacks the permission to "
              + "update artifacts of its type, is also refused."),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response publishArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    c.request().getRequestBody().mustHaveOnly("@id", "newVersion");
    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter newVersionParam = c.request().getRequestBody().get("newVersion");

    String id = linkedDataUtil.resolveResourceId(idParam.stringValue());
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
    return publishArtifact(c, aid, newVersion, null);
  }

  private Response publishArtifact(CedarRequestContext c, CedarUntypedSchemaArtifactId aid,
                                   ResourceVersion newVersion, ArtifactServerUtil.ArtifactContent source) throws CedarException {
    return publishArtifact(c, aid, newVersion, source, null);
  }

  /**
   * As above. When {@code published} is given, it receives what putting the publication back would take,
   * as soon as both stores describe the publication. A command that publishes on the way to a later step
   * needs it if that step fails.
   */
  private Response publishArtifact(CedarRequestContext c, CedarUntypedSchemaArtifactId aid,
                                   ResourceVersion newVersion, ArtifactServerUtil.ArtifactContent source,
                                   AtomicReference<Publication> published) throws CedarException {
    userMustHaveCapabilityOnArtifact(c, aid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);

    FolderServerArtifactCurrentUserReport folderServerResourceOld = getArtifactReport(c, aid);

    CurrentUserResourcePermissions currentUserResourcePermissions = folderServerResourceOld.getCurrentUserPermissions();
    if (!currentUserResourcePermissions.isCanPublish()) {
      return versioningRefusal(currentUserResourcePermissions.getPublishErrorKey(), aid);
    }

    CedarResourceType resourceType = folderServerResourceOld.getType();

    CedarPermission updatePermission = CedarPermission.getUpdateForVersionedArtifactType(resourceType);
    if (updatePermission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_RESOURCE_TYPE)
          .message("You passed an illegal artifact type for versioning:'" + resourceType.getValue() + "'. The " +
              "allowed values are:" +
              CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .parameter("invalidResourceType", resourceType.getValue())
          .parameter("allowedResourceTypes", CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .build();
    }

    // Check update permission
    c.must(c.user()).have(updatePermission);

    var artifactContent = source != null ? source
        : ArtifactServerUtil.getSchemaArtifactWithEtagFromArtifactServer(resourceType, aid, c, cedarConfig, response);
    String getResponse = artifactContent.content();
    // The graph can refuse to record the publication, and putting its document back is then recorded here.
    AtomicReference<Undo> undo = new AtomicReference<>();
    if (getResponse != null) {
      JsonNode getJsonNode = null;
      try {
        getJsonNode = JsonMapper.STRICT_MAPPER.readTree(getResponse);
        if (getJsonNode != null) {

          ResourceVersion oldVersion = null;
          JsonNode oldVersionNode = getJsonNode.at(ModelPaths.PAV_VERSION);
          if (oldVersionNode != null) {
            oldVersion = ResourceVersion.forValueWithValidation(oldVersionNode.textValue());
          }

          if (newVersion.isBefore(oldVersion)) {
            return CedarResponse.badRequest()
                .errorKey(CedarErrorKey.INVALID_DATA)
                .message("The new version should be greater than or equal to the old version")
                .parameter("oldVersion", oldVersion.getValue())
                .parameter("newVersion", newVersion.getValue())
                .build();
          }

          // Check the document as well as the graph report: neither store may authorize a
          // transition when their publication states disagree.
          JsonNode oldStatusNode = getJsonNode.get(BIBO_STATUS);
          if (oldStatusNode != null && BiboStatus.PUBLISHED.getValue().equals(oldStatusNode.textValue())) {
            return CedarResponse.badRequest()
                .errorKey(CedarErrorKey.PUBLISH_ONLY_DRAFT)
                .message("Only a draft artifact can be published; this artifact is already published.")
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

          String content = JsonMapper.STRICT_MAPPER.writeValueAsString(getJsonNode);
          Response putResponse = ArtifactServerUtil.putSchemaArtifactToArtifactServer(resourceType, aid, c, content,
              cedarConfig, artifactContent.etag());
          int putStatus = putResponse.getStatus();
          if (putStatus != HttpStatus.SC_OK) {
            return putResponse;
          }

          if (putStatus == HttpStatus.SC_OK) {
            boolean graphUpdated = false;
            boolean supersededWrite = false;
            org.metadatacenter.cedar.resource.restore.ArtifactRestoreJob restoreJob = null;
            try {
              if (artifactRestoreCompletionService != null) {
                restoreJob = artifactRestoreCompletionService.prepare(aid, resourceType, getResponse,
                    putResponse.getHeaderString(HttpHeaders.ETAG), false);
              }
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
              FolderServerArtifact publishedResource;
              var stamped = stampedModificationProvenance(putResponse.getEntity());
              if (restoreJob == null) {
                publishedResource = folderSession.updateArtifactById(aid, resourceType, updates, null, null, stamped)
                    .resource();
              } else {
                var result = folderSession.updateArtifactById(aid, resourceType, updates, restoreJob.jobId(), null,
                    stamped);
                if (result.outcome() == ArtifactGraphUpdateResult.Outcome.SUPERSEDED) {
                  supersededWrite = true;
                  var acknowledged = (org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact)
                      FolderServerArtifact.fromFolderServerResourceCurrentUserReport(folderServerResourceOld);
                  acknowledged.setVersion(newVersion.getValue());
                  acknowledged.setPublicationStatus(BiboStatus.PUBLISHED.getValue());
                  return Response.ok().entity(acknowledged).build();
                }
                publishedResource = result.resource();
              }
              if (publishedResource == null) {
                throw new CedarProcessingException("The published artifact could not be updated in the graph");
              }
              graphUpdated = true;
              if (published != null) {
                published.set(new Publication(getResponse,
                    putResponse.getEntity() instanceof JsonNode stored ? stored.toString() : content,
                    putResponse.getHeaderString(HttpHeaders.ETAG)));
              }

              completeVersionProjections(c, relatedVersionIds(aid.getId()));
              FolderServerArtifact updatedResource = folderSession.findArtifactById(aid);

              return Response.ok().entity(updatedResource).build();
            } finally {
              if (!graphUpdated && !supersededWrite) {
                String publishedEtag = putResponse.getHeaderString(HttpHeaders.ETAG);
                undo.set(undoOf(restoreJob != null ? artifactRestoreCompletionService.restoreNow(restoreJob, c)
                    : restorePublishedArtifact(c, resourceType, aid, getResponse, publishedEtag), publishedEtag));
              }
              if (restoreJob != null && !graphUpdated) artifactRestoreCompletionService.forgetOutcome(restoreJob);
            }

          }
        }
      } catch (org.metadatacenter.server.VersionTransitionConflictException e) {
        return describeUndo(CedarResponse.conflict().errorKey(CedarErrorKey.VERSIONING_ONLY_ON_LATEST)
            .message(e.getMessage()).build(), undo.get());
      } catch (CedarException e) {
        // A refusal or an outage answers with its own status; this used to become a bare 500.
        throw describeUndo(e, undo.get());
      } catch (Exception e) {
        log.error("Error while publishing the artifact", e);
      }
    }
    return describeUndo(CedarResponse.internalServerError()
        .message("There was an error while publishing the artifact")
        .parameter("id", aid)
        .build(), undo.get());
  }

  /**
   * Restore a publish write only while its exact replacement ETag is still current.
   *
   * @return what became of the document, for the failure the publication is answered with
   */
  private RestoreOutcome restorePublishedArtifact(CedarRequestContext context, CedarResourceType resourceType,
                                                  CedarSchemaArtifactId artifactId, String preImage,
                                                  String publishedEtag) {
    if (publishedEtag == null || publishedEtag.isBlank()) {
      log.error("Failed publish left {} changed on the artifact server: conditional rollback is unavailable",
          artifactId);
      return new RestoreOutcome(RestoreOutcome.State.NOT_PUT_BACK, null);
    }
    try {
      Response rollback = ArtifactServerUtil.putSchemaArtifactToArtifactServer(resourceType, artifactId, context,
          preImage, cedarConfig, publishedEtag);
      if (rollback.getStatus() != HttpStatus.SC_OK) {
        log.error("Failed publish left {} changed on the artifact server: conditional rollback answered {}",
            artifactId, rollback.getStatus());
      }
      return RestoreOutcome.answered(rollback.getStatus(), rollback.getHeaderString(HttpHeaders.ETAG),
          rollback.getEntity() == null ? null : rollback.getEntity().toString(), false);
    } catch (Exception e) {
      log.error("Failed publish left {} changed on the artifact server: conditional rollback failed", artifactId, e);
      return new RestoreOutcome(RestoreOutcome.State.NOT_PUT_BACK, null);
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
          + "The sharing settings of the old artifact can be copied over to the new artifact. The caller may "
          + "choose the draft's identifier in draftId; an identifier already in use is refused with 412 before "
          + "anything is written, so repeating a request whose answer was lost cannot make a second draft.",
      tags = {"Validation", "Command", "Versioning"})
  @RequestBody(description = "Info about the creation process", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.CreateDraftArtifactRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "The draft artifact",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactRecord")),
          headers = @Header(name = "Location", description = "Where the draft artifact can be read.",
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
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    c.request().getRequestBody()
        .mustHaveOnly("@id", "newVersion", "folderId", "propagateSharing", "newFolderName", DRAFT_ID);
    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter newVersionParam = c.request().getRequestBody().get("newVersion");
    CedarParameter folderIdParam = c.request().getRequestBody().get("folderId");
    CedarParameter propagateSharingParam = c.request().getRequestBody().get("propagateSharing");
    CedarParameter newFolderNameParam = c.request().getRequestBody().get("newFolderName");
    CedarParameter draftIdParam = c.request().getRequestBody().get(DRAFT_ID);

    String id = linkedDataUtil.resolveResourceId(idParam.stringValue());
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);
    String folderId = linkedDataUtil.resolveResourceId(CedarResourceType.FOLDER, folderIdParam.stringValue());
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
    Optional<String> draftId = draftIdParam.isEmpty() ? Optional.empty() : Optional.of(draftIdParam.stringValue());

    return createDraftArtifact(c, aid, newVersion, fid, propagateSharing, newFolderNameString, draftId, null);
  }

  /**
   * Creates the draft that succeeds a published artifact.
   *
   * @param requestedDraftId the identity the caller chose for the draft, if it chose one
   * @param created          when given, set to the draft document and its ETag as soon as the artifact
   *                         server has stored it, for a caller that may have to remove it again
   */
  private Response createDraftArtifact(CedarRequestContext c, CedarUntypedSchemaArtifactId aid,
                                       ResourceVersion newVersion, CedarFolderId fid, boolean propagateSharing,
                                       String newFolderName, Optional<String> requestedDraftId,
                                       AtomicReference<ArtifactServerUtil.ArtifactContent> created)
      throws CedarException {

    userMustHaveCapabilityOnArtifact(c, aid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);

    FolderServerArtifactCurrentUserReport folderServerResourceOld = getArtifactReport(c, aid);

    // A draft at the identity the caller chose can be looked up when the answer is lost. A repeat of the
    // request finds the identity taken, and says so before anything else: the successor its first attempt
    // made also stops the source being drafted again, and that refusal would read as a failure.
    Optional<CedarArtifactId> draftId = Optional.empty();
    if (requestedDraftId.isPresent()) {
      draftId = Optional.of(suppliedArtifactIdentity(DRAFT_ID, requestedDraftId.get(), folderServerResourceOld.getType()));
      Response occupied = occupiedIdentityRefusal(c, folderServerResourceOld.getType(), draftId.get());
      if (occupied != null) {
        return occupied;
      }
    }

    CurrentUserResourcePermissions currentUserResourcePermissions = folderServerResourceOld.getCurrentUserPermissions();
    if (!currentUserResourcePermissions.isCanCreateDraft()) {
      return versioningRefusal(currentUserResourcePermissions.getCreateDraftErrorKey(), aid);
    }

    CedarResourceType artifactType = folderServerResourceOld.getType();

    CedarPermission updatePermission = CedarPermission.getUpdateForVersionedArtifactType(artifactType);
    if (updatePermission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_ARTIFACT_TYPE)
          .message("You passed an illegal artifact type for versioning:'" + artifactType.getValue() + "'. The " +
              "allowed values are:" +
              CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .parameter("invalidResourceType", artifactType.getValue())
          .parameter("allowedResourceTypes", CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .build();

    }

    // Check update permission
    c.must(c.user()).have(updatePermission);

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);

    userMustHaveCapabilityOnFolder(c, fid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.CREATE_IN_FOLDER);

    String getResponse = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(artifactType, aid, c,
        cedarConfig, response);
    // A draft stored and never registered is removed, and what became of it is recorded here.
    AtomicReference<Undo> undo = new AtomicReference<>();
    if (getResponse != null) {
      JsonNode getJsonNode = null;
      try {
        getJsonNode = JsonMapper.STRICT_MAPPER.readTree(getResponse);
        if (getJsonNode != null) {

          // The source document must agree with the graph's published state.
          JsonNode oldStatusNode = getJsonNode.get(BIBO_STATUS);
          if (oldStatusNode == null
              || !BiboStatus.PUBLISHED.getValue().equals(oldStatusNode.textValue())) {
            return CedarResponse.badRequest()
                .errorKey(CedarErrorKey.CREATE_DRAFT_ONLY_FROM_PUBLISHED)
                .message("A draft can only be created from a published artifact.")
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
                .message("The new version should be greater than the old version")
                .parameter("oldVersion", oldVersion.getValue())
                .parameter("newVersion", newVersion.getValue())
                .build();
          }

          ObjectNode newDocument = draftDocumentOf(getJsonNode, aid, newVersion, draftId);

          userMustHaveCapabilityOnFolder(c, fid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.CREATE_IN_FOLDER);

          String artifactServerPostRequestBodyAsString = JsonMapper.STRICT_MAPPER.writeValueAsString(newDocument);

          String cleanupJob = artifactCreateCleanupService.prepare(artifactType, "draft", draftId);
          boolean stored = false;
          String storedId = draftId.map(CedarArtifactId::getId).orElse(null);
          boolean registered = false;
          try {
            Response artifactServerPostResponse = executeResourceCreateOnArtifactServer(c, artifactType,
                draftId, artifactServerPostRequestBodyAsString, cleanupJob);

            int artifactServerPostStatus = artifactServerPostResponse.getStatus();
            InputStream is = (InputStream) artifactServerPostResponse.getEntity();
            String artifactServerPostBody = is == null ? null : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (artifactServerPostStatus == CedarResponseStatus.CREATED.getStatusCode()) {
              stored = true;
              JsonNode artifactServerPostResponseNode = JsonMapper.STRICT_MAPPER.readTree(artifactServerPostBody);
              if (created != null) {
                created.set(new ArtifactServerUtil.ArtifactContent(artifactServerPostResponseNode.toString(),
                    artifactServerPostResponse.getHeaderString(HttpHeaders.ETAG)));
              }
              JsonNode atId = artifactServerPostResponseNode.at(AT_ID);
              String newIdString = atId.asText();
              storedId = newIdString;
              CedarUntypedSchemaArtifactId newId = CedarUntypedSchemaArtifactId.build(newIdString);

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

              FolderServerArtifact newResource = folderSession.createDraftWithCleanup(brandNewResource, fid, propagateSharing,
                  cleanupJob, org.metadatacenter.server.ArtifactCreationProvenance.fromDocument(artifactServerPostResponseNode));
              if (newResource == null) {
                BackendCallResult backendCallResult = new BackendCallResult();
                backendCallResult.addError(CedarErrorType.SERVER_ERROR)
                    .errorKey(CedarErrorKey.DRAFT_NOT_CREATED)
                    .message("There was an error while creating the draft version of the artifact");
                throw new CedarBackendException(backendCallResult);
              }
              registered = true;

              FolderServerArtifact createdNewResource = folderSession.findArtifactById(newId);
              completeVersionProjections(c, relatedVersionIds(newId.getId()));

              if (artifactType == CedarResourceType.TEMPLATE && newFolderName != null && !newFolderName.isEmpty()) {
                createCopyOfInstancesWithNewTemplate(c, CedarTemplateId.build(aid.getId()),
                    CedarTemplateId.build(newId.getId()), newFolderName);
              }

              UriBuilder builder = uriInfo.getAbsolutePathBuilder();
              URI uri = builder.build();

              return Response.created(uri).entity(createdNewResource).build();
            } else {
              // The artifact server's refusal, with its own status: a validation failure is the
              // caller's to fix, and it was answered as an unexplained 500. Its body is passed on as
              // text. A refusal need not be JSON, and one that was not, such as a container's error
              // page, failed to parse and became a 500 as well.
              throw new ArtifactServerRefusal(artifactServerPostStatus, artifactServerPostBody);
            }
          } finally {
            artifactCreateCleanupService.cleanupNow(cleanupJob, c);
            if (stored && !registered) {
              undo.set(undoOfUnregisteredCreate(c, artifactType, storedId));
            }
          }
        }
      } catch (org.metadatacenter.server.VersionTransitionConflictException e) {
        return describeUndo(CedarResponse.conflict().errorKey(CedarErrorKey.VERSIONING_ONLY_ON_LATEST)
            .message(e.getMessage()).build(), undo.get());
      } catch (CedarException e) {
        throw describeUndo(e, undo.get());
      } catch (Exception e) {
        log.error("Error while creating the draft version of the artifact", e);
      }
    }
    return describeUndo(CedarResponse.internalServerError()
        .message("There was an error while creating the draft version of the artifact")
        .parameter("id", aid)
        .build(), undo.get());
  }

  @POST
  @Timed
  @Path("/check-update-template/{template_id}")
  @Operation(summary = "Check whether a template can be updated", description = "Check whether an existing template can be updated with the supplied template definition. The "
          + "destructive and non-destructive changes between the stored template and the supplied template are "
          + "computed, and the number of affected instances is reported.", tags = {"Command", "Versioning"})
  @RequestBody(description = "The template definition the stored template would be replaced by", required = true,
      content = @Content(schema = @Schema(implementation = SchemaArtifactDocument.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Whether the template can be updated, and what updating it would cost",
          content = @Content(schema = @Schema(ref = "#/components/schemas/TemplateUpdateCheck"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Bad request"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Forbidden"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error")
  })
  public Response checkUpdateTemplate(
      @Parameter(description = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(CedarResourceType.TEMPLATE, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    userMustHaveCapabilityOnArtifact(c, tid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);

    ObjectNode newTemplateObjectNode = submittedTemplate(c);
    Map<String, Object> resp = new HashMap<>();

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    long instanceCount = folderSession.getNumberOfInstances(CedarTemplateId.build(id));
    resp.put("numberOfInstances", instanceCount);

    if (instanceCount == 0) {
      resp.put("canBeUpdated", true);
      return Response.ok().entity(resp).build();
    }

    String getResponse = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(CedarResourceType.TEMPLATE, tid, c,
        cedarConfig, response);
    if (getResponse == null || getResponse.isBlank()) {
      throw new CedarObjectNotFoundException(tid.getId());
    }

    try {
      JsonNode oldTemplateJsonNode = JsonMapper.STRICT_MAPPER.readTree(getResponse);
      if (!(oldTemplateJsonNode instanceof ObjectNode oldTemplateObjectNode)) {
        throw new IllegalArgumentException("The stored template is not a JSON object");
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
    } catch (Exception e) {
      log.error("Error while checking template {} for update", tid.getId(), e);
      throw new CedarProcessingException("There was an error while checking the template for update", e);
    }
  }

  @POST
  @Timed
  @Path("/publish-create-draft-template/{template_id}")
  @Operation(summary = "Publish a template and create a new draft", description = "Publish the given template, then create a new draft version from it and apply the supplied template "
          + "definition. Instances of the source template can be copied into a new folder. The command succeeds "
          + "or fails as a whole. What a later step would refuse is refused before the template is published: "
          + "a parent folder the caller cannot create in, a draft identifier already in use and a definition "
          + "the draft would not accept. If a step fails after publication, the command removes the draft it "
          + "created and returns the template to the draft it was, then answers with the failing step's status, "
          + "or 503 when that step received no answer. Returning the template to a draft gives it a new ETag. "
          + "A retry sends that ETag in If-Match and publishes the template at the same version.",
      tags = {"Command", "Versioning"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "The template definition to apply to the new draft", required = true,
      content = @Content(schema = @Schema(implementation = SchemaArtifactDocument.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "The new draft template",
          content = @Content(schema = @Schema(ref = "#/components/schemas/ArtifactRecord"))),
      @ApiResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "The definition or the draft identifier is refused, or the template cannot be published, "
              + "for example because it is already published; nothing was published"),
      @ApiResponse(responseCode = "401", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Unauthorized"),
      @ApiResponse(responseCode = "403", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "Only the template's owner may version it, whatever role another user holds "
              + "(versioningOnlyByOwner), and the caller must be able to create in its folder; nothing was published"),
      @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Not found"),
      @ApiResponse(responseCode = "412", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "The source template changed, or the draft identifier already names an artifact; "
              + "nothing was published and no draft was created"),
      @ApiResponse(responseCode = "428", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "The source template ETag is required in If-Match"),
      @ApiResponse(responseCode = "500", content = @Content(schema = @Schema(implementation = CedarError.class)), description = "Internal server error"),
      @ApiResponse(responseCode = "503", content = @Content(schema = @Schema(implementation = CedarError.class)),
          description = "A step received no answer from the artifact server; whatever the command had done was undone")
  })
  public Response publishCreateDraftTemplate(
      @Parameter(description = "Template identifier.", required = true) @PathParam(PP_TEMPLATE_ID) String id,
      @Parameter(description = "Name of the folder to copy the template instances into.")
      @QueryParam(QP_FOLDER_NAME) Optional<String> folderName,
      @Parameter(description = "The full identifier the new draft is to have, chosen by the caller so that it "
          + "can read the outcome by it if the answer is lost. Example: https://repo.metadatacenter.org/templates/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e. Without it, the server chooses one.")
      @QueryParam(QP_DRAFT_ID) Optional<String> draftIdParam) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    id = linkedDataUtil.resolveResourceId(CedarResourceType.TEMPLATE, id);

    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    userMustHaveCapabilityOnArtifact(c, tid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.READ_RESOURCE);

    String ifMatch = c.getIfMatchHeader();
    if (RevisionPreconditionParser.isAbsent(ifMatch)) {
      return CedarResponse.preconditionRequired()
          .id(tid.getId()).errorKey(CedarErrorKey.ARTIFACT_PRECONDITION_REQUIRED)
          .message("Creating a draft from an edited template requires its original ETag in If-Match")
          .build();
    }
    ObjectNode newTemplateJsonNode = submittedTemplate(c);
    // The draft's identity is settled before anything is read, so that the definition can be judged at
    // the identity it will have and whatever is created there can be found again if a step fails.
    CedarArtifactId draftId = draftIdParam.isPresent() && !draftIdParam.get().isBlank()
        ? suppliedArtifactIdentity(QP_DRAFT_ID, draftIdParam.get(), CedarResourceType.TEMPLATE)
        : CedarArtifactId.build(linkedDataUtil.buildNewLinkedDataId(CedarResourceType.TEMPLATE),
            CedarResourceType.TEMPLATE);
    var source = ArtifactServerUtil.getSchemaArtifactWithEtagFromArtifactServer(
        CedarResourceType.TEMPLATE, tid, c, cedarConfig, response);
    if (source.etag() == null || source.etag().isBlank()) {
      throw new CedarProcessingException("The source template has no revision validator");
    }
    var expected = RevisionPreconditionParser.parse(ifMatch);
    var current = RevisionPreconditionParser.parse(source.etag());
    if (current.revisions().stream().noneMatch(expected::matches)) {
      return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
          .id(tid.getId()).errorKey(CedarErrorKey.ARTIFACT_HAS_MOVED_ON)
          .message("The template changed since it was opened")
          .build();
    }
    String getResponse = source.content();
    AtomicReference<Undo> undo = new AtomicReference<>();
    if (getResponse != null) {
      JsonNode oldTemplateJsonNode;
      try {
        oldTemplateJsonNode = JsonMapper.STRICT_MAPPER.readTree(getResponse);
        if (oldTemplateJsonNode != null) {
          JsonArtifactReader reader = new JsonArtifactReader();
          TemplateSchemaArtifact oldModelArtifact = reader.readTemplateSchemaArtifact((ObjectNode) oldTemplateJsonNode);

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
          CedarUntypedSchemaArtifactId sourceId = CedarUntypedSchemaArtifactId.build(tid.getId());

          // Refuse now what a later step would refuse, while nothing has changed and the ETag the author
          // opened still holds, so that a corrected request can succeed.
          userMustHaveCapabilityOnFolder(c, fid, org.metadatacenter.server.security.model.permission.resource.ResourceCapability.CREATE_IN_FOLDER);
          Response occupied = occupiedIdentityRefusal(c, CedarResourceType.TEMPLATE, draftId);
          if (occupied != null) {
            return occupied;
          }
          Response refusedDefinition = refusalOfTheDefinition(c, draftId,
              draftDocumentOf(oldTemplateJsonNode, sourceId, newVersion, Optional.of(draftId)), newTemplateJsonNode);
          if (refusedDefinition != null) {
            return refusedDefinition;
          }

          return describeUndo(publishThenDraft(c, tid, sourceId, oldVersion, newVersion, fid, source, draftId,
              newTemplateJsonNode, folderName, undo), undo.get());
        }
      } catch (CedarException e) {
        throw describeUndo(e, undo.get());
      } catch (Exception e) {
        log.error("Error while publishing template {} and creating its draft", tid, e);
        return describeUndo(CedarResponse.internalServerError()
            .message("There was an error while publishing the template and creating its draft")
            .parameter("id", tid)
            .build(), undo.get());
      }
    }
    return CedarResponse.internalServerError()
        .message("There was an error while publishing the template and creating its draft")
        .parameter("id", tid)
        .build();
  }

  /**
   * Publishes the template, creates its draft and gives the draft the submitted definition, undoing what
   * committed when a later step fails. {@code undo} receives what the undoing left the template as, for
   * the failure the command is answered with.
   */
  private Response publishThenDraft(CedarRequestContext c, CedarTemplateId tid, CedarUntypedSchemaArtifactId sourceId,
                                    ResourceVersion oldVersion, ResourceVersion newVersion, CedarFolderId fid,
                                    ArtifactServerUtil.ArtifactContent source, CedarArtifactId draftId,
                                    ObjectNode newTemplateJsonNode, Optional<String> folderName,
                                    AtomicReference<Undo> undo) throws Exception {
    AtomicReference<Publication> publication = new AtomicReference<>();
    AtomicReference<ArtifactServerUtil.ArtifactContent> createdDraft = new AtomicReference<>();
    ArtifactServerUtil.ArtifactContent storedDraft = null;
    boolean complete = false;
    try {
      Response publishResponse = publishArtifact(c, sourceId, oldVersion, source, publication);
      if (publishResponse.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
        return publishResponse;
      }

      Response createResponse = createDraftArtifact(c, sourceId, newVersion, fid, true, null,
          Optional.of(draftId.getId()), createdDraft);
      if (createResponse.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
        return createResponse;
      }

      if (!(createResponse.getEntity() instanceof FolderServerTemplate entity)) {
        log.error("Draft creation for template {} returned a successful response without a template entity", tid);
        return CedarResponse.internalServerError()
            .message("Draft creation returned an invalid response")
            .parameter("id", tid)
            .build();
      }
      CedarTemplateId newTemplateId = CedarTemplateId.build(entity.getId());

      storedDraft = ArtifactServerUtil.getSchemaArtifactWithEtagFromArtifactServer(
          CedarResourceType.TEMPLATE, newTemplateId, c, cedarConfig, null);
      applyDefinitionToDraft(newTemplateJsonNode,
          JsonMapper.STRICT_MAPPER.readTree(storedDraft.content()));
      Response updateResponse = executeResourceUpdateOnArtifactServerAndGraphDb(c, CedarResourceType.TEMPLATE, newTemplateId,
          JsonMapper.STRICT_MAPPER.writeValueAsString(newTemplateJsonNode), false, storedDraft.etag());
      if (updateResponse.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
        return updateResponse;
      }
      complete = true;
      // The worker must see the final definition, including renamed fields, before copying instances.
      if (folderName.isPresent() && !folderName.get().isBlank()) {
        createCopyOfInstancesWithNewTemplate(c, tid, newTemplateId, folderName.get());
      }
      return updateResponse;
    } finally {
      if (!complete) {
        undo.set(undoPublicationAndDraft(c, tid, oldVersion, publication.get(), draftId, createdDraft.get(),
            storedDraft));
      }
    }
  }

  /** What a publication changed in the artifact store, so a command that fails later can put it back. */
  private record Publication(String preImage, String publishedDocument, String publishedEtag) {
  }

  /**
   * The document a draft of a published artifact is created from: the published document at the next
   * version, as a draft naming it as predecessor, at the identity the caller chose if it chose one.
   */
  private static ObjectNode draftDocumentOf(JsonNode source, CedarSchemaArtifactId sourceId,
                                            ResourceVersion newVersion, Optional<CedarArtifactId> draftId) {
    ObjectNode newDocument = (ObjectNode) source.deepCopy();
    newDocument.put(ModelNodeNames.PAV_VERSION, newVersion.getValue());
    newDocument.put(ModelNodeNames.BIBO_STATUS, BiboStatus.DRAFT.getValue());
    newDocument.put(ModelNodeNames.PAV_PREVIOUS_VERSION, sourceId.getId());
    // A draft is the same intellectual artifact at a new version, so it keeps schema:identifier
    // where a copy drops it, but it is a new document to the artifact server all the same.
    ArtifactCopyOperations.prepareDerivedBody(newDocument);
    draftId.ifPresent(identity -> newDocument.put(JSON_LD_ID, identity.getId()));
    return newDocument;
  }

  /**
   * Asks the artifact server how the PUT that gives the draft its definition will judge it, before
   * anything has been published or created. Null when the PUT would accept it; otherwise the PUT's own
   * refusal, relayed as the PUT's would be.
   *
   * <p>The draft does not exist yet, so the artifact server is given the document the draft will be
   * created from and the definition as it will be sent, and runs the create's preparation and then the
   * PUT's on them, storing nothing. A cheaper check, such as the validate command, judges the bare
   * definition and so disagrees with the PUT about a field the PUT would give an identifier.
   */
  private Response refusalOfTheDefinition(CedarRequestContext c, CedarArtifactId draftId, ObjectNode draftDocument,
                                          ObjectNode submitted) throws CedarException {
    ObjectNode replacement = submitted.deepCopy();
    applyDefinitionToDraft(replacement, draftDocument);
    ObjectNode question = JsonMapper.STRICT_MAPPER.createObjectNode();
    question.set("created", draftDocument);
    question.set("replacement", replacement);
    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.TEMPLATE, draftId)
        + "/replacement-judgement";
    try (ClassicHttpResponse judged = new ArtifactServiceClient(cedarConfig).post(url, c, question.toString())) {
      int status = judged.getCode();
      String content = judged.getEntity() == null ? "" : EntityUtils.toString(judged.getEntity(), StandardCharsets.UTF_8);
      if (status >= 200 && status < 300) {
        return null;
      }
      ProxyUtil.proxyResponseHeaders(judged, response);
      return CedarResponse.status(status).entity(content).build();
    } catch (CedarException e) {
      throw e;
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  /**
   * Undoes what a failed publish-create-draft-template committed: the draft it created, then the
   * publication. It is best effort and never throws, so the caller still answers with the failing step's
   * own status. Whatever it cannot put back is logged with the identifiers needed to find it.
   *
   * @return what the undoing left the template as, for the failure the command is answered with, or null
   *     when the command had committed nothing
   */
  private Undo undoPublicationAndDraft(CedarRequestContext c, CedarTemplateId sourceId, ResourceVersion version,
                                       Publication publication, CedarArtifactId draftId,
                                       ArtifactServerUtil.ArtifactContent createdDraft,
                                       ArtifactServerUtil.ArtifactContent storedDraft) {
    try {
      if (!removeDraftOfFailedCommand(c, draftId, createdDraft, storedDraft)) {
        log.error("Publishing {} and drafting {} failed, and the draft could not be removed; the template "
            + "stays published", sourceId, draftId);
        return new Undo("The draft created from the template could not be removed, so the template stays "
            + "published.", publication == null ? null : publication.publishedEtag());
      }
      if (publication != null) {
        return returnSourceToDraft(c, sourceId, version, publication);
      }
      return createdDraft == null ? null : new Undo("No draft of the template remains.", null);
    } catch (Exception failure) {
      log.error("Undoing the failed publication of {} and its draft {} did not finish", sourceId, draftId, failure);
      return new Undo("Undoing the template's publication did not finish, so whether the template is still "
          + "published is not known. Read it before trying again.", null);
    }
  }

  /**
   * Removes the draft a failed command registered, from both stores, and reports whether the graph is
   * free of it. A document the graph never registered belongs to the create's cleanup job, which removes
   * it. A registered one is removed only while it still holds what this command stored in it, by a
   * deletion conditional on the revision that was read with that content, so nothing anyone else wrote
   * is lost.
   */
  private boolean removeDraftOfFailedCommand(CedarRequestContext c, CedarArtifactId draftId,
                                             ArtifactServerUtil.ArtifactContent createdDraft,
                                             ArtifactServerUtil.ArtifactContent storedDraft) throws CedarException {
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    if (folderSession.findArtifactById(draftId) == null) {
      return true;
    }
    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(CedarResourceType.TEMPLATE, draftId);
    String condition;
    try (ClassicHttpResponse current = new ArtifactServiceClient(cedarConfig).get(url, c)) {
      int status = current.getCode();
      String content = current.getEntity() == null ? null : EntityUtils.toString(current.getEntity(), StandardCharsets.UTF_8);
      if (status == HttpStatus.SC_NOT_FOUND) {
        // The document is gone already; only the graph still names the draft.
        condition = createdDraft != null ? createdDraft.etag() : storedDraft != null ? storedDraft.etag() : null;
      } else if (status == HttpStatus.SC_OK && (sameDraft(content, createdDraft) || sameDraft(content, storedDraft))) {
        condition = AbstractResourceServerResource.headerValue(current, HttpHeaders.ETAG);
      } else {
        return false;
      }
    } catch (Exception e) {
      log.warn("The draft {} of a failed command could not be read before its removal", draftId, e);
      return false;
    }
    if (RevisionPreconditionParser.isAbsent(condition)) {
      return false;
    }
    Response deleted = executeArtifactDelete(c, CedarResourceType.TEMPLATE, draftId, condition);
    if (deleted.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
      log.warn("The draft {} of a failed command was not removed: its deletion answered {}", draftId,
          deleted.getStatus());
    }
    return folderSession.findArtifactById(draftId) == null;
  }

  /**
   * Whether a stored document is the draft this command created. When an update of the draft reaches the
   * artifact store and its graph write fails, the draft is put back by an ordinary write. That write
   * stamps the draft's modification time and author. It also stamps each child the update had changed,
   * and gives a full creation record to each child the update had removed. The comparison leaves out
   * those provenance values and keeps everything an author could have written.
   */
  private static boolean sameDraft(String content, ArtifactServerUtil.ArtifactContent known) {
    if (content == null || known == null || known.content() == null) {
      return false;
    }
    try {
      JsonNode stored = JsonMapper.STRICT_MAPPER.readTree(content);
      JsonNode created = JsonMapper.STRICT_MAPPER.readTree(known.content());
      if (!(stored instanceof ObjectNode) || !(created instanceof ObjectNode)) {
        return false;
      }
      withoutWriteStamps(stored, true);
      withoutWriteStamps(created, true);
      return stored.equals(created);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Removes the provenance values a write stamps. The draft keeps its creation record through any write,
   * so only its two modification stamps go. A child can be stamped with all four. The schema also
   * declares these four properties, as objects, and those declarations stay.
   */
  private static void withoutWriteStamps(JsonNode node, boolean draft) {
    if (node instanceof ObjectNode object) {
      List<String> stamped = draft ? List.of(PAV_LAST_UPDATED_ON, OSLC_MODIFIED_BY)
          : List.of(PAV_CREATED_ON, PAV_CREATED_BY, PAV_LAST_UPDATED_ON, OSLC_MODIFIED_BY);
      for (String key : stamped) {
        JsonNode value = object.get(key);
        if (value != null && !value.isContainerNode()) {
          object.remove(key);
        }
      }
      object.elements().forEachRemaining(child -> withoutWriteStamps(child, false));
    } else if (node != null && node.isArray()) {
      node.elements().forEachRemaining(child -> withoutWriteStamps(child, false));
    }
  }

  /**
   * Returns a template a failed command published to the draft it was. The document goes back first,
   * conditional on the published ETag, so a write since the publication stands. The graph follows,
   * fenced by a durable restore that puts the published document back if the graph does not commit, so
   * the two stores end describing the same state either way.
   */
  private Undo returnSourceToDraft(CedarRequestContext c, CedarTemplateId sourceId, ResourceVersion version,
                                   Publication publication) throws CedarException {
    Response restored = ArtifactServerUtil.putSchemaArtifactToArtifactServer(CedarResourceType.TEMPLATE, sourceId, c,
        publication.preImage(), cedarConfig, publication.publishedEtag());
    if (restored.getStatus() != HttpStatus.SC_OK) {
      log.error("The failed publication of {} could not be undone: putting its draft document back answered {}",
          sourceId, restored.getStatus());
      // A refused put-back leaves the published document, except after a 412 that names no instances,
      // which means another write replaced it and its ETag is not known here.
      boolean replaced = restored.getStatus() == HttpStatus.SC_PRECONDITION_FAILED
          && !ArtifactServerRefusal.refusesForInstances(restored.getStatus(),
              restored.getEntity() == null ? null : restored.getEntity().toString());
      return new Undo("No draft of the template remains, but the template could not be returned to the draft it "
          + "was, so it stays published.", replaced ? null : publication.publishedEtag());
    }
    String restoredEtag = restored.getHeaderString(HttpHeaders.ETAG);
    ArtifactRestoreJob restoreJob = artifactRestoreCompletionService == null ? null
        : artifactRestoreCompletionService.prepare(sourceId, CedarResourceType.TEMPLATE,
            publication.publishedDocument(), restoredEtag, false);
    boolean returned = false;
    RestoreOutcome republished = null;
    try {
      returned = artifactRestoreCompletionService != null && artifactRestoreCompletionService.returnToDraft(
          restoreJob, sourceId, version.getValue(), version.getValue(), c.getCedarUser().getId());
    } finally {
      if (!returned) {
        log.error("The failed publication of {} could not be undone in the graph; its published document is "
            + "being put back", sourceId);
        if (restoreJob != null) {
          republished = artifactRestoreCompletionService.restoreNow(restoreJob, c);
        } else {
          republished = restorePublishedArtifact(c, CedarResourceType.TEMPLATE, sourceId,
              publication.publishedDocument(), restoredEtag);
        }
      }
      if (restoreJob != null && !returned) {
        artifactRestoreCompletionService.forgetOutcome(restoreJob);
      }
      completeVersionProjections(c, relatedVersionIds(sourceId.getId()));
    }
    if (returned) {
      return new Undo("The template's publication was undone, and no draft of it remains.", restoredEtag);
    }
    String kept = "No draft of the template remains. The graph kept the template published";
    return switch (republished.state()) {
      case PUT_BACK, SETTLED -> new Undo(kept + ", so its published document was put back and it stays published.",
          republished.etag());
      case OVERTAKEN -> new Undo(kept + ", and a later write has replaced its document since.", null);
      case RECORDED -> new Undo(kept + ". Putting its published document back is recorded and will be retried.",
          null);
      case PARKED -> new Undo(kept + ", and the artifact server refused to put its published document back. The "
          + "refusal is recorded for a person to resolve.", restoredEtag);
      case NOT_PUT_BACK -> new Undo(kept + ", and its published document could not be put back, so its document "
          + "is the draft while the graph says it is published.", restoredEtag);
    };
  }

  /**
   * The refusal of a publication or a draft that the caller's permission report does not allow, for the
   * reason the report gives. Only an artifact's owner may version it, so a caller who is not the owner
   * is forbidden whatever role they hold. Every other reason concerns the artifact rather than the
   * caller, such as publishing an artifact that is already published, and is a bad request.
   */
  private static Response versioningRefusal(CedarErrorKey reason, CedarUntypedSchemaArtifactId aid) {
    CedarResponse.CedarResponseBuilder refusal = reason == CedarErrorKey.VERSIONING_ONLY_BY_OWNER
        ? CedarResponse.forbidden() : CedarResponse.badRequest();
    return refusal.errorKey(reason).parameter("id", aid.getId()).build();
  }

  /**
   * The template definition a versioning command was sent. A body that is not a template the artifact
   * library can read is the client's mistake, so it is refused before anything is read or changed: it
   * was a 500, after the command had already looked up the stored template, and check-update said such
   * a body could replace a template that had no instances, without reading it at all.
   */
  private static ObjectNode submittedTemplate(CedarRequestContext c) throws CedarException {
    if (!(c.request().getRequestBody().asJson() instanceof ObjectNode submitted)) {
      throw new CedarBadRequestException(new CedarErrorPack()
          .message("The request body must be the template definition, as a JSON object"));
    }
    try {
      new JsonArtifactReader().readTemplateSchemaArtifact(submitted.deepCopy());
    } catch (RuntimeException e) {
      throw new CedarBadRequestException(new CedarErrorPack()
          .message("The request body is not a template the artifact library can read: " + e.getMessage()));
    }
    return submitted;
  }

  /**
   * Applies the submitted template definition to the draft that has just been created, without undoing
   * what creating it established.
   *
   * <p>The submitted document describes a template, not this draft. Its identifier, version, status and
   * predecessor are whatever the client last read, or absent altogether, so all four are read back from
   * the draft that was stored. The predecessor matters most. A successor that loses it loses the version
   * it was drawn from, and a client sending a document it had drafted earlier would name that earlier
   * draft's predecessor instead. The DOI belongs to the published artifact rather than to its successor,
   * and creating the draft dropped it.
   */
  private static void applyDefinitionToDraft(ObjectNode submitted, JsonNode storedDraft) {
    for (String key : List.of(JSON_LD_ID, PAV_VERSION, BIBO_STATUS, PAV_PREVIOUS_VERSION)) {
      JsonNode value = storedDraft.get(key);
      if (value == null) {
        submitted.remove(key);
      } else {
        submitted.set(key, value);
      }
    }
    ModelUtil.removeDOIFromResource(submitted);
  }
}
