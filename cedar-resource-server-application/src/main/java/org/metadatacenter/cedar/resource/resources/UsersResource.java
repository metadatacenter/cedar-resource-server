package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.util.http.CedarEntityUtil;
import org.metadatacenter.util.http.ProxyUtil;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/users", description = "User operations")
public class UsersResource extends AbstractResourceServerResource {

  public UsersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @ApiOperation(
      value = "List all users",
      httpMethod = "GET")
  @GET
  @Timed
  public Response findUsers() throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    String url = usersURL;
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    HttpEntity entity = proxyResponse.getEntity();
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    if (entity != null) {
      return Response.status(statusCode).entity(CedarEntityUtil.toString(entity)).build();
    } else {
      return Response.status(statusCode).build();
    }
  }
}