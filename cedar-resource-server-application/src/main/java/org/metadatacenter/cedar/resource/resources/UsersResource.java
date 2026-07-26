package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Users")
@SecurityRequirement(name = "api_key")
public class UsersResource extends AbstractResourceServerResource {

  private static final Logger log = LoggerFactory.getLogger(UsersResource.class);

  public UsersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Operation(summary = "Users", description = "The Users endpoint returns information about the users of the system.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "An array of users", content = @Content(schema = @Schema(implementation = User.class))),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
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
