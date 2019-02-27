package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.error.CedarErrorReasonKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerNode;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerFolderCurrentUserReport;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.io.IOException;
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

    CedarParameter folderIdP = c.request().getRequestBody().get("folderId");
    c.must(folderIdP).be(NonEmpty);

    String folderId = folderIdP.stringValue();

    userMustHaveWriteAccessToFolder(c, folderId);

    String url = microserviceUrlUtil.getWorkspace().getFolders();
    HttpResponse proxyResponse = ProxyUtil.proxyPost(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      try {
        if (HttpStatus.SC_CREATED == statusCode) {
          FolderServerFolder createdFolder = JsonMapper.MAPPER.readValue(entity.getContent(), FolderServerFolder.class);
          // index the folder that has been created
          createIndexFolder(createdFolder, c);
          URI location = CedarUrlUtil.getLocationURI(proxyResponse);
          return Response.created(location).entity(resourceWithProvenanceDisplayNames(proxyResponse,
              FolderServerNode.class)).build();
        } else {
          return Response.status(statusCode).entity(entity.getContent()).build();
        }
      } catch (IOException e) {
        throw new CedarProcessingException(e);
      }
    } else {
      return Response.status(statusCode).build();
    }
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findFolder(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);

    FolderServerFolderCurrentUserReport folderServerFolder = userMustHaveReadAccessToFolder(c, id);
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

    return updateFolderNameAndDescriptionOnFolderServer(c, id);
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
