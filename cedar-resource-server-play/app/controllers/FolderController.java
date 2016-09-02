package controllers;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.resourceserver.CedarRSFolder;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;
import play.mvc.Results;
import utils.DataServices;

@Api(value = "/folders", description = "Folder operations")
public class FolderController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(FolderController.class);

  @ApiOperation(
      value = "Create folder",
      httpMethod = "POST")
  public static Result createFolder() {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

      String folderId = getFolderIdFromBody();
      if (!userHasWriteAccessToFolder(folderBase, folderId)) {
        return unauthorized("You do not have write access to the folder");
      }

      String url = folderBase + CedarNodeType.Prefix.FOLDERS;
      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_CREATED == statusCode) {
          // index the folder that has been created
          DataServices.getInstance().getSearchService().indexResource(JsonMapper.MAPPER.readValue(entity.getContent(),
              CedarRSFolder.class), null, frontendRequest);
          return ok(resourceWithExpandedProvenanceInfo(request(), proxyResponse));
        } else {
          return Results.status(statusCode, entity.getContent());
        }
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      System.out.println(e.getMessage());
      return internalServerErrorWithError(e);
    }
  }

  @ApiOperation(
      value = "Find folder by id",
      httpMethod = "GET")
  public static Result findFolder(String folderId) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

      if (!userHasReadAccessToFolder(folderBase, folderId)) {
        return unauthorized("You do not have read access to the folder");
      }

      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + new URLCodec().encode(folderId);

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_OK == statusCode) {
          return ok(resourceWithExpandedProvenanceInfo(request(), proxyResponse));
        } else {
          return Results.status(statusCode, entity.getContent());
        }
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  @ApiOperation(
      value = "Find folder details by id",
      httpMethod = "GET")
  public static Result findFolderDetails(String folderId) {
    return findFolder(folderId);
  }

  @ApiOperation(
      value = "Update folder",
      httpMethod = "PUT")
  public static Result updateFolder(String folderId) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

      if (!userHasWriteAccessToFolder(folderBase, folderId)) {
        return unauthorized("You do not have write access to the folder");
      }

      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + new URLCodec().encode(folderId);

      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_OK == statusCode) {
          // update the folder on the index
          DataServices.getInstance().getSearchService().updateIndexedResource(JsonMapper.MAPPER.readValue(entity
              .getContent(), CedarRSFolder.class), null, frontendRequest);
          return ok(resourceWithExpandedProvenanceInfo(request(), proxyResponse));
        } else {
          return Results.status(statusCode, entity.getContent());
        }
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  @ApiOperation(
      value = "Delete folder",
      httpMethod = "DELETE")
  public static Result deleteFolder(String folderId) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

      if (!userHasWriteAccessToFolder(folderBase, folderId)) {
        return unauthorized("You do not have write access to the folder");
      }

      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + new URLCodec().encode(folderId);

      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int folderDeleteStatusCode = proxyResponse.getStatusLine().getStatusCode();
      if (HttpStatus.SC_NO_CONTENT == folderDeleteStatusCode) {
        // remove the folder from the index
        DataServices.getInstance().getSearchService().removeResourceFromIndex(folderId);
        return noContent();
      } else {
        return generateStatusResponse(proxyResponse);
      }
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }


  @ApiOperation(
      value = "Get permissions of a folder",
      httpMethod = "GET")
  public static Result getFolderPermissions(String folderId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.FOLDER_READ);
      if (userHasReadAccessToFolder(folderBase, folderId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the folder permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeFolderPermissionGetByProxy(folderId);
    } else {
      return unauthorized("You do not have read access for this folder");
    }
  }

  @ApiOperation(
      value = "Update folder permissions",
      httpMethod = "PUT")
  public static Result updateFolderPermissions(String folderId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.FOLDER_UPDATE);
      if (userHasWriteAccessToFolder(folderBase, folderId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the folder permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeFolderPermissionPutByProxy(folderId);
    } else {
      return unauthorized("You do not have write access for this folder");
    }
  }

  private static Result executeFolderPermissionGetByProxy(String folderId) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());

      if (!userHasReadAccessToFolder(folderBase, folderId)) {
        return unauthorized("You do not have read access to the folder");
      }

      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + new URLCodec().encode(folderId) + "/permissions";

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        return Results.status(statusCode, entity.getContent());
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  private static Result executeFolderPermissionPutByProxy(String folderId) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());

      if (!userHasWriteAccessToFolder(folderBase, folderId)) {
        return unauthorized("You do not have write access to the folder");
      }

      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + new URLCodec().encode(folderId) + "/permissions";

      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        return Results.status(statusCode, entity.getContent());
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }
}
