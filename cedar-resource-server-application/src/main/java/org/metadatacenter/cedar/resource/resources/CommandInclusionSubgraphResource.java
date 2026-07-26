package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.artifact.ArtifactServerUtil;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarTypedSchemaArtifactId;
import org.metadatacenter.id.CedarUntypedSchemaArtifactId;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphRequest;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphResponse;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphTodoElement;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphTodoList;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.InclusionSubgraphServiceSession;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.search.util.InclusionSubgraphUtil;
import org.metadatacenter.util.CedarResourceTypeUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.json.JsonMapper;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Command")
@SecurityRequirement(name = "api_key")
public class CommandInclusionSubgraphResource extends AbstractResourceServerResource {

  public CommandInclusionSubgraphResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/inclusions-subgraph-preview")
  @Operation(summary = "Preview the inclusion subgraph of an artifact", description = "Build a preview of the tree of artifacts affected by a change to the given artifact, without "
          + "applying any update.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response previewInclusionSubgraph() throws CedarException, IOException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    InclusionSubgraphRequest treeRequest = JsonMapper.MAPPER.readValue(c.request().getRequestBody().asJsonString(), InclusionSubgraphRequest.class);

    String id = treeRequest.getId();
    if (id == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .errorMessage("@id not provided for the inclusion subgraph request")
          .build();
    }
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);

    userMustHaveReadAccessToArtifact(c, aid);

    InclusionSubgraphServiceSession inclusionSubgraphSession = CedarDataServices.getInclusionSubgraphServiceSession(c);

    InclusionSubgraphResponse treeResponse = InclusionSubgraphUtil.buildAffectedTree(treeRequest, inclusionSubgraphSession);

    ProvenanceNameUtil.addProvenanceDisplayNames(treeResponse);

    return Response.ok(treeResponse).build();
  }

  @POST
  @Timed
  @Path("/inclusions-subgraph-update")
  @Operation(summary = "Update the inclusion subgraph of an artifact", description = "Propagate a change to the given artifact across the tree of affected artifacts, updating each "
          + "referencing artifact on the artifact server.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response updateInclusionSubgraph() throws CedarException, IOException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    InclusionSubgraphRequest treeRequest = JsonMapper.MAPPER.readValue(c.request().getRequestBody().asJsonString(), InclusionSubgraphRequest.class);

    String id = treeRequest.getId();
    if (id == null) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .errorMessage("@id not provided for the inclusion subgraph request")
          .build();
    }
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);

    userMustHaveReadAccessToArtifact(c, aid);

    InclusionSubgraphServiceSession inclusionSubgraphSession = CedarDataServices.getInclusionSubgraphServiceSession(c);

    InclusionSubgraphResponse treeResponse = InclusionSubgraphUtil.buildAffectedTree(treeRequest, inclusionSubgraphSession);

    InclusionSubgraphTodoList todoList = InclusionSubgraphUtil.updateResources(treeResponse);

    for (InclusionSubgraphTodoElement todo : todoList.getTodoList()) {
      CedarTypedSchemaArtifactId sourceArtifactId = CedarResourceTypeUtil.buildTypedArtifactId(todo.getSourceId());
      CedarTypedSchemaArtifactId targetArtifactId = CedarResourceTypeUtil.buildTypedArtifactId(todo.getTargetId());

      String sourceArtifact = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(sourceArtifactId.getType(), sourceArtifactId, c, microserviceUrlUtil, null);
      String targetArtifact = ArtifactServerUtil.getSchemaArtifactFromArtifactServer(targetArtifactId.getType(), targetArtifactId, c, microserviceUrlUtil, null);
      JsonNode sourceJsonNode = JsonMapper.MAPPER.readTree(sourceArtifact);
      JsonNode targetJsonNode = JsonMapper.MAPPER.readTree(targetArtifact);
      boolean replaced = InclusionSubgraphUtil.updateSubdocumentByAtId(targetJsonNode, todo.getSourceId(), sourceJsonNode);
      String newTargetContent = JsonMapper.MAPPER.writeValueAsString(targetJsonNode);

      Response putResponse = ArtifactServerUtil.putSchemaArtifactToArtifactServer(targetArtifactId.getType(), targetArtifactId, c, newTargetContent, microserviceUrlUtil);

    }

    ProvenanceNameUtil.addProvenanceDisplayNames(treeResponse);

    return Response.ok(treeResponse).build();
  }


}
