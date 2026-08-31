package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.bridge.CedarDataServices;
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
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.server.search.elasticsearch.service.DeepSearchPageResponse;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.user.ResourcePublicationStatusFilter;
import org.metadatacenter.server.security.model.user.ResourceVersionFilter;
import org.metadatacenter.util.TrustedByUtil;
import org.metadatacenter.util.http.CedarURIBuilder;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedSortedTypedSearchQuery;
import org.metadatacenter.util.http.SearchContinuation;

import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.metadatacenter.constant.CedarQueryParameters.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

public abstract class AbstractSearchResource extends AbstractResourceServerResource {

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
                         @QueryParam(QP_CONTINUATION) Optional<String> continuationParam,
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
        .queryParam(QP_CATEGORY_ID, categoryIdParam)
        .queryParam(QP_CONTINUATION, continuationParam);

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

    if (continuationParam.isPresent()) {
      if (!searchDeep) {
        throw new CedarAssertionException("A continuation is only served by /search-deep!")
            .parameter(QP_CONTINUATION, continuationParam.get())
            .badRequest();
      }
      if (offsetParam.isPresent()) {
        // One says where to carry on from and the other says how far in to start. A request holding
        // both is asking for two different pages.
        throw new CedarAssertionException("Pass a continuation or an offset, not both!")
            .parameter(QP_OFFSET, offsetParam.get())
            .badRequest();
      }
    }

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
      // Only this branch reads the search index, and the three ways into it serve different depths.
      // The graph-backed branch above pages with SKIP and is bounded by none of them.
      if (continuationParam.isPresent()) {
        return continuationPage(c, pagedSearchQuery, continuationParam.get(), queryString, idString, resourceTypeList,
            version, publicationStatus, categoryId, sortList, limit, absoluteUrl, nlqt);
      }
      if (searchDeep) {
        pagedSearchQuery.validateDeepOffset();
        r = nodeSearchingService
            .searchDeep(c, queryString, idString, resourceTypeList, version, publicationStatus, categoryId, sortList, limit, offset, absoluteUrl);
      } else {
        pagedSearchQuery.validateShallowWindow(cedarConfig.getElasticsearchConfig().getMaxResultWindow());
        r = nodeSearchingService
            .search(c, queryString, idString, resourceTypeList, version, publicationStatus, categoryId, sortList, limit, offset, absoluteUrl);
      }
    }
    r.setNodeListQueryType(nlqt);
    r.setPaging(LinkHeaderUtil.getPagingLinkHeaders(absoluteUrl, r.getTotalCount(), limit, offset));
    ProvenanceNameUtil.addProvenanceDisplayNames(r);
    return Response.ok().entity(r).build();
  }

  /**
   * A page of a walk the caller drives. The first page is asked for by value, every page after it by
   * the token the previous one answered with, and the walk ends when a page comes back without one.
   */
  private Response continuationPage(CedarRequestContext c, PagedSortedTypedSearchQuery pagedSearchQuery,
                                    String continuationValue, String queryString, String idString,
                                    List<String> resourceTypeList, ResourceVersionFilter version,
                                    ResourcePublicationStatusFilter publicationStatus, String categoryId,
                                    List<String> sortList, int limit, String absoluteUrl,
                                    NodeListQueryType nlqt) throws CedarException {
    String userId = c.getCedarUser().getId();
    String fingerprint = SearchContinuation.fingerprint(queryString, idString, resourceTypeList,
        pagedSearchQuery.getVersionAsString(), pagedSearchQuery.getPublicationStatusAsString(), categoryId, sortList);

    SearchContinuation current = SearchContinuation.isStart(continuationValue)
        ? null
        : SearchContinuation.decode(continuationValue, userId, fingerprint);

    DeepSearchPageResponse page = nodeSearchingService.searchDeepPage(c, queryString, idString, resourceTypeList,
        version, publicationStatus, categoryId, sortList, limit,
        current == null ? 0 : current.getRowsSeen(),
        current == null ? 0 : current.getTotalCount(),
        current == null ? null : current.getPointInTimeId(),
        current == null ? null : current.getSearchAfterValues(),
        absoluteUrl);

    FolderServerNodeListResponse r = page.response();
    String nextContinuation = null;
    if (page.hasMore()) {
      long rowsSeen = r.getCurrentOffset() + r.getResources().size();
      nextContinuation = SearchContinuation
          .of(page.pointInTimeId(), page.nextSearchAfter(), userId, fingerprint, rowsSeen, r.getTotalCount())
          .encode();
      r.setContinuation(nextContinuation);
    }
    r.setNodeListQueryType(nlqt);
    r.setPaging(LinkHeaderUtil.getContinuationLinkHeaders(absoluteUrl, limit, nextContinuation));
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

    FolderServiceSession folderSession = dataServices.getFolderServiceSession(c);
    ResourcePermissionServiceSession permissionSession = dataServices.getResourcePermissionServiceSession(c);

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
      FolderServerResourceExtract found = null;
      FolderServerArtifact resourceById = folderSession.findArtifactById(CedarUntypedArtifactId.build(id));
      if (resourceById != null) {
        found = FolderServerResourceExtract.fromNode(resourceById);
      } else {
        FolderServerFolder folderById = folderSession.findFolderById(CedarFolderId.build(id));
        if (folderById != null) {
          found = FolderServerResourceExtract.fromNode(folderById);
        }
      }
      if (found != null) {
        FolderServerResourceExtract visible = readableOrRedacted(c, permissionSession, found);
        if (visible != null) {
          resources.add(visible);
        }
      }
      total = resources.size();
    } else {
      throw new CedarProcessingException("Search type not supported!")
          .parameter("resolvedSearchType", nlqt.getValue());
    }

    // Add "trustedBy" information to artifacts. An alternative that would provide better performance would be to
    // get the parentFolderId directly from Neo4j, instead of executing this extra loop to add it at this level.
    // TODO Try to optimize more. In case of a folder (VIEW_SPECIAL_FOLDERS) the parent can be retrieved directly
    // Maybe - just maybe - storing the parent folderId on the Neo4j node and in the search index doc is not a bad idea?
    // Then it could be checked directly, without reading in the parent
    for (FolderServerResourceExtract resourceExtract : resources) {
      // A redacted entry carries its identifier and its type and nothing else. Reading its parent folder
      // to label it would report which folder holds a resource the caller may not read.
      if (resourceExtract.isActiveUserCanRead() && !resourceExtract.getType().equals(CedarResourceType.FOLDER)) {
        FolderServerFolder parentFolder = folderSession.getParentFolder(CedarUntypedArtifactId.build(resourceExtract.getId()));
        TrustedByUtil.decorateWithTrustedBy(resourceExtract, parentFolder, cedarConfig.getTrustedFolders().getFoldersMap());
      }
    }

    r.setTotalCount(total);
    r.setCurrentOffset(offset);

    r.setResources(resources);

    return r;
  }

  /**
   * The extract as it stands when the active user may read the resource, and reduced to its identifier
   * and type when they may not.
   *
   * <p>Every other search served here filters unreadable resources inside its Cypher, so one never
   * reaches this level. A lookup by identifier has nothing to filter: the caller supplied the
   * identifier, and one identifier resolves to one resource. So an unreadable resource is redacted
   * instead of dropped, which reports it as one the active user cannot read and carries none of its
   * name, description, provenance or timestamps. A resource type that cannot be redacted answers null
   * and is dropped by the caller.
   *
   * <p>{@link CedarPermission#READ_NOT_READABLE_NODE} reads everything, exactly as it turns the Cypher
   * permission conditions off for every other search served here.
   */
  private FolderServerResourceExtract readableOrRedacted(CedarRequestContext c,
                                                         ResourcePermissionServiceSession permissionSession,
                                                         FolderServerResourceExtract extract) {
    if (c.getCedarUser().has(CedarPermission.READ_NOT_READABLE_NODE)) {
      return extract;
    }
    if (permissionSession.userHasReadAccessToResource(extract.getResourceId())) {
      return extract;
    }
    return FolderServerResourceExtract.anonymous(extract);
  }
}
