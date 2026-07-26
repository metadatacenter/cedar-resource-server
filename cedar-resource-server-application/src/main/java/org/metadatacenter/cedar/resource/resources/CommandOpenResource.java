package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandOpenResource extends AbstractResourceServerResource {

  public CommandOpenResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/make-artifact-open")
  @Operation(summary = "Make artifact open", description = "Make artifact open.", tags = {"Command", "OpenView"})
  @RequestBody(description = "Id of the artifact to make open", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
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
  @Operation(summary = "Make artifact not open", description = "Make artifact not open.", tags = {"Command", "OpenView"})
  @RequestBody(description = "Id of the artifact to make open", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
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
  @Operation(summary = "Make folder open", description = "Make folder open.", tags = {"Command", "OpenView"})
  @RequestBody(description = "Id of the folder to make open", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
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
  @Operation(summary = "Make folder not open", description = "Make folder not open.", tags = {"Command", "OpenView"})
  @RequestBody(description = "Id of the folder to make open", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
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
