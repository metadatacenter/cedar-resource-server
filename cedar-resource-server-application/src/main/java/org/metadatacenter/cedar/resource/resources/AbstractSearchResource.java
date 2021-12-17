package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.id.CedarTemplateId;
import org.metadatacenter.id.CedarUntypedArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListQueryTypeDetector;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.security.model.user.ResourcePublicationStatusFilter;
import org.metadatacenter.server.security.model.user.ResourceVersionFilter;
import org.metadatacenter.util.TrustedByUtil;
import org.metadatacenter.util.http.CedarURIBuilder;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedSortedTypedSearchQuery;

import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
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
                         @QueryParam(QP_SHARING) Optional<String> sharingParam,
                         @QueryParam(QP_MODE) Optional<String> modeParam,
                         @QueryParam(QP_CATEGORY_ID) Optional<String> categoryIdParam,
                         boolean searchDeep) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    NodeListQueryType nlqt = NodeListQueryTypeDetector.detect(q, id, isBasedOnParam, sharingParam, modeParam, categoryIdParam);


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
        .queryParam(QP_SHARING, sharingParam)
        .queryParam(QP_MODE, modeParam)
        .queryParam(QP_CATEGORY_ID, categoryIdParam);

    PagedSortedTypedSearchQuery pagedSearchQuery = new PagedSortedTypedSearchQuery(
        cedarConfig.getResourceRESTAPI().getPagination())
        .q(q)
        .id(id)
        .resourceTypes(resourceTypes)
        .version(versionParam)
        .publicationStatus(publicationStatusParam)
        .isBasedOn(isBasedOnParam)
        .categoryId(categoryIdParam)
        .mode(modeParam)
        .sort(sortParam)
        .limit(limitParam)
        .offset(offsetParam);
    pagedSearchQuery.validate();

    int limit = pagedSearchQuery.getLimit();
    int offset = pagedSearchQuery.getOffset();
    String queryString = pagedSearchQuery.getQ();
    String idString = pagedSearchQuery.getId();
    ResourceVersionFilter version = pagedSearchQuery.getVersion();
    ResourcePublicationStatusFilter publicationStatus = pagedSearchQuery.getPublicationStatus();
    List<String> sortList = pagedSearchQuery.getSortList();
    String isBasedOn = pagedSearchQuery.getIsBasedOn();
    String categoryId = pagedSearchQuery.getCategoryId();
    String mode = pagedSearchQuery.getMode();

    FolderServerNodeListResponse r;
    String absoluteUrl = builder.build().toString();

    if (nlqt == NodeListQueryType.VIEW_SHARED_WITH_ME || nlqt == NodeListQueryType.VIEW_SHARED_WITH_EVERYBODY ||
        nlqt == NodeListQueryType.VIEW_ALL || nlqt == NodeListQueryType.SEARCH_ID ||
        nlqt == NodeListQueryType.SEARCH_IS_BASED_ON || nlqt == NodeListQueryType.VIEW_SPECIAL_FOLDERS) {

      r = performGraphDbSearch(c, pagedSearchQuery, nlqt, queryString, idString, version, publicationStatus, isBasedOn, mode, sortList, limit, offset);

    } else {
      List<String> resourceTypeList = pagedSearchQuery.getResourceTypeAsStringList();
      // If sortParam was empty, set sortList to empty too instead of using the default sorting applied by the validator, to keep ElasticSearch-generated ranking
      if (sortParam.isEmpty()) {
        sortList = new ArrayList<>();
      }
      if (searchDeep) {
        r = nodeSearchingService
            .searchDeep(c, queryString, idString, resourceTypeList, version, publicationStatus, categoryId, sortList, limit, offset, absoluteUrl);
      } else {
        r = nodeSearchingService
            .search(c, queryString, idString, resourceTypeList, version, publicationStatus, categoryId, sortList, limit, offset, absoluteUrl);
      }
    }
    r.setNodeListQueryType(nlqt);
    r.setPaging(LinkHeaderUtil.getPagingLinkHeaders(absoluteUrl, r.getTotalCount(), limit, offset));
    ProvenanceNameUtil.addProvenanceDisplayNames(r);
    return Response.ok().entity(r).build();
  }

  private FolderServerNodeListResponse performGraphDbSearch(CedarRequestContext c,
                                                            PagedSortedTypedSearchQuery pagedSearchQuery,
                                                            NodeListQueryType nlqt,
                                                            String q,
                                                            String id,
                                                            ResourceVersionFilter version,
                                                            ResourcePublicationStatusFilter publicationStatus,
                                                            String isBasedOn,
                                                            String mode,
                                                            List<String> sortList,
                                                            int limit,
                                                            int offset) throws CedarException {
    List<CedarResourceType> resourceTypeList = pagedSearchQuery.getResourceTypeList();

    FolderServerNodeListResponse r = new FolderServerNodeListResponse();

    NodeListRequest req = new NodeListRequest();
    req.setResourceTypes(resourceTypeList);
    req.setVersion(version);
    req.setPublicationStatus(publicationStatus);
    req.setLimit(limit);
    req.setOffset(offset);
    req.setSort(sortList);
    req.setQ(q);
    req.setId(id);
    req.setIsBasedOn(isBasedOn);
    req.setMode(mode);

    r.setRequest(req);

    r.setNodeListQueryType(nlqt);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

    List<FolderServerResourceExtract> resources;
    long total;

    if (nlqt == NodeListQueryType.VIEW_SHARED_WITH_ME) {
      resources = folderSession.viewSharedWithMe(resourceTypeList, version, publicationStatus, limit, offset, sortList);
      total = folderSession.viewSharedWithMeCount(resourceTypeList, version, publicationStatus);
    } else if (nlqt == NodeListQueryType.VIEW_SHARED_WITH_EVERYBODY) {
      resources = folderSession.viewSharedWithEverybody(resourceTypeList, version, publicationStatus, limit, offset, sortList);
      total = folderSession.viewSharedWithEverybodyCount(resourceTypeList, version, publicationStatus);
    } else if (nlqt == NodeListQueryType.VIEW_ALL) {
      resources = folderSession.viewAll(resourceTypeList, version, publicationStatus, limit, offset, sortList);
      total = folderSession.viewAllCount(resourceTypeList, version, publicationStatus);
    } else if (nlqt == NodeListQueryType.VIEW_SPECIAL_FOLDERS) {
      resources = folderSession.viewSpecialFolders(limit, offset, sortList);
      total = folderSession.viewSpecialFoldersCount();
    } else if (nlqt == NodeListQueryType.SEARCH_IS_BASED_ON) {
      resources = folderSession.searchIsBasedOn(resourceTypeList, CedarTemplateId.build(req.getIsBasedOn()), limit, offset, sortList);
      total = folderSession.searchIsBasedOnCount(resourceTypeList, CedarTemplateId.build(req.getIsBasedOn()));
    } else if (nlqt == NodeListQueryType.SEARCH_ID) {
      resources = new ArrayList<>();
      FolderServerArtifact resourceById = folderSession.findArtifactById(CedarUntypedArtifactId.build(id));
      if (resourceById != null) {
        resources.add(FolderServerResourceExtract.fromNode(resourceById));
      } else {
        FolderServerFolder folderById = folderSession.findFolderById(CedarFolderId.build(id));
        if (folderById != null) {
          resources.add(FolderServerResourceExtract.fromNode(folderById));
        }
      }
      total = resources.size();
    } else {
      throw new CedarProcessingException("Search type not supported!")
          .parameter("resolvedSearchType", nlqt.getValue());
    }

    // Add "trustedBy" information to artifacts. An alternative that would provide better performance would be to
    // get the parentFolderId directly from Neo4j, instead of executing this extra loop to add it at this level.
    for (FolderServerResourceExtract resourceExtract : resources) {
      if (!resourceExtract.getType().equals(CedarResourceType.FOLDER)) {
        FolderServerArtifact artifact = folderSession.findArtifactById(CedarUntypedArtifactId.build(resourceExtract.getId()));
        List<FolderServerResourceExtract> pathInfo = PathInfoBuilder.getResourcePathExtract(c, folderSession, permissionSession, artifact);
        TrustedByUtil.decorateWithTrustedby(resourceExtract, pathInfo, cedarConfig.getTrustedFolders().getFoldersMap());
      }
    }

    r.setTotalCount(total);
    r.setCurrentOffset(offset);

    r.setResources(resources);

    return r;
  }
}
