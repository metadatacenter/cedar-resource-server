package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import org.metadatacenter.bridge.CedarDataServices;
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
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;

import static org.metadatacenter.constant.CedarPathParameters.PP_FOLDER_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.*;

@Path("/folders")
@Api(value = "/folders", tags = "Folders", authorizations = {@Authorization("api_key")})
public class FoldersResource extends AbstractResourceServerResource {

  public FoldersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @ApiOperation(value = "Create a folder", notes = "Create a folder.", code = 201, response = Folder.class)
  @ApiImplicitParams({
      @ApiImplicitParam(name = "folder", value = "The folder to be created", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.Folder", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 201, message = "A folder", response = Folder.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response createFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    CedarFolderId newFolderId = linkedDataUtil.buildNewLinkedDataIdObject(CedarFolderId.class);
    return createFolderWithId(c, newFolderId);
  }

  @GET
  @Timed
  @Path("/{folder_id}")
  @ApiOperation(value = "Get a folder", notes = "Get a folder.", response = Folder.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A folder", response = Folder.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findFolder(
      @ApiParam(value = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);
    CedarFolderId fid = CedarFolderId.build(id);

    userMustHaveReadAccessToFolder(c, fid);
    FolderServerFolderCurrentUserReport folderServerFolder = getFolderReport(c, fid);
    ProvenanceNameUtil.addProvenanceDisplayName(folderServerFolder);
    return Response.ok().entity(folderServerFolder).build();
  }

  @GET
  @Timed
  @Path("/{folder_id}/details")
  @ApiOperation(value = "Get the details of a folder", notes = "Get the details of a folder.")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findFolderDetails(
      @ApiParam(value = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    return findFolder(id);
  }

  @PUT
  @Timed
  @Path("/{folder_id}")
  @ApiOperation(value = "Update a folder", notes = "Update a folder.", response = Folder.class)
  @ApiResponses({
      @ApiResponse(code = 200, message = "A folder", response = Folder.class),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response createOrUpdateFolder(
      @ApiParam(value = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(id).be(ValidId);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);
    CedarFolderId folderId = CedarFolderId.build(id);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
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
  @ApiOperation(value = "Delete a folder", notes = "Delete a folder.")
  @ApiResponses({
      @ApiResponse(code = 204, message = "Successful operation (no content)"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response deleteFolder(
      @ApiParam(value = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_DELETE);
    CedarFolderId fid = CedarFolderId.build(id);

    userMustHaveWriteAccessToFolder(c, fid);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
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
        boolean deleted = folderSession.deleteFolderById(fid);
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
  @ApiOperation(value = "Get permissions of a folder", notes = "Get permissions of a folder.",
      tags = {"Folders", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response getFolderPermissions(
      @ApiParam(value = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
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
  @ApiOperation(value = "Update permissions of a folder", notes = "Update permissions of a folder.",
      tags = {"Folders", "Permissions"})
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response updateFolderPermissions(
      @ApiParam(value = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
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
    c.must(folderIdP).be(NonEmpty);
    String folderId = folderIdP.stringValue();
    CedarFolderId fid = CedarFolderId.build(folderId);

    userMustHaveWriteAccessToFolder(c, fid);

    CedarParameter path = c.request().getRequestBody().get("path");

    if (folderIdP.isMissing() && path.isEmpty()) {
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

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    FolderServerFolder parentFolder = null;

    String pathV = null;
    String folderIdV;

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

    if (!folderId.isEmpty()) {
      folderIdV = folderIdP.stringValue();
      CedarFolderId fidv = CedarFolderId.build(folderIdV);
      parentFolder = folderSession.findFolderById(fidv);
    }

    if (parentFolder == null) {
      return CedarResponse.badRequest()
          .parameter("path", path)
          .parameter("folderId", folderId)
          .errorKey(CedarErrorKey.PARENT_FOLDER_NOT_FOUND)
          .errorMessage("The parent folder is not present!")
          .build();
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
      return CedarResponse.badRequest()
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
    return Response.created(uri).entity(newFolder).build();
  }
}
