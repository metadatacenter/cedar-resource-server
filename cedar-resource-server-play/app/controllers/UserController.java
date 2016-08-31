package controllers;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import play.mvc.Result;
import play.mvc.Results;

@Api(value = "/users", description = "User operations")
public class UserController extends AbstractResourceServerController {

  @ApiOperation(
      value = "List all users",
      httpMethod = "GET")
  public static Result findUsers() {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the users", e);
      return forbiddenWithError(e);
    }

    String url = usersURL;
    try {
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Results.status(statusCode, EntityUtils.toString(entity));
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }
}
