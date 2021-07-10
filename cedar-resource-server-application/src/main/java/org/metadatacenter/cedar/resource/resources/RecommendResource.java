package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.request.RecommendTemplatesRequest;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;

import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class RecommendResource extends AbstractSearchResource {

  public RecommendResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @POST
  @Timed
  @Path("/recommend-templates")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response recommendTemplates(@Valid RecommendTemplatesRequest body) throws CedarException {

    // TODO:
    //  - returns internal error when body is empty
    //  - error when the field name contains /

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    Iterator<String> itFieldNames = body.getMetadataRecord().fieldNames();
    List<String> fieldNames = new ArrayList<>();
    while (itFieldNames.hasNext()) {
      fieldNames.add(itFieldNames.next());
    }

    // Step 1: Basic search to retrieve templates by field name
    FolderServerNodeListResponse searchResponse = searchArtifactsByFieldNames(fieldNames,
        CedarResourceType.Types.TEMPLATE);

    // Step 2. Analysis of results and generation of recommmendations

    //r.setPaging(LinkHeaderUtil.getPagingLinkHeaders(absoluteUrl, r.getTotalCount(), limit, offset));
    //ProvenanceNameUtil.addProvenanceDisplayNames(r);
    return Response.ok().entity(searchResponse).build();
  }

  private FolderServerNodeListResponse searchArtifactsByFieldNames(List<String> fieldNames,
                                                                   String resourceTypes) throws CedarException {

    StringBuilder queryBuilder = new StringBuilder();
    for (int i = 0; i < fieldNames.size() - 1; i++) {
      queryBuilder.append("\"").append(fieldNames.get(i)).append("\"").append(": OR ");
    }
    queryBuilder.append("\"").append(fieldNames.get(fieldNames.size() - 1)).append("\"").append(":");

    Response r = super.search(Optional.of(queryBuilder.toString()), Optional.empty(), Optional.of(resourceTypes),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), false);

    return (FolderServerNodeListResponse) r.getEntity();

  }

}

