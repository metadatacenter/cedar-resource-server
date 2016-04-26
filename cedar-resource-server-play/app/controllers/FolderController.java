package controllers;

import org.apache.commons.codec.net.URLCodec;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;
import play.mvc.Results;

public class FolderController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(FolderController.class);

  public static Result createFolder() {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String url = folderBase + CedarNodeType.Prefix.FOLDERS;

      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_CREATED == statusCode) {
          return ok(resourceWithExpandedProvenanceInfo(proxyResponse));
        } else {
          return Results.status(statusCode, entity.getContent());
        }
      } else {
        return Results.status(statusCode);
      }

    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  public static Result findFolder(String folderId) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + new URLCodec().encode(folderId);

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();

      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_OK == statusCode) {
          return ok(resourceWithExpandedProvenanceInfo(proxyResponse));
        } else {
          return Results.status(statusCode, entity.getContent());
        }
      } else {
        return Results.status(statusCode);
      }

    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }


  public static Result updateFolder(String folderId) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + new URLCodec().encode(folderId);

      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_OK == statusCode) {
          return ok(resourceWithExpandedProvenanceInfo(proxyResponse));
        } else {
          return Results.status(statusCode, entity.getContent());
        }
      } else {
        return Results.status(statusCode);
      }

    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  public static Result deleteFolder(String folderId) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String url = folderBase + CedarNodeType.Prefix.FOLDERS + "/" + new URLCodec().encode(folderId);

      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        return Results.status(statusCode, entity.getContent());
      } else {
        return Results.status(statusCode);
      }

    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

}
