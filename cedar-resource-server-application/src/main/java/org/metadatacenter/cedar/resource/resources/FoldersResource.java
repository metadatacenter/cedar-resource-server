package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.security.model.auth.CedarPermission;
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
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
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
          return Response.created(location).entity(resourceWithExpandedProvenanceInfo(proxyResponse,
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
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);

    // TODO: the folder returned by this may be that is exactly what
    // we read below. Check this
    FolderServerFolder folderServerFolder = userMustHaveReadAccessToFolder(c, id);


    String url = microserviceUrlUtil.getWorkspace().getFolderWithId(id);
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      if (HttpStatus.SC_OK == statusCode) {
        return Response.ok().entity(resourceWithExpandedProvenanceInfo(proxyResponse, FolderServerNode.class)).build();
      } else {
        try {
          return Response.status(statusCode).entity(entity.getContent()).build();
        } catch (IOException e) {
          throw new CedarProcessingException(e);
        }
      }
    } else {
      return Response.status(statusCode).build();
    }
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
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);

    return updateFolderNameAndDescriptionOnFolderServer(c, id);
  }

  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteFolder(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_DELETE);

    userMustHaveWriteAccessToFolder(c, id);

    String url = microserviceUrlUtil.getWorkspace().getFolderWithId(id);

    HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int folderDeleteStatusCode = proxyResponse.getStatusLine().getStatusCode();
    if (HttpStatus.SC_NO_CONTENT == folderDeleteStatusCode) {
      // remove the folder from the index
      indexRemoveDocument(id);
      return Response.noContent().build();
    } else {
      return generateStatusResponse(proxyResponse);
    }
  }


  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getFolderPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);

    userMustHaveReadAccessToFolder(c, id);
    return executeFolderPermissionGetByProxy(id, c);
  }

  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateFolderPermissions(@PathParam(PP_ID) String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);

    userMustHaveWriteAccessToFolder(c, id);

    return executeFolderPermissionPutByProxy(id, c);
  }

  private Response executeFolderPermissionGetByProxy(String folderId, CedarRequestContext context) throws
      CedarException {
    try {
      String url = microserviceUrlUtil.getWorkspace().getFolderWithIdPermissions(folderId);

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        return Response.status(statusCode).entity(entity.getContent()).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  private Response executeFolderPermissionPutByProxy(String folderId, CedarRequestContext context) throws
      CedarException {
    try {
      String url = microserviceUrlUtil.getWorkspace().getFolderWithIdPermissions(folderId);

      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode == HttpStatus.SC_OK) {
        searchPermissionEnqueueService.folderPermissionsChanged(folderId);
      }
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        return Response.status(statusCode).entity(entity.getContent()).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }
}