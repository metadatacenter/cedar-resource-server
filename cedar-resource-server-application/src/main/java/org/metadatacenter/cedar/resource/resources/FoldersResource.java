package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorReasonKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FileSystemResource;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerFolderCurrentUserReport;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/folders")
public class FoldersResource extends AbstractResourceServerResource {

  public FoldersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  public Response createFolder() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_CREATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);

    CedarParameter folderIdP = c.request().getRequestBody().get("folderId");
    c.must(folderIdP).be(NonEmpty);
    String folderId = folderIdP.stringValue();

    userMustHaveWriteAccessToFolder(c, folderId);

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
      parentFolder = folderSession.findFolderById(folderIdV);
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
    c.must(description).be(NonEmpty);

    // check existence of parent folder
    FolderServerFolder newFolder = null;
    FileSystemResource newFolderCandidate = folderSession.findNodeByParentIdAndName(parentFolder, nameV);
    if (newFolderCandidate != null) {
      return CedarResponse.badRequest()
          .parameter("parentFolderId", parentFolder.getId())
          .parameter("name", name)
          .errorKey(CedarErrorKey.NODE_ALREADY_PRESENT)
          .errorMessage("There is already a node with the same name at the requested location!")
          .parameter("conflictingResourceType", newFolderCandidate.getType().getValue())
          .parameter("conflictingResourceId", newFolderCandidate.getId())
          .build();
    }

    String descriptionV = description.stringValue();

    FolderServerFolder brandNewFolder = new FolderServerFolder();
    brandNewFolder.setName(nameV);
    brandNewFolder.setDescription(descriptionV);
    newFolder = folderSession.createFolderAsChildOfId(brandNewFolder, parentFolder.getId());

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

  @GET
  @Timed
  @Path("/{id}")
  public Response findFolder(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);

    FolderServerFolderCurrentUserReport folderServerFolder = userMustHaveReadAccessToFolder(c, id);
    addProvenanceDisplayName(folderServerFolder);
    return Response.ok().entity(folderServerFolder).build();
  }

  @GET
  @Timed
  @Path("/{id}/details")
  public Response findFolderDetails(@PathParam(PP_ID) String id) throws CedarException {
    return findFolder(id);
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateFolder(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);
    c.must(c.request().getRequestBody()).be(NonEmpty);

    return updateFolderNameAndDescriptionInGraphDb(c, id);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteFolder(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_DELETE);

    FolderServerFolderCurrentUserReport folder = userMustHaveWriteAccessToFolder(c, id);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    if (folder == null) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .errorMessage("The folder can not be found by id")
          .build();
    } else {
      long contentCount = folderSession.findFolderContentsUnfilteredCount(id);
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
        boolean deleted = folderSession.deleteFolderById(id);
        if (deleted) {
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
  @Path("/{id}/permissions")
  public Response getFolderPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);

    return generateNodePermissionsResponse(c, id);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateFolderPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);

    return updateNodePermissions(c, id);
  }
}
