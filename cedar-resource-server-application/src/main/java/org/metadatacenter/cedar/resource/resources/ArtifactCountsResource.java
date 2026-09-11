package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.ArtifactCounts;
import org.metadatacenter.util.http.CedarError;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.metadatacenter.exception.CedarDependencyUnavailableException;
import org.metadatacenter.util.http.ArtifactServiceClient;
import java.io.IOException;

@Path("/" + ArtifactCounts.PATH)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Monitoring")
@SecurityRequirement(name = "api_key")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Actual artifact document-store counts",
        content = @Content(schema = @Schema(implementation = ArtifactCounts.class))),
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = CedarError.class))),
    @ApiResponse(responseCode = "403", description = "The caller lacks monitor read permission",
        content = @Content(schema = @Schema(implementation = CedarError.class))),
    @ApiResponse(responseCode = "503", description = "Artifact counts are unavailable",
        content = @Content(schema = @Schema(implementation = CedarError.class)))
})
public class ArtifactCountsResource extends CedarMicroserviceResource {
  public ArtifactCountsResource(CedarConfig config) {
    super(config);
  }

  @GET @Timed
  @Operation(summary = "Count artifact documents", description = "Requires MONITOR_READ. Counts stored documents, including any absent from the workspace graph.")
  public Response counts() throws CedarException {
    var c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);
    c.must(c.user()).have(CedarPermission.MONITOR_READ);
    String url = cedarConfig.getServers().getArtifact().getBase() + ArtifactCounts.PATH;
    try (ClassicHttpResponse upstream = new ArtifactServiceClient(cedarConfig).get(url, c)) {
      return Response.ok(ArtifactCounts.read(upstream)).header("Cache-Control", "no-store").build();
    } catch (IOException e) {
      throw new CedarDependencyUnavailableException("Artifact counts are unavailable", e);
    }
  }
}
