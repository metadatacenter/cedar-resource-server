package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.security.model.user.ResourcePublicationStatusFilter;
import org.metadatacenter.server.security.model.user.ResourceVersionFilter;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedSortedTypedQuery;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.constant.CedarQueryParameters.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/folders")
@Produces(MediaType.APPLICATION_JSON)
public class FolderContentsResource extends AbstractResourceServerResource {

  public FolderContentsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/{id}/contents")
  public Response findFolderContentsById(@PathParam(PP_ID) String id,
                                         @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
                                         @QueryParam(QP_VERSION) Optional<String> versionParam,
                                         @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
                                         @QueryParam(QP_SORT) Optional<String> sortParam,
                                         @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                                         @QueryParam(QP_OFFSET) Optional<Integer> offsetParam) throws CedarException {

    CedarRequestContext c = buildRequestContext();
    c.must(c.user()).be(LoggedIn);

    if (id != null) {
      id = id.trim();
    }

    if (id == null || id.length() == 0) {
      throw new CedarProcessingException("You need to specify id as a request parameter!");
    }

    CedarFolderId fid = CedarFolderId.build(id);

    PagedSortedTypedQuery pagedSortedTypedQuery = new PagedSortedTypedQuery(
        cedarConfig.getResourceRESTAPI().getPagination())
        .resourceTypes(resourceTypes)
        .version(versionParam)
        .publicationStatus(publicationStatusParam)
        .sort(sortParam)
        .limit(limitParam)
        .offset(offsetParam);
    pagedSortedTypedQuery.validate();

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerFolder folder = folderSession.findFolderById(fid);
    if (folder == null) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .errorMessage("The folder can not be found by id")
          .build();
    }

    ResourcePermissionServiceSession permissionServiceSession = CedarDataServices.getResourcePermissionServiceSession(c);
    boolean hasRead = permissionServiceSession.userHasReadAccessToResource(fid);
    if (!hasRead) {
      return CedarResponse.forbidden()
          .id(id)
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_FOLDER)
          .errorMessage("You do not have read access to the folder")
          .build();
    }

    UriBuilder builder = uriInfo.getAbsolutePathBuilder();
    URI absoluteURI = builder
        .queryParam(QP_RESOURCE_TYPES, pagedSortedTypedQuery.getResourceTypesAsString())
        .queryParam(QP_VERSION, pagedSortedTypedQuery.getVersionAsString())
        .queryParam(QP_PUBLICATION_STATUS, pagedSortedTypedQuery.getPublicationStatusAsString())
        .queryParam(QP_SORT, pagedSortedTypedQuery.getSortListAsString())
        .build();

    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

    List<FolderServerResourceExtract> pathInfo = PathInfoBuilder.getResourcePathExtract(c, folderSession, permissionSession, folder);

    FolderServerNodeListResponse r = findFolderContents(folderSession, fid, absoluteURI.toString(), pathInfo, pagedSortedTypedQuery);

    addProvenanceDisplayNames(r);
    return Response.ok(r).build();
  }

  private FolderServerNodeListResponse findFolderContents(FolderServiceSession folderSession, CedarFolderId folderId, String absoluteUrl,
                                                          List<FolderServerResourceExtract> pathInfo, PagedSortedTypedQuery pagedSortedTypedQuery) {

    int limit = pagedSortedTypedQuery.getLimit();
    int offset = pagedSortedTypedQuery.getOffset();
    List<String> sortList = pagedSortedTypedQuery.getSortList();
    List<CedarResourceType> resourceTypeList = pagedSortedTypedQuery.getResourceTypeList();
    ResourceVersionFilter version = pagedSortedTypedQuery.getVersion();
    ResourcePublicationStatusFilter publicationStatus = pagedSortedTypedQuery.getPublicationStatus();

    FolderServerNodeListResponse r = new FolderServerNodeListResponse();
    r.setNodeListQueryType(NodeListQueryType.FOLDER_CONTENT);

    NodeListRequest req = new NodeListRequest();
    req.setResourceTypes(resourceTypeList);
    req.setVersion(version);
    req.setPublicationStatus(publicationStatus);
    req.setLimit(limit);
    req.setOffset(offset);
    req.setSort(sortList);

    r.setRequest(req);

    List<FolderServerResourceExtract> resources = folderSession.findFolderContentsExtract(folderId, resourceTypeList, version, publicationStatus,
        limit, offset, sortList);

    long total = folderSession.findFolderContentsCount(folderId, resourceTypeList, version, publicationStatus);

    r.setTotalCount(total);
    r.setCurrentOffset(offset);

    r.setResources(resources);

    r.setPathInfo(pathInfo);

    r.setPaging(LinkHeaderUtil.getPagingLinkHeaders(absoluteUrl, total, limit, offset));

    return r;
  }

}
