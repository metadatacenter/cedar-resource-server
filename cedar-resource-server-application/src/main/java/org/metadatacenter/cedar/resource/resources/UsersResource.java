package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.config.CedarConfig;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/users", description = "User operations")
public class UsersResource extends AbstractResourceServerResource {

  private
  @Context
  UriInfo uriInfo;

  private
  @Context
  HttpServletRequest request;

  public UsersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @ApiOperation(
      value = "List all users",
      httpMethod = "GET")
  public static Result findUsers() {
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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