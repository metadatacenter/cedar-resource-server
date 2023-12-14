package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.apache.http.HttpStatus;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.id.CedarUntypedSchemaArtifactId;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphRequest;
import org.metadatacenter.model.request.inclusionsubgraph.InclusionSubgraphResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.InclusionSubgraphServiceSession;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.search.util.InclusionSubgraphUtil;
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
public class CommandInclusionSubgraphResource extends AbstractResourceServerResource {

  public CommandInclusionSubgraphResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/inclusions-subgraph-preview")
  public Response previewInclusionSubgraph() throws CedarException, IOException {
    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    InclusionSubgraphRequest treeRequest = JsonMapper.MAPPER.readValue(c.request().getRequestBody().asJsonString(), InclusionSubgraphRequest.class);

    String id = treeRequest.getId();
    CedarUntypedSchemaArtifactId aid = CedarUntypedSchemaArtifactId.build(id);

    userMustHaveReadAccessToArtifact(c, aid);

    InclusionSubgraphServiceSession inclusionSubgraphSession = CedarDataServices.getInclusionSubgraphServiceSession(c);

    InclusionSubgraphResponse treeResponse = InclusionSubgraphUtil.buildAffectedTree(treeRequest, inclusionSubgraphSession);

    ProvenanceNameUtil.addProvenanceDisplayNames(treeResponse);

    return Response.ok(treeResponse).build();
  }


}
