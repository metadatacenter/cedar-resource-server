package controllers;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.constant.ConfigConstants;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.F;
import play.mvc.Result;
import play.mvc.Results;

import java.util.ArrayList;
import java.util.List;

public class FolderContentsController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(FolderContentsController.class);

  final static List<String> knownSortKeys;

  public static final String folderBase;
  private static final String ROOT_PATH = "folder-contents";

  static {
    knownSortKeys = new ArrayList<>();
    knownSortKeys.add("name");
    knownSortKeys.add("createdOn");
    knownSortKeys.add("lastUpdatedOn");
    folderBase = config.getString(ConfigConstants.FOLDER_SERVER_BASE);
  }

  public static Result findFolderContents(F.Option<String> pathParam, F.Option<String> resourceTypes, F
      .Option<String> sort, F.Option<Integer> limitParam, F.Option<Integer> offsetParam) {

    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      String absoluteUrl = routes.FolderContentsController.findFolderContents(pathParam, resourceTypes, sort,
          limitParam, offsetParam).absoluteURL(request());

      int idx = absoluteUrl.indexOf(ROOT_PATH);
      String suffix = absoluteUrl.substring(idx);

      String url = folderBase + suffix;

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
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
