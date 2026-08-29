package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.GraphDbPermissionReader;
import org.metadatacenter.cedar.resource.resources.swaggermodel.Folder;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.LinkedData;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorReasonKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarUntypedFilesystemResourceId;
import org.metadatacenter.model.folderserver.basic.FileSystemResource;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerFolderCurrentUserReport;
import org.metadatacenter.operation.CedarOperations;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.RevisionConflictException;
import org.metadatacenter.server.VersionedResource;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.RevisionPreconditionParser;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;

import static org.metadatacenter.constant.CedarPathParameters.PP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.*;

@Path("/folders")
@Tag(name = "Folders")
@SecurityRequirement(name = "api_key")
public class FoldersResource extends AbstractResourceServerResource {

  public FoldersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Operation(summary = "Create a folder", description = "Create a folder.")
  @RequestBody(description = "The folder to be created", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.Folder.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "A folder", content = @Content(schema = @Schema(implementation = Folder.class))),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response createFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    CedarFolderId newFolderId = linkedDataUtil.buildNewLinkedDataIdObject(CedarFolderId.class);
    return createFolderWithId(c, newFolderId);
  }

  @GET
  @Timed
  @Path("/{folder_id}")
  @Operation(summary = "Get a folder", description = "Get a folder.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A folder", content = @Content(schema = @Schema(implementation = Folder.class))),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response findFolder(
      @Parameter(description = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);
    CedarFolderId fid = CedarFolderId.build(id);

    userMustHaveReadAccessToFolder(c, fid);
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    VersionedResource<FolderServerFolder> snapshot = folderSession.findVersionedFolderById(fid);
    if (snapshot == null) {
      return CedarResponse.notFound().id(id).errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .errorMessage("The folder can not be found by id").build();
    }
    ResourcePermissionServiceSession permissionSession = dataServices.getResourcePermissionServiceSession(c);
    FolderServerFolderCurrentUserReport folderServerFolder = GraphDbPermissionReader.getFolderCurrentUserReport(
        c, folderSession, permissionSession, snapshot.resource());
    ProvenanceNameUtil.addProvenanceDisplayName(folderServerFolder);
    return Response.ok().header(HttpHeaders.ETAG, RevisionPreconditionParser.format(snapshot.revision()))
        .entity(folderServerFolder).build();
  }

  @GET
  @Timed
  @Path("/{folder_id}/details")
  @Operation(summary = "Get the details of a folder", description = "Get the details of a folder.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response findFolderDetails(
      @Parameter(description = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    return findFolder(id);
  }

  @PUT
  @Timed
  @Path("/{folder_id}")
  @Operation(summary = "Update a folder", description = "Update a folder.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "A folder", content = @Content(schema = @Schema(implementation = Folder.class))),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response createOrUpdateFolder(
      @Parameter(description = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(id).be(ValidId);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);
    CedarFolderId folderId = CedarFolderId.build(id);

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    FolderServerFolder folder = folderSession.findFolderById(folderId);
    if (folder != null) {
      return updateFolderNameAndDescriptionInGraphDb(c, folderId);
    } else {
      CedarParameter atIdParameter = c.request().getRequestBody().get(LinkedData.ID);
      if (atIdParameter.isEmpty()) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.MISSING_DATA)
            .errorMessage("For 'create-with-id' the new folder @id should be present in the body as well!")
            .parameter("@id", id)
            .operation(CedarOperations.createWithId(FolderServerFolder.class, "id", id))
            .build();
      } else if (!atIdParameter.stringValue().equals(id)) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.INVALID_DATA)
            .errorMessage("For 'create-with-id' the same folder @id should be present in the URL and the body.")
            .parameter("@idURL", id)
            .parameter("@idBody", atIdParameter.stringValue())
            .operation(CedarOperations.createWithId(FolderServerFolder.class, "id", id))
            .build();
      }
      return createFolderWithId(c, folderId);
    }
  }

  @DELETE
  @Timed
  @Path("/{folder_id}")
  @Operation(summary = "Delete a folder", description = "Delete a folder.")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Successful operation (no content)"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response deleteFolder(
      @Parameter(description = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_DELETE);
    CedarFolderId fid = CedarFolderId.build(id);

    userMustHaveWriteAccessToFolder(c, fid);

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    FolderServerFolder folder = folderSession.findFolderById(fid);

    if (folder == null) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .errorMessage("The folder can not be found by id")
          .build();
    } else {
      long contentCount = folderSession.findFolderContentsUnfilteredCount(fid);
      if (contentCount > 0) {
        return CedarResponse.badRequest()
            .id(id)
            .errorKey(CedarErrorKey.FOLDER_CAN_NOT_BE_DELETED)
            .errorReasonKey(CedarErrorReasonKey.NON_EMPTY_FOLDER)
            .errorMessage("Non-empty folders can not be deleted")
            .build();
      } else if (folder.isUserHome()) {
        return CedarResponse.badRequest()
            .id(id)
            .errorKey(CedarErrorKey.FOLDER_CAN_NOT_BE_DELETED)
            .errorReasonKey(CedarErrorReasonKey.USER_HOME_FOLDER)
            .errorMessage("User home folders can not be deleted")
            .build();
      } else if (folder.isSystem()) {
        return CedarResponse.badRequest()
            .id(id)
            .errorKey(CedarErrorKey.FOLDER_CAN_NOT_BE_DELETED)
            .errorReasonKey(CedarErrorReasonKey.SYSTEM_FOLDER)
            .errorMessage("System folders can not be deleted")
            .build();
      } else {
        String ifMatch = c.getIfMatchHeader();
        if (ifMatch == null || ifMatch.isBlank()) {
          return CedarResponse.status(org.metadatacenter.http.CedarResponseStatus.PRECONDITION_REQUIRED)
              .errorMessage("Deleting a folder requires the ETag returned by GET in If-Match")
              .build();
        }
        boolean deleted;
        try {
          deleted = folderSession.deleteFolderById(fid, RevisionPreconditionParser.parse(ifMatch));
        } catch (RevisionConflictException e) {
          return CedarResponse.status(org.metadatacenter.http.CedarResponseStatus.PRECONDITION_FAILED)
              .parameter("currentETag", RevisionPreconditionParser.format(e.getCurrentRevision()))
              .errorMessage("The folder has been updated since it was read")
              .build();
        }
        if (deleted) {
          removeIndexDocument(CedarUntypedFilesystemResourceId.build(id));
          return CedarResponse.noContent().build();
        } else {
          return CedarResponse.internalServerError()
              .id(id)
              .errorKey(CedarErrorKey.FOLDER_NOT_DELETED)
              .errorMessage("The folder can not be delete by id")
              .build();
        }
      }
    }
  }

  @GET
  @Timed
  @Path("/{folder_id}/permissions")
  @Operation(summary = "Get permissions of a folder", description = "Get permissions of a folder.", tags = {"Folders", "Permissions"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response getFolderPermissions(
      @Parameter(description = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);
    CedarFolderId fid = CedarFolderId.build(id);

    return generateResourcePermissionsResponse(c, fid);
  }

  @PUT
  @Timed
  @Path("/{folder_id}/permissions")
  @Operation(summary = "Update permissions of a folder", description = "Update permissions of a folder.", tags = {"Folders", "Permissions"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response updateFolderPermissions(
      @Parameter(description = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);
    CedarFolderId fid = CedarFolderId.build(id);

    return updateResourcePermissions(c, fid);
  }


  private Response createFolderWithId(CedarRequestContext c, CedarFolderId newFolderId) throws CedarException {
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_CREATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);

    CedarParameter folderIdP = c.request().getRequestBody().get("folderId");
    CedarParameter path = c.request().getRequestBody().get("path");

    if (folderIdP.isEmpty() && path.isEmpty()) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.PARENT_FOLDER_NOT_SPECIFIED)
          .errorMessage("You need to supply either path or folderId parameter identifying the parent folder")
          .build();
    }

    if (!folderIdP.isEmpty() && !path.isEmpty()) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.PARENT_FOLDER_SPECIFIED_TWICE)
          .errorMessage("You need to supply either path or folderId parameter (not both) identifying the parent folder")
          .build();
    }

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    FolderServerFolder parentFolder = null;

    String pathV = null;
    String folderIdV = null;

    String normalizedPath = null;
    if (!path.isEmpty()) {
      pathV = path.stringValue();
      normalizedPath = folderSession.normalizePath(pathV);
      if (!normalizedPath.equals(pathV)) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.PATH_NOT_NORMALIZED)
            .errorMessage("You must supply the path of the new folder in normalized form!")
            .build();
      }
      parentFolder = folderSession.findFolderByPath(pathV);
    }

    if (!folderIdP.isEmpty()) {
      folderIdV = folderIdP.stringValue();
      CedarFolderId fidv = CedarFolderId.build(folderIdV);
      userMustHaveWriteAccessToFolder(c, fidv);
      parentFolder = folderSession.findFolderById(fidv);
    }

    if (parentFolder == null) {
      return CedarResponse.badRequest()
          .parameter("path", path)
          .parameter("folderId", folderIdV)
          .errorKey(CedarErrorKey.PARENT_FOLDER_NOT_FOUND)
          .errorMessage("The parent folder is not present!")
          .build();
    }

    // A path identifies the same parent as folderId and must carry the same authorization check.
    if (folderIdP.isEmpty()) {
      userMustHaveWriteAccessToFolder(c, parentFolder.getResourceId());
    }


    // get name parameter
    CedarParameter name = c.request().getRequestBody().get("name");
    name.trim();
    c.must(name).be(NonEmpty);

    String nameV = name.stringValue();
    // test new folder name syntax
    String normalizedName = folderSession.sanitizeName(nameV);
    if (!normalizedName.equals(nameV)) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.CREATE_INVALID_FOLDER_NAME)
          .errorMessage("The new folder name contains invalid characters!")
          .parameter("name", name.stringValue())
          .build();
    }

    CedarParameter description = c.request().getRequestBody().get("description");
    description.trim();
    c.must(description).be(NonEmpty);

    // check existence of parent folder
    FolderServerFolder newFolder = null;
    FileSystemResource newFolderCandidate = folderSession.findFilesystemResourceByParentFolderIdAndName(parentFolder.getResourceId(), nameV);
    if (newFolderCandidate != null) {
      return CedarResponse.conflict()
          .parameter("parentFolderId", parentFolder.getId())
          .parameter("name", name)
          .errorKey(CedarErrorKey.NODE_ALREADY_PRESENT)
          .errorMessage("There is already a resource with the same name at the requested location!")
          .parameter("conflictingResourceType", newFolderCandidate.getType().getValue())
          .parameter("conflictingResourceId", newFolderCandidate.getId())
          .build();
    }

    String descriptionV = description.stringValue();

    FolderServerFolder brandNewFolder = new FolderServerFolder();
    brandNewFolder.setName(nameV);
    brandNewFolder.setDescription(descriptionV);
    newFolder = folderSession.createFolderAsChildOfId(brandNewFolder, parentFolder.getResourceId(), newFolderId);

    if (newFolder == null) {
      return CedarResponse.badRequest()
          .parameter("path", pathV)
          .parameter("parentFolderId", parentFolder.getId())
          .parameter("name", nameV)
          .errorKey(CedarErrorKey.FOLDER_NOT_CREATED)
          .errorMessage("The folder was not created!")
          .build();
    }

    UriBuilder builder = uriInfo.getAbsolutePathBuilder();
    URI uri = builder.path(CedarUrlUtil.urlEncode(newFolder.getId())).build();
    createIndexFolder(newFolder, c);
    return Response.created(uri).header(HttpHeaders.ETAG, RevisionPreconditionParser.format(1L))
        .entity(newFolder).build();
  }
}
