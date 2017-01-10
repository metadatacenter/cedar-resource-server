package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

@Path("/folders")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/folders", description = "Folder operations")
public class FoldersResource extends AbstractResourceServerResource {

  public FoldersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @ApiOperation(
      value = "Create folder")
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

    String url = folderBase + CedarNodeType.Prefix.FOLDERS;
    HttpResponse proxyResponse = ProxyUtil.proxyPost(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      try {
        if (HttpStatus.SC_CREATED == statusCode) {
          // index the folder that has been created
          searchService.indexResource(JsonMapper.MAPPER.readValue(entity.getContent(),
              FolderServerFolder.class), null, c);
          //TODO: use created here, with the proxied location header
          return Response.ok().entity(resourceWithExpandedProvenanceInfo(proxyResponse, c)).build();
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

  @ApiOperation(
      value = "Find folder by id")
  @GET
  @Timed
  @Path("/{id}")
  public Response findFolder(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);

    // TODO: the folder returned by this may be that is exactly what
    // we read below. Check this
    userMustHaveReadAccessToFolder(c, id);

    String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + CedarUrlUtil.urlEncode(id);

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      if (HttpStatus.SC_OK == statusCode) {
        return Response.ok().entity(resourceWithExpandedProvenanceInfo(proxyResponse, c)).build();
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

  @ApiOperation(
      value = "Find folder details by id")
  @GET
  @Timed
  @Path("/{id}/details")
  public Response findFolderDetails(@PathParam("id") String id) throws CedarException {
    return findFolder(id);
  }

  @ApiOperation(
      value = "Update folder")
  @PUT
  @Timed
  @Path("/{id}")
  public Response updateFolder(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);

    userMustHaveWriteAccessToFolder(c, id);

    String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + CedarUrlUtil.urlEncode(id);

    HttpResponse proxyResponse = ProxyUtil.proxyPut(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      try {
        if (HttpStatus.SC_OK == statusCode) {
          // update the folder on the index
          searchService.updateIndexedResource(JsonMapper.MAPPER.readValue(entity
              .getContent(), FolderServerFolder.class), null, c);
          return Response.ok().entity(resourceWithExpandedProvenanceInfo(proxyResponse, c)).build();
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

  @ApiOperation(
      value = "Delete folder")
  @DELETE
  @Timed
  @Path("/{id}")
  public Response deleteFolder(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_DELETE);

    userMustHaveWriteAccessToFolder(c, id);

    String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + CedarUrlUtil.urlEncode(id);

    HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int folderDeleteStatusCode = proxyResponse.getStatusLine().getStatusCode();
    if (HttpStatus.SC_NO_CONTENT == folderDeleteStatusCode) {
      // remove the folder from the index
      searchService.removeResourceFromIndex(id);
      return Response.noContent().build();
    } else {
      return generateStatusResponse(proxyResponse);
    }
  }


  @ApiOperation(
      value = "Get permissions of a folder")
  @GET
  @Timed
  @Path("/{id}/permissions")
  public Response getFolderPermissions(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_READ);

    userMustHaveReadAccessToFolder(c, id);
    return executeFolderPermissionGetByProxy(id, c);
  }

  @ApiOperation(
      value = "Update folder permissions")
  @PUT
  @Timed
  @Path("/{id}/permissions")
  public Response updateFolderPermissions(@PathParam("id") String id) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.FOLDER_UPDATE);

    userMustHaveWriteAccessToFolder(c, id);

    return executeFolderPermissionPutByProxy(id, c);
  }

  private Response executeFolderPermissionGetByProxy(String folderId, CedarRequestContext context) throws
      CedarException {
    try {
      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + CedarUrlUtil.urlEncode(folderId) + "/permissions";

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
      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + CedarUrlUtil.urlEncode(folderId) + "/permissions";

      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, context);
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
}