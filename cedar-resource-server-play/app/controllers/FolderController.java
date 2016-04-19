package controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.constant.ConfigConstants;
import org.metadatacenter.model.CedarFolder;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;
import play.mvc.Results;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FolderController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(FolderController.class);

  final static List<String> knownSortKeys;

  final static String folderBase;


  static {
    knownSortKeys = new ArrayList<>();
    knownSortKeys.add("name");
    knownSortKeys.add("createdOn");
    knownSortKeys.add("lastUpdatedOn");
    folderBase = config.getString(ConfigConstants.FOLDER_SERVER_BASE);
  }

  public static Result createFolder() {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String url = folderBase + "folders";

      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_CREATED == statusCode) {
          return ok(folderWithExpandedProvenanceInfo(proxyResponse));
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

      String url = folderBase + "folders/" + folderId;

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();

      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_OK == statusCode) {
          return ok(folderWithExpandedProvenanceInfo(proxyResponse));
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

      String url = folderBase + "folders/" + folderId;

      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_OK == statusCode) {
          return ok(folderWithExpandedProvenanceInfo(proxyResponse));
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
    System.out.println("HERE in delete folder");
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String url = folderBase + "folders/" + folderId;

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

  private static CedarFolder deserializeAndAddProvenanceInfoToFolder(HttpResponse proxyResponse) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    CedarFolder folder = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      System.out.println("Response string:" + responseString);
      folder = mapper.readValue(responseString, CedarFolder.class);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    if (folder != null) {
      // TODO: get real user names here
      folder.getCreatedBy().setName("Foo Bar");
      folder.getLastUpdatedBy().setName("Foo Bar");
    }
    return folder;
  }

  private static JsonNode folderWithExpandedProvenanceInfo(HttpResponse proxyResponse) throws IOException {
    CedarFolder foundFolder = deserializeAndAddProvenanceInfoToFolder(proxyResponse);
    ObjectMapper mapper = new ObjectMapper();
    return mapper.valueToTree(foundFolder);
  }

}
