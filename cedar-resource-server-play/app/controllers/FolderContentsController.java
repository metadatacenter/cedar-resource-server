package controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.constant.ConfigConstants;
import org.metadatacenter.model.resourceserver.CedarRSFolder;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.F;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FolderContentsController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(FolderContentsController.class);

  final static List<String> knownSortKeys;

  public static final String folderBase;
  private static final String ROOT_PATH_BY_PATH = "folders/contents";
  private static final String ROOT_PATH_BY_ID = "folders/";

  static {
    knownSortKeys = new ArrayList<>();
    knownSortKeys.add("name");
    knownSortKeys.add("createdOn");
    knownSortKeys.add("lastUpdatedOn");
    folderBase = config.getString(ConfigConstants.FOLDER_SERVER_BASE);
  }

  public static Result findFolderContentsByPath(F.Option<String> pathParam, F.Option<String> resourceTypes, F
      .Option<String> sort, F.Option<Integer> limitParam, F.Option<Integer> offsetParam) {

    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String absoluteUrl = routes.FolderContentsController.findFolderContentsByPath(pathParam, resourceTypes, sort,
          limitParam, offsetParam).absoluteURL(request());

      int idx = absoluteUrl.indexOf(ROOT_PATH_BY_PATH);
      String suffix = absoluteUrl.substring(idx);

      String url = folderBase + suffix;

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      return deserializeAndConvertFolderNamesIfNecessary(proxyResponse);
    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  public static Result findFolderContentsById(String id, F.Option<String> resourceTypes, F
      .Option<String> sort, F.Option<Integer> limitParam, F.Option<Integer> offsetParam) {

    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String absoluteUrl = routes.FolderContentsController.findFolderContentsById(id, resourceTypes, sort,
          limitParam, offsetParam).absoluteURL(request());

      int idx = absoluteUrl.indexOf(ROOT_PATH_BY_ID);
      String suffix = absoluteUrl.substring(idx);

      String url = folderBase + suffix;

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      return deserializeAndConvertFolderNamesIfNecessary(proxyResponse);
    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  private static Result deserializeAndConvertFolderNamesIfNecessary(HttpResponse proxyResponse) throws IOException {
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      RSNodeListResponse response = null;
      try {
        String responseString = EntityUtils.toString(proxyResponse.getEntity());
        response = MAPPER.readValue(responseString, RSNodeListResponse.class);
      } catch (JsonProcessingException e) {
        e.printStackTrace();
      }
      // it can not be deserialized as RSNodeListResponse
      if (response == null) {
        return Results.status(statusCode, entity.getContent());
      } else {
        if (response.getResources() != null) {
          response.getResources().forEach(rsNode -> addUserHomeFolderDisplayName(rsNode, request()));
        }
        if (response.getPathInfo() != null) {
          response.getPathInfo().forEach(rsNode -> addUserHomeFolderDisplayName(rsNode, request()));
        }
        return Results.status(statusCode, MAPPER.writeValueAsString(response));
      }
    } else {
      return Results.status(statusCode);
    }
  }


}
