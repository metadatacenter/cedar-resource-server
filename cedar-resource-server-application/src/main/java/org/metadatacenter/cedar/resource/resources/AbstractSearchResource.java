package org.metadatacenter.cedar.resource.resources;

import org.apache.http.HttpResponse;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListQueryTypeDetector;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.security.model.user.ResourcePublicationStatusFilter;
import org.metadatacenter.server.security.model.user.ResourceVersionFilter;
import org.metadatacenter.util.http.CedarURIBuilder;
import org.metadatacenter.util.http.PagedSortedTypedSearchQuery;
import org.metadatacenter.util.http.ProxyUtil;

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
                         @QueryParam(QP_ID) Optional<String> id,
                         @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
                         @QueryParam(QP_VERSION) Optional<String> versionParam,
                         @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
                         @QueryParam(QP_IS_BASED_ON) Optional<String> isBasedOnParam,
                         @QueryParam(QP_SORT) Optional<String> sortParam,
                         @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                         @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
                         @QueryParam(QP_SHARING) Optional<String> sharing,
                         boolean searchDeep) throws CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    NodeListQueryType nlqt = NodeListQueryTypeDetector.detect(q, id, isBasedOnParam, sharing);

    if (nlqt == NodeListQueryType.VIEW_SHARED_WITH_ME || nlqt == NodeListQueryType.VIEW_ALL || nlqt ==
        NodeListQueryType.SEARCH_ID) {
      CedarURIBuilder builder = new CedarURIBuilder(uriInfo)
          .queryParam(QP_Q, q)
          .queryParam(QP_ID, id)
          .queryParam(QP_RESOURCE_TYPES, resourceTypes)
          .queryParam(QP_VERSION, versionParam)
          .queryParam(QP_PUBLICATION_STATUS, publicationStatusParam)
          .queryParam(QP_IS_BASED_ON, isBasedOnParam)
          .queryParam(QP_SORT, sortParam)
          .queryParam(QP_LIMIT, limitParam)
          .queryParam(QP_OFFSET, offsetParam)
          .queryParam(QP_SHARING, sharing);

      String url = builder.getProxyUrl(microserviceUrlUtil.getWorkspace().getBase());

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      return deserializeAndAddProvenanceDisplayNames(proxyResponse, c);
    } else {
      PagedSortedTypedSearchQuery pagedSearchQuery = new PagedSortedTypedSearchQuery(
          cedarConfig.getFolderRESTAPI().getPagination())
          .q(q)
          .id(id)
          .resourceTypes(resourceTypes)
          .version(versionParam)
          .publicationStatus(publicationStatusParam)
          .isBasedOn(isBasedOnParam)
          .sort(sortParam)
          .limit(limitParam)
          .offset(offsetParam);
      pagedSearchQuery.validate();

      try {
        String isBasedOn = pagedSearchQuery.getIsBasedOn();
        List<String> resourceTypeList = pagedSearchQuery.getNodeTypeAsStringList();
        ResourceVersionFilter version = pagedSearchQuery.getVersion();
        ResourcePublicationStatusFilter publicationStatus = pagedSearchQuery.getPublicationStatus();
        List<String> sortList = pagedSearchQuery.getSortList();
        String queryString = pagedSearchQuery.getQ();
        String idString = pagedSearchQuery.getId();
        int limit = pagedSearchQuery.getLimit();
        int offset = pagedSearchQuery.getOffset();

        CedarURIBuilder builder = new CedarURIBuilder(uriInfo)
            .queryParam(QP_Q, q)
            .queryParam(QP_ID, id)
            .queryParam(QP_RESOURCE_TYPES, resourceTypes)
            .queryParam(QP_VERSION, versionParam)
            .queryParam(QP_PUBLICATION_STATUS, publicationStatusParam)
            .queryParam(QP_IS_BASED_ON, isBasedOnParam)
            .queryParam(QP_SORT, sortParam)
            .queryParam(QP_LIMIT, limitParam)
            .queryParam(QP_OFFSET, offsetParam)
            .queryParam(QP_SHARING, sharing);

        String absoluteUrl = builder.build().toString();

        FolderServerNodeListResponse results = null;
        if (searchDeep) {
          results = contentSearchingService.searchDeep(c, queryString, idString, resourceTypeList, version,
              publicationStatus, isBasedOn, sortList, limit, offset, absoluteUrl);
        } else {
          results = contentSearchingService.search(c, queryString, idString, resourceTypeList, version,
              publicationStatus, isBasedOn, sortList, limit, offset, absoluteUrl);
        }
        results.setNodeListQueryType(nlqt);

        addProvenanceDisplayNames(results, c);
        return Response.ok().entity(results).build();
      } catch (Exception e) {
        throw new CedarProcessingException(e);
      }
    }
  }
}