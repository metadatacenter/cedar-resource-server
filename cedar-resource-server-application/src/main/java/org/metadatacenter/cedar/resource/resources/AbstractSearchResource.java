package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.http.HttpResponse;
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
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

public class AbstractSearchResource extends AbstractResourceServerResource {

  public AbstractSearchResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public Response search(@QueryParam(QP_Q) Optional<String> q,
                         @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
                         @QueryParam(QP_DERIVED_FROM_ID) Optional<String> derivedFromIdParam,
                         @QueryParam(QP_SORT) Optional<String> sortParam,
                         @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                         @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
                         @QueryParam(QP_SHARING) Optional<String> sharing,
                         boolean searchDeep) throws CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    NodeListQueryType nlqt = NodeListQueryTypeDetector.detect(q, derivedFromIdParam, sharing);

    if (nlqt == NodeListQueryType.VIEW_SHARED_WITH_ME || nlqt == NodeListQueryType.VIEW_ALL) {
      CedarURIBuilder builder = new CedarURIBuilder(uriInfo)
          .queryParam(QP_Q, q)
          .queryParam(QP_RESOURCE_TYPES, resourceTypes)
          .queryParam(QP_DERIVED_FROM_ID, derivedFromIdParam)
          .queryParam(QP_SORT, sortParam)
          .queryParam(QP_LIMIT, limitParam)
          .queryParam(QP_OFFSET, offsetParam)
          .queryParam(QP_SHARING, sharing);

      String url = builder.getProxyUrl(microserviceUrlUtil.getWorkspace().getBase());

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      return deserializeAndConvertFolderNamesIfNecessary(proxyResponse);
    } else {
      PagedSortedTypedSearchQuery pagedSearchQuery = new PagedSortedTypedSearchQuery(
          cedarConfig.getFolderRESTAPI().getPagination())
          .q(q)
          .resourceTypes(resourceTypes)
          .derivedFromId(derivedFromIdParam)
          .sort(sortParam)
          .limit(limitParam)
          .offset(offsetParam);
      pagedSearchQuery.validate();

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
            .queryParam(QP_DERIVED_FROM_ID, derivedFromIdParam)
            .queryParam(QP_SORT, sortParam)
            .queryParam(QP_LIMIT, limitParam)
            .queryParam(QP_OFFSET, offsetParam)
            .queryParam(QP_SHARING, sharing);

        String absoluteUrl = builder.build().toString();

        FolderServerNodeListResponse results = null;
        if (searchDeep) {
          results = contentSearchingService.searchDeep(c, queryString, resourceTypeList, templateId, sortList, limit,
              offset, absoluteUrl);
        } else {
          results = contentSearchingService.search(c, queryString, resourceTypeList, templateId, sortList, limit,
              offset, absoluteUrl);
        }
        results.setNodeListQueryType(nlqt);

        JsonNode resultsNode = JsonMapper.MAPPER.valueToTree(results);
        return Response.ok().entity(resultsNode).build();
      } catch (Exception e) {
        throw new CedarProcessingException(e);
      }
    }
  }
}