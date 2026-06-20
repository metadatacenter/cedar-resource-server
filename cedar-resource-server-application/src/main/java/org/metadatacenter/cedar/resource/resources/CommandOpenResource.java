package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/command", tags = "Command", authorizations = {@Authorization("api_key")})
public class CommandOpenResource extends AbstractResourceServerResource {

  public CommandOpenResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/make-artifact-open")
  @ApiOperation(value = "Make artifact open", notes = "Make artifact open.", tags = {"Command", "OpenView"})
  @ApiImplicitParams({
      @ApiImplicitParam(name = "idRequest", value = "Id of the artifact to make open", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response makeArtifactOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    CedarUntypedArtifactId artifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToArtifact(c, artifactId);

    folderSession.setOpen(artifactId);
    FolderServerArtifact updatedResource = folderSession.findArtifactById(artifactId);
    return Response.ok().entity(updatedResource).build();
  }

  @POST
  @Timed
  @Path("/make-artifact-not-open")
  @ApiOperation(value = "Make artifact not open", notes = "Make artifact not open.", tags = {"Command", "OpenView"})
  @ApiImplicitParams({
      @ApiImplicitParam(name = "idRequest", value = "Id of the artifact to make open", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response makeArtifactNotOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    CedarUntypedArtifactId artifactId = CedarUntypedArtifactId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToArtifact(c, artifactId);

    folderSession.setNotOpen(artifactId);
    FolderServerArtifact updatedResource = folderSession.findArtifactById(artifactId);
    return Response.ok().entity(updatedResource).build();
  }

  @POST
  @Timed
  @Path("/make-folder-open")
  @ApiOperation(value = "Make folder open", notes = "Make folder open.", tags = {"Command", "OpenView"})
  @ApiImplicitParams({
      @ApiImplicitParam(name = "idRequest", value = "Id of the folder to make open", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response makeFolderOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    CedarFolderId folderId = CedarFolderId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToFolder(c, folderId);

    folderSession.setOpen(folderId);
    FolderServerFolder updatedFolder = folderSession.findFolderById(folderId);
    return Response.ok().entity(updatedFolder).build();
  }

  @POST
  @Timed
  @Path("/make-folder-not-open")
  @ApiOperation(value = "Make folder not open", notes = "Make folder not open.", tags = {"Command", "OpenView"})
  @ApiImplicitParams({
      @ApiImplicitParam(name = "idRequest", value = "Id of the folder to make open", required = true,
          dataType = "org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest", paramType = "body")
  })
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public Response makeFolderNotOpen() throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    String id = requestBody.get("@id").stringValue();
    CedarFolderId folderId = CedarFolderId.build(id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    userMustHaveWriteAccessToFolder(c, folderId);

    folderSession.setNotOpen(folderId);
    FolderServerFolder updatedFolder = folderSession.findFolderById(folderId);
    return Response.ok().entity(updatedFolder).build();
  }

}
