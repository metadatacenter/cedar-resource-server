package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListQueryTypeDetector;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.util.http.CedarURIBuilder;
import org.metadatacenter.util.http.PagedSortedTypedSearchQuery;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SearchDeepResource extends AbstractResourceServerResource {

  public SearchDeepResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/search-deep")
  public Response searchDeep(@QueryParam(QP_Q) Optional<String> q,
                             @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
                             @QueryParam(QP_DERIVED_FROM_ID) Optional<String> derivedFromId,
                             @QueryParam(QP_SORT) Optional<String> sortParam,
                             @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                             @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
                             @QueryParam(QP_SHARING) Optional<String> sharing) throws CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    PagedSortedTypedSearchQuery pagedSearchQuery = new PagedSortedTypedSearchQuery(cedarConfig.getFolderRESTAPI()
        .getPagination())
        .q(q)
        .resourceTypes(resourceTypes)
        .derivedFromId(derivedFromId)
        .sort(sortParam)
        .limit(limitParam)
        .offset(offsetParam);
    pagedSearchQuery.validate();

    NodeListQueryType nlqt = NodeListQueryTypeDetector.detect(q, derivedFromId, sharing);

    try {
      String templateId = pagedSearchQuery.getDerivedFromId();
      List<String> resourceTypeList = pagedSearchQuery.getNodeTypeAsStringList();
      List<String> sortList = pagedSearchQuery.getSortList();
      String queryString = pagedSearchQuery.getQ();
      int limit = pagedSearchQuery.getLimit();
      int offset = pagedSearchQuery.getOffset();

      CedarURIBuilder builder = new CedarURIBuilder(uriInfo)
          .queryParam(QP_Q, q)
          .queryParam(QP_RESOURCE_TYPES, resourceTypes)
          .queryParam(QP_DERIVED_FROM_ID, derivedFromId)
          .queryParam(QP_SORT, sortParam)
          .queryParam(QP_LIMIT, limitParam)
          .queryParam(QP_OFFSET, offsetParam)
          .queryParam(QP_SHARING, sharing);

      String absoluteUrl = builder.build().toString();

      FolderServerNodeListResponse results = contentSearchingService.searchDeep(c, queryString, resourceTypeList,
          templateId, sortList, limit, offset, absoluteUrl);
      results.setNodeListQueryType(nlqt);

      JsonNode resultsNode = JsonMapper.MAPPER.valueToTree(results);
      return Response.ok().entity(resultsNode).build();
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }
}