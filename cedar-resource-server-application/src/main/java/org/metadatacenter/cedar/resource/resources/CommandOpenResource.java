package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.http.CedarResponseStatus;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.assertion.noun.CedarRequestBody;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.RevisionConflictException;
import org.metadatacenter.server.RevisionPrecondition;
import org.metadatacenter.server.VersionedResource;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.RevisionPreconditionParser;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

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
  @Operation(summary = "Make artifact open", description = "Expose an artifact through OpenView. Use the ETag "
      + "returned by the artifact's /details endpoint.", tags = {"Command", "OpenView"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "Id of the artifact to make open", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation",
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response makeArtifactOpen() throws CedarException {
    return changeArtifactOpenState(true);
  }

  @POST
  @Timed
  @Path("/make-artifact-not-open")
  @Operation(summary = "Make artifact not open", description = "Remove an artifact from OpenView. Use the ETag "
      + "returned by the artifact's /details endpoint.", tags = {"Command", "OpenView"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "Id of the artifact to make not open", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation",
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response makeArtifactNotOpen() throws CedarException {
    return changeArtifactOpenState(false);
  }

  @POST
  @Timed
  @Path("/make-folder-open")
  @Operation(summary = "Make folder open", description = "Expose a folder through OpenView. Use the ETag "
      + "returned by GET /folders/{id}.", tags = {"Command", "OpenView"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "Id of the folder to make open", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation",
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response makeFolderOpen() throws CedarException {
    return changeFolderOpenState(true);
  }

  @POST
  @Timed
  @Path("/make-folder-not-open")
  @Operation(summary = "Make folder not open", description = "Remove a folder from OpenView. Use the ETag "
      + "returned by GET /folders/{id}.", tags = {"Command", "OpenView"},
      parameters = @Parameter(ref = "#/components/parameters/IfMatch"))
  @RequestBody(description = "Id of the folder to make not open", required = true, content = @Content(schema = @Schema(implementation = org.metadatacenter.cedar.resource.resources.swaggermodel.IdRequest.class)))
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation",
          headers = @Header(name = "ETag", ref = "#/components/headers/ETag")),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "412", ref = "#/components/responses/PreconditionFailed"),
      @ApiResponse(responseCode = "428", ref = "#/components/responses/PreconditionRequired"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response makeFolderNotOpen() throws CedarException {
    return changeFolderOpenState(false);
  }

  private Response changeArtifactOpenState(boolean open) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarRequestBody requestBody = c.request().getRequestBody();
    CedarParameter idParam = requestBody.get("@id");
    c.must(idParam).be(NonEmpty);
    CedarUntypedArtifactId artifactId = CedarUntypedArtifactId.build(idParam.stringValue());
    userMustHaveWriteAccessToArtifact(c, artifactId);

    RevisionPrecondition precondition = requirePrecondition(c);
    if (precondition == null) {
      return preconditionRequired(artifactId.getId());
    }
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    try {
      VersionedResource<FolderServerArtifact> updated = open
          ? folderSession.setOpen(artifactId, precondition)
          : folderSession.setNotOpen(artifactId, precondition);
      if (updated == null) {
        return CedarResponse.notFound().id(artifactId).errorMessage("The artifact can not be found by id").build();
      }
      return Response.ok().header(HttpHeaders.ETAG, RevisionPreconditionParser.format(updated.revision()))
          .entity(updated.resource()).build();
    } catch (RevisionConflictException e) {
      return preconditionFailed(e);
    }
  }

  private Response changeFolderOpenState(boolean open) throws CedarException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    CedarParameter idParam = c.request().getRequestBody().get("@id");
    c.must(idParam).be(NonEmpty);
    CedarFolderId folderId = CedarFolderId.build(idParam.stringValue());

    userMustHaveWriteAccessToFolder(c, folderId);

    RevisionPrecondition precondition = requirePrecondition(c);
    if (precondition == null) {
      return preconditionRequired(folderId.getId());
    }
    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    try {
      VersionedResource<FolderServerFolder> updated = open
          ? folderSession.setOpen(folderId, precondition)
          : folderSession.setNotOpen(folderId, precondition);
      if (updated == null) {
        return CedarResponse.notFound().id(folderId).errorMessage("The folder can not be found by id").build();
      }
      return Response.ok().header(HttpHeaders.ETAG, RevisionPreconditionParser.format(updated.revision()))
          .entity(updated.resource()).build();
    } catch (RevisionConflictException e) {
      return preconditionFailed(e);
    }
  }

  private RevisionPrecondition requirePrecondition(CedarRequestContext c) {
    String ifMatch = c.getIfMatchHeader();
    return ifMatch == null || ifMatch.isBlank() ? null : RevisionPreconditionParser.parse(ifMatch);
  }

  private Response preconditionRequired(String id) {
    return CedarResponse.status(CedarResponseStatus.PRECONDITION_REQUIRED)
        .id(id)
        .errorMessage("Changing OpenView visibility requires the details ETag in If-Match")
        .build();
  }

  private Response preconditionFailed(RevisionConflictException e) {
    return CedarResponse.status(CedarResponseStatus.PRECONDITION_FAILED)
        .parameter("currentETag", RevisionPreconditionParser.format(e.getCurrentRevision()))
        .errorMessage("The resource's OpenView state has changed since it was read")
        .build();
  }

}
