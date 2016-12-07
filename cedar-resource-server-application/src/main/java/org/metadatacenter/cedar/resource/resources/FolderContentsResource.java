package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.config.CedarConfig;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;

@Path("/folders")
@Produces(MediaType.APPLICATION_JSON)
public class FolderContentsResource extends AbstractResourceServerResource {

  private
  @Context
  UriInfo uriInfo;

  private
  @Context
  HttpServletRequest request;

  private static final String ROOT_PATH_BY_PATH = "folders/contents";
  private static final String ROOT_PATH_BY_ID = "folders/";

  public FolderContentsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  public static Result findFolderContentsByPath(F.Option<String> pathParam, F.Option<String> resourceTypes, F
      .Option<String> sort, F.Option<Integer> limitParam, F.Option<Integer> offsetParam) {

    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

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
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

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
      FolderServerNodeListResponse response = null;
      try {
        String responseString = EntityUtils.toString(proxyResponse.getEntity());
        response = JsonMapper.MAPPER.readValue(responseString, FolderServerNodeListResponse.class);
      } catch (JsonProcessingException e) {
        e.printStackTrace();
      }
      // it can not be deserialized as RSNodeListResponse
      if (response == null) {
        return Results.status(statusCode, entity.getContent());
      } else {
        return Results.status(statusCode, JsonMapper.MAPPER.writeValueAsString(response));
      }
    } else {
      return Results.status(statusCode);
    }
  }
}