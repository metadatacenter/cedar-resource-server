package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.resource.resources.swaggermodel.User;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.folderserver.basic.FolderServerUser;
import org.metadatacenter.model.response.FolderServerUserListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.UserServiceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/users", tags = "Users", authorizations = {@Authorization("api_key")})
public class UsersResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(UsersResource.class);

  public UsersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @ApiOperation(value = "Users", notes = "The Users endpoint returns information about the users of the system.",
      response = User.class, responseContainer = "List")
  @ApiResponses({
      @ApiResponse(code = 200, message = "An array of users", response = User.class, responseContainer = "List"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response findUsers() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    UserServiceSession userSession = CedarDataServices.getUserServiceSession(c);

    List<FolderServerUser> users = userSession.findUsers();

    FolderServerUserListResponse r = new FolderServerUserListResponse();

    r.setUsers(users);

    return Response.ok().entity(r).build();
  }
}
