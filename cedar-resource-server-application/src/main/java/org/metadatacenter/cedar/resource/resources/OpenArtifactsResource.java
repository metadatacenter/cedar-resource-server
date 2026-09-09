package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.cedar.util.dw.AnonymousAccess;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarDependencyUnavailableException;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.util.http.ArtifactServiceClient;
import org.metadatacenter.util.http.CedarError;
import org.metadatacenter.util.http.CedarResponse;

import java.io.IOException;

/** Resource owns the anonymous artifact decision; OpenView is only a compatibility adapter. */
@Path("/open")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Open artifacts")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "The artifact and its ancestors are private",
        content = @Content(schema = @Schema(implementation = CedarError.class))),
    @ApiResponse(responseCode = "404", description = "The artifact does not exist",
        content = @Content(schema = @Schema(implementation = CedarError.class))),
    @ApiResponse(responseCode = "503", description = "A required backend is unavailable",
        content = @Content(schema = @Schema(implementation = CedarError.class)))
})
public class OpenArtifactsResource extends CedarMicroserviceResource {
  public OpenArtifactsResource(CedarConfig config) {
    super(config);
  }

  @GET @Timed @Path("/templates/{id}")
  @Operation(summary = "Read an open template anonymously", description = "User credentials never broaden access.")
  @ApiResponse(responseCode = "200", description = "The open artifact as JSON",
      content = @Content(schema = @Schema(implementation = org.metadatacenter.util.artifact.SchemaArtifactDocument.class)))
  public Response template(@PathParam("id") String id) throws CedarException {
    return readOpen(id, CedarResourceType.TEMPLATE);
  }

  @GET @Timed @Path("/template-elements/{id}")
  @Operation(summary = "Read an open element anonymously", description = "User credentials never broaden access.")
  @ApiResponse(responseCode = "200", description = "The open artifact as JSON",
      content = @Content(schema = @Schema(implementation = org.metadatacenter.util.artifact.SchemaArtifactDocument.class)))
  public Response element(@PathParam("id") String id) throws CedarException {
    return readOpen(id, CedarResourceType.ELEMENT);
  }

  @GET @Timed @Path("/template-fields/{id}")
  @Operation(summary = "Read an open field anonymously", description = "User credentials never broaden access.")
  @ApiResponse(responseCode = "200", description = "The open artifact as JSON",
      content = @Content(schema = @Schema(implementation = org.metadatacenter.util.artifact.SchemaArtifactDocument.class)))
  public Response field(@PathParam("id") String id) throws CedarException {
    return readOpen(id, CedarResourceType.FIELD);
  }

  @GET @Timed @Path("/template-instances/{id}")
  @Operation(summary = "Read an open instance anonymously", description = "User credentials never broaden access.")
  @ApiResponse(responseCode = "200", description = "The open artifact as JSON",
      content = @Content(schema = @Schema(implementation = org.metadatacenter.util.artifact.InstanceArtifactDocument.class)))
  public Response instance(@PathParam("id") String id) throws CedarException {
    return readOpen(id, CedarResourceType.INSTANCE);
  }

  @AnonymousAccess
  private Response readOpen(String id, CedarResourceType type) throws CedarException {
    var anonymous = buildAnonymousRequestContext();
    String resolved = id.contains("://") ? id : linkedDataUtil.getLinkedDataId(type, id);
    CedarArtifactId artifactId = CedarArtifactId.build(resolved, type);
    FolderServiceSession folders = dataServices.getFolderServiceSession(anonymous);
    FolderServerArtifact artifact = folders.findArtifactById(artifactId);
    if (artifact == null) return CedarResponse.notFound().id(artifactId).build();
    if (!artifact.isOpen() && !folders.isArtifactOpenImplicitly(artifactId)) {
      return CedarResponse.unauthorized().id(artifactId).build();
    }

    // Only after openness is proved: artifact still requires both the internal service key and a
    // backend user identity. Never substitute the HTTP caller's credential on this anonymous path.
    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(type, artifactId);
    try (ClassicHttpResponse upstream = new ArtifactServiceClient(cedarConfig).get(url,
        CedarRequestContextFactory.fromAdminUser(cedarConfig, dataServices.getNeoUserService()))) {
      Response.ResponseBuilder result = Response.status(upstream.getCode()).type(MediaType.APPLICATION_JSON);
      if (upstream.getEntity() != null) result.entity(EntityUtils.toByteArray(upstream.getEntity()));
      // Openness can be revoked independently of the document revision; do not serve cached bodies.
      return result.header("Cache-Control", "no-store").build();
    } catch (IOException e) {
      throw new CedarDependencyUnavailableException("Downstream service is unavailable", e);
    }
  }
}
