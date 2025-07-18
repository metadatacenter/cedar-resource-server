package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpStatus;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
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

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.model.ModelNodeNames.*;
import static org.metadatacenter.model.ModelPaths.AT_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
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

    String getResponse = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(resourceType, aid, c,
        microserviceUrlUtil, response);
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

          //publish on the artifact server
          ((ObjectNode) getJsonNode).put(PAV_VERSION, newVersion.getValue());
          ((ObjectNode) getJsonNode).put(BIBO_STATUS, BiboStatus.PUBLISHED.getValue());
          String content = JsonMapper.MAPPER.writeValueAsString(getJsonNode);
          Response putResponse = ArtifactServerUtil.putSchemaArtifactToArtifactServer(resourceType, aid, c, content,
              microserviceUrlUtil);
          int putStatus = putResponse.getStatus();

          if (putStatus == HttpStatus.SC_OK) {
            // publish in Neo4j server
            FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

            if (folderServerResourceOld instanceof FolderServerSchemaArtifactCurrentUserReport) {
              FolderServerSchemaArtifactCurrentUserReport schemaArtifact =
                  (FolderServerSchemaArtifactCurrentUserReport) folderServerResourceOld;
              schemaArtifact.setLatestPublishedVersion(true);
            }

            Map<NodeProperty, String> updates = new HashMap<>();
            updates.put(NodeProperty.VERSION, newVersion.getValue());
            updates.put(NodeProperty.PUBLICATION_STATUS, BiboStatus.PUBLISHED.getValue());
            folderSession.updateArtifactById(aid, resourceType, updates);

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

          }
        }
      } catch (Exception e) {
        log.error("Error while publishing artifact: " + e.getMessage());
      }
    }
    return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
  }

  private void createCopyOfInstancesWithNewTemplate(CedarRequestContext context, CedarTemplateId oldId,
                                                    CedarTemplateId newId) {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    long instanceCount = folderSession.getNumberOfInstances(CedarTemplateId.build(oldId.getId()));
    if (instanceCount > 0) {
      cloneInstanceEnqueueService.cloneInstances(oldId, newId);
    }
  }

  @POST
  @Timed
  @Path("/create-draft-artifact")
  public Response createDraftArtifact() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter idParam = c.request().getRequestBody().get("@id");
    CedarParameter newVersionParam = c.request().getRequestBody().get("newVersion");
    CedarParameter folderIdParam = c.request().getRequestBody().get("folderId");
    CedarParameter propagateSharingParam = c.request().getRequestBody().get("propagateSharing");

    String id = idParam.stringValue();
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);
    String folderId = folderIdParam.stringValue();
    CedarFolderId fid = CedarFolderId.build(folderId);
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

    boolean propagateSharing = Boolean.parseBoolean(propagateSharingString);

    return createDraftArtifact(c, aid, newVersion, fid, propagateSharing);
  }

  private Response createDraftArtifact(CedarRequestContext c, CedarUntypedSchemaArtifactId aid,
                                       ResourceVersion newVersion, CedarFolderId fid, boolean propagateSharing) throws CedarException {

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

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

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
          newDocument.remove(ModelNodeNames.JSON_LD_ID);

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

            folderSession.unsetLatestVersion(aid);
            FolderServerArtifact newResource = folderSession.createResourceAsChildOfId(brandNewResource, fid);
            if (newResource == null) {
              BackendCallResult backendCallResult = new BackendCallResult();
              backendCallResult.addError(CedarErrorType.SERVER_ERROR)
                  .errorKey(CedarErrorKey.DRAFT_NOT_CREATED)
                  .message("There was an error while creating the draft version of the artifact");
              throw new CedarBackendException(backendCallResult);
            } else {
              if (propagateSharing) {
                ResourcePermissionServiceSession permissionSession =
                    CedarDataServices.getResourcePermissionServiceSession(c);
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
            }
            FolderServerArtifact createdNewResource = folderSession.findArtifactById(newId);
            createIndexArtifact(createdNewResource, c);
            FolderServerArtifact updatedSourceResource = folderSession.findArtifactById(aid);
            updateIndexResource(updatedSourceResource, c);

            if (artifactType == CedarResourceType.TEMPLATE) {
              createCopyOfInstancesWithNewTemplate(c, CedarTemplateId.build(aid.getId()),
                  CedarTemplateId.build(newId.getId()));
            }

            UriBuilder builder = uriInfo.getAbsolutePathBuilder();
            URI uri = builder.build();

            return Response.created(uri).entity(createdNewResource).build();

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
        e.printStackTrace();
      }
    }
    return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
  }

  @POST
  @Timed
  @Path("/check-update-template/{id}")
  public Response checkUpdateTemplate(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.TEMPLATE_READ);
    CedarTemplateId tid = CedarTemplateId.build(id);

    userMustHaveReadAccessToArtifact(c, tid);

    Map<String, Object> resp = new HashMap<>();

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    long instanceCount = folderSession.getNumberOfInstances(CedarTemplateId.build(id));

    if (instanceCount == 0) {
      resp.put("canBeUpdated", true);
      return Response.ok().entity(resp).build();
    }

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

          DeltaFinder finder = new DeltaFinder();
          Delta delta = finder.findDelta(oldModelArtifact, newModelArtifact);

          List<Change> destructive = delta.getDestructiveChanges();
          List<Change> nonDestructive = delta.getNonDestructiveChanges();
          resp.put("destructiveChanges", destructive.size());
          resp.put("nonDestructiveChanges", nonDestructive.size());
          resp.put("canBeUpdated", destructive.isEmpty());
          return Response.ok().entity(resp).build();
        }
      } catch (Exception e) {
        throw new CedarObjectNotFoundException(tid.getId());
      }
    }
    return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
  }

  @POST
  @Timed
  @Path("/publish-create-draft-template/{id}")
  public Response publishCreateDraftTemplate(@PathParam(PP_ID) String id) throws CedarException {
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

          FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
          ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

          FileSystemResource artifact = folderSession.findArtifactById(tid);
          List<FolderServerResourceExtract> pathInfo = PathInfoBuilder.getResourcePathExtract(c, folderSession,
              permissionSession, artifact);
          FolderServerResourceExtract parentFolderExtract = pathInfo.get(pathInfo.size() - 2);

          CedarFolderId fid = CedarFolderId.build(parentFolderExtract.getId());

          publishArtifact(c, CedarUntypedSchemaArtifactId.build(tid.getId()), oldVersion);

          Response createResponse = createDraftArtifact(c, CedarUntypedSchemaArtifactId.build(tid.getId()),
              newVersion, fid, true);

          FolderServerTemplate entity = (FolderServerTemplate) createResponse.getEntity();
          String newTemplateIdString = entity.getId();
          CedarTemplateId newTemplateId = CedarTemplateId.build(newTemplateIdString);

          ((ObjectNode) newTemplateJsonNode).put(JSON_LD_ID, newTemplateIdString);
          ((ObjectNode) newTemplateJsonNode).put(PAV_VERSION, newVersion.getValue());
          return executeResourceUpdateOnArtifactServerAndGraphDb(c, CedarResourceType.TEMPLATE, newTemplateId,
              JsonMapper.MAPPER.writeValueAsString(newTemplateJsonNode));
        }
      } catch (Exception e) {
        throw new CedarObjectNotFoundException(tid.getId());
      }
    }
    return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
  }
}
