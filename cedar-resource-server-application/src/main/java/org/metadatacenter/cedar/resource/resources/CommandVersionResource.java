package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpStatus;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorType;
import org.metadatacenter.exception.CedarBackendException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarSchemaArtifactId;
import org.metadatacenter.id.CedarUntypedSchemaArtifactId;
import org.metadatacenter.model.*;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerArtifactCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerSchemaArtifactCurrentUserReport;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.auth.CedarNodePermissions;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.auth.CurrentUserResourcePermissions;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionUser;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
import org.metadatacenter.util.CedarResourceTypeUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.model.ModelNodeNames.BIBO_STATUS;
import static org.metadatacenter.model.ModelNodeNames.PAV_VERSION;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
public class CommandVersionResource extends AbstractResourceServerResource {

  public CommandVersionResource(CedarConfig cedarConfig) {
    super(cedarConfig);
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

    userMustHaveReadAccessToArtifact(c, aid);

    FolderServerArtifactCurrentUserReport folderServerResourceOld = getArtifactReport(c, aid);

    CurrentUserResourcePermissions currentUserResourcePermissions = folderServerResourceOld.getCurrentUserPermissions();
    if (!currentUserResourcePermissions.isCanPublish()) {
      return CedarResponse.badRequest()
          .errorKey(currentUserResourcePermissions.getPublishErrorKey())
          .parameter("id", id)
          .build();
    }

    CedarResourceType resourceType = folderServerResourceOld.getType();

    CedarPermission updatePermission = CedarPermission.getUpdateForVersionedArtifactType(resourceType);
    if (updatePermission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_RESOURCE_TYPE)
          .errorMessage("You passed an illegal artifact type for versioning:'" + resourceType.getValue() + "'. The allowed values are:" +
              CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .parameter("invalidResourceType", resourceType.getValue())
          .parameter("allowedResourceTypes", CedarResourceTypeUtil.getValidResourceTypeValuesForVersioning())
          .build();
    }

    // Check update permission
    c.must(c.user()).have(updatePermission);

    String getResponse = getSchemaArtifactFromArtifactServer(resourceType, aid, c);
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

          //publish on artifact server
          ((ObjectNode) getJsonNode).put(PAV_VERSION, newVersion.getValue());
          ((ObjectNode) getJsonNode).put(BIBO_STATUS, BiboStatus.PUBLISHED.getValue());
          String content = JsonMapper.MAPPER.writeValueAsString(getJsonNode);
          Response putResponse = putSchemaArtifactToArtifactServer(resourceType, aid, c, content);
          int putStatus = putResponse.getStatus();

          if (putStatus == HttpStatus.SC_OK) {
            // publish in Neo4j server
            FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

            if (folderServerResourceOld instanceof FolderServerSchemaArtifactCurrentUserReport) {
              FolderServerSchemaArtifactCurrentUserReport schemaArtifact = (FolderServerSchemaArtifactCurrentUserReport) folderServerResourceOld;
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
              if (folderServerResourceOld instanceof FolderServerSchemaArtifactCurrentUserReport) {
                FolderServerSchemaArtifactCurrentUserReport schemaArtifact = (FolderServerSchemaArtifactCurrentUserReport) folderServerResourceOld;
                if (schemaArtifact.getPreviousVersion() != null) {
                  folderSession.unsetLatestPublishedVersion(schemaArtifact.getPreviousVersion());
                }
              }
            }

            FolderServerArtifact updatedResource = folderSession.findArtifactById(aid);
            updateIndexResource(updatedResource, c);

            // read the updated previous version
            if (folderServerResourceOld instanceof FolderServerSchemaArtifactCurrentUserReport) {
              FolderServerSchemaArtifactCurrentUserReport schemaArtifact = (FolderServerSchemaArtifactCurrentUserReport) folderServerResourceOld;
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
        e.printStackTrace();
      }
    }
    return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
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

    userMustHaveReadAccessToArtifact(c, aid);

    FolderServerArtifactCurrentUserReport folderServerResourceOld = getArtifactReport(c, aid);

    CurrentUserResourcePermissions currentUserResourcePermissions = folderServerResourceOld.getCurrentUserPermissions();
    if (!currentUserResourcePermissions.isCanCreateDraft()) {
      return CedarResponse.badRequest()
          .errorKey(currentUserResourcePermissions.getCreateDraftErrorKey())
          .parameter("id", id)
          .build();
    }

    CedarResourceType artifactType = folderServerResourceOld.getType();

    boolean propagateSharing = Boolean.parseBoolean(propagateSharingString);

    CedarPermission updatePermission = CedarPermission.getUpdateForVersionedArtifactType(artifactType);
    if (updatePermission == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_ARTIFACT_TYPE)
          .errorMessage("You passed an illegal artifact type for versioning:'" + artifactType.getValue() + "'. The allowed values are:" +
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

    String getResponse = getSchemaArtifactFromArtifactServer(artifactType, aid, c);
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
          newDocument.remove(ModelNodeNames.JSON_LD_ID);

          userMustHaveWriteAccessToFolder(c, fid);

          String artifactServerPostRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(newDocument);

          Response artifactServerPostResponse = executeResourcePostToArtifactServer(c, artifactType,
              artifactServerPostRequestBodyAsString);

          int artifactServerPostStatus = artifactServerPostResponse.getStatus();
          InputStream is = (InputStream) artifactServerPostResponse.getEntity();
          JsonNode artifactServerPostResponseNode = JsonMapper.MAPPER.readTree(is);
          if (artifactServerPostStatus == Response.Status.CREATED.getStatusCode()) {
            JsonNode atId = artifactServerPostResponseNode.at(ModelPaths.AT_ID);
            String newIdString = atId.asText();
            CedarUntypedSchemaArtifactId newId = CedarUntypedSchemaArtifactId.build(newIdString);

            FolderServerArtifact sourceResource = folderSession.findSchemaArtifactById(aid);

            BiboStatus status = BiboStatus.DRAFT;

            FolderServerArtifact brandNewResource = GraphDbObjectBuilder.forResourceType(artifactType, newId, sourceResource.getName(),
                sourceResource.getDescription(), sourceResource.getIdentifier(), newVersion, status);
            if (brandNewResource instanceof FolderServerSchemaArtifact) {
              FolderServerSchemaArtifact schemaArtifact = (FolderServerSchemaArtifact) brandNewResource;
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
                ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);
                CedarNodePermissions permissions = permissionSession.getResourcePermissions(aid);
                ResourcePermissionsRequest permissionsRequest = permissions.toRequest();
                ResourcePermissionUser newOwner = new ResourcePermissionUser();
                newOwner.setId(c.getCedarUser().getId());
                permissionsRequest.setOwner(newOwner);
                BackendCallResult backendCallResult = permissionSession.updateResourcePermissions(newId, permissionsRequest);
                if (backendCallResult.isError()) {
                  throw new CedarBackendException(backendCallResult);
                }
              }
            }
            FolderServerArtifact createdNewResource = folderSession.findArtifactById(newId);
            createIndexArtifact(createdNewResource, c);
            FolderServerArtifact updatedSourceResource = folderSession.findArtifactById(aid);
            updateIndexResource(updatedSourceResource, c);

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

}
