package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
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

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/command")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/command", tags = "Command", authorizations = {@Authorization("api_key")})
public class CommandInclusionSubgraphResource extends AbstractResourceServerResource {

  public CommandInclusionSubgraphResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/inclusions-subgraph-preview")
  @ApiOperation(value = "Preview the inclusion subgraph of an artifact",
      notes = "Build a preview of the tree of artifacts affected by a change to the given artifact, without "
          + "applying any update.")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
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
  @ApiOperation(value = "Update the inclusion subgraph of an artifact",
      notes = "Propagate a change to the given artifact across the tree of affected artifacts, updating each "
          + "referencing artifact on the artifact server.")
  @ApiResponses({
      @ApiResponse(code = 200, message = "Successful operation"),
      @ApiResponse(code = 400, message = "Bad request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 403, message = "Forbidden"),
      @ApiResponse(code = 404, message = "Not found"),
      @ApiResponse(code = 500, message = "Internal server error")
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
