package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarArtifactId;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.id.CedarFolderId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.model.response.FolderServerNodeMapListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.cache.user.ProvenanceNameUtil;
import org.metadatacenter.util.NodeListUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.LinkHeaderUtil;
import org.metadatacenter.util.http.PagedSortedTypedQuery;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.*;

import static org.metadatacenter.constant.CedarPathParameters.PP_FOLDER_ID;
import static org.metadatacenter.constant.CedarQueryParameters.*;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/folders")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Folder Contents")
@SecurityRequirement(name = "api_key")
public class FolderContentsResource extends AbstractResourceServerResource {

  public FolderContentsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  @GET
  @Timed
  @Path("/{folder_id}/contents")
  @Operation(summary = "Get the contents of a folder", description = "Get the contents of a folder.", tags = {"Folders", "Folder Contents", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response findFolderContentsById(
      @Parameter(description = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id,
      @Parameter(description = "Resource types as comma separated values. The allowed values are: 'folder', 'field', "
          + "'element', 'template', 'instance'")
      @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
      @Parameter(description = "Version selector. It is only handled for template-fields, template-elements and templates. "
          + "The allowed values are: 'latest', 'all'")
      @QueryParam(QP_VERSION) Optional<String> versionParam,
      @Parameter(description = "Publication status selector. It is only handled for template-fields, template-elements and "
          + "templates. The allowed values are: 'bibo:draft', 'bibo:published', 'all'")
      @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
      @Parameter(description = "Sort field names as comma separated values. Prepending a field with '-' means descending "
          + "order on that field. The allowed values are: 'name', 'lastUpdatedOnTS', 'createdOnTS'")
      @QueryParam(QP_SORT) Optional<String> sortParam,
      @Parameter(description = "Paging limit")
      @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
      @Parameter(description = "Paging offset")
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

    FolderServerNodeListResponse r = NodeListUtil.findFolderContents(cedarConfig, folderSession, fid, absoluteURI.toString(), pathInfo, pagedSortedTypedQuery);

    ProvenanceNameUtil.addProvenanceDisplayNames(r);
    return Response.ok(r).build();
  }

  @GET
  @Timed
  @Path("/{folder_id}/contents-extract")
  @Operation(summary = "Get the content extracts of a folder", description = "Get the content extracts of a folder. Only "
      + "the enumerated fields will be returned. Multilevel field paths are not supported. It is intended to return "
      + "smaller payload if a lot of artifacts are expected to be returned.", tags = {"Folders", "Folder Contents", "Versioning"})
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Successful operation"),
      @ApiResponse(responseCode = "400", description = "Bad request"),
      @ApiResponse(responseCode = "401", description = "Unauthorized"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "500", description = "Internal server error")
  })
  public Response findFolderContentsExtractById(
      @Parameter(description = "Folder identifier. Example: https://repo.metadatacenter.org/folders/"
          + "8bc64ab5-df6b-48c8-8c61-6c016245918e", required = true)
      @PathParam(PP_FOLDER_ID) String id,
      @Parameter(description = "Resource types as comma separated values. The allowed values are: 'folder', 'field', "
          + "'element', 'template', 'instance'")
      @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
      @Parameter(description = "Version selector. It is only handled for template-fields, template-elements and templates. "
          + "The allowed values are: 'latest', 'all'")
      @QueryParam(QP_VERSION) Optional<String> versionParam,
      @Parameter(description = "Publication status selector. It is only handled for template-fields, template-elements and "
          + "templates. The allowed values are: 'bibo:draft', 'bibo:published', 'all'")
      @QueryParam(QP_PUBLICATION_STATUS) Optional<String> publicationStatusParam,
      @Parameter(description = "Sort field names as comma separated values. Prepending a field with '-' means descending "
          + "order on that field. The allowed values are: 'name', 'lastUpdatedOnTS', 'createdOnTS'")
      @QueryParam(QP_SORT) Optional<String> sortParam,
      @Parameter(description = "Paging limit")
      @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
      @Parameter(description = "Paging offset")
      @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
      @Parameter(description = "Field name list, separated by comma.")
      @QueryParam(QP_FIELD_NAMES) Optional<String> fieldNamesParam) throws CedarException {

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

    List<String> fieldNameList = getAndCheckFieldNames(fieldNamesParam);

    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

    List<FolderServerResourceExtract> pathInfo = PathInfoBuilder.getResourcePathExtract(c, folderSession, permissionSession, folder);

    FolderServerNodeMapListResponse r = findFolderContentsAsMaps(folderSession, fid, absoluteURI.toString(), pathInfo, pagedSortedTypedQuery,
        fieldNameList);

    boolean readCategories = fieldNameList != null && fieldNameList.contains("categories");

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
    for (Map<String, Object> resourceMap : r.getResources()) {
      String resourceId = resourceMap.get("@id").toString();
      String resourceTypeString = resourceMap.get("resourceType").toString();
      if (readCategories) {
        CedarResourceType resourceType = CedarResourceType.forValue(resourceTypeString);
        if (resourceType != CedarResourceType.FOLDER) {
          CedarArtifactId caid = CedarArtifactId.build(resourceId, resourceType);
          List<CedarCategoryId> categories = categorySession.getAttachedCategoryIds(caid);
          List<String> categoryList = new ArrayList<>();
          for (CedarCategoryId categoryId : categories) {
            categoryList.add(categoryId.getId());
          }
          resourceMap.put("categories", categoryList);
        }
      }
    }

    return Response.ok(r).build();
  }

  protected static List<String> getAndCheckFieldNames(Optional<String> fieldNames) throws CedarAssertionException {
    if (fieldNames != null && fieldNames.isPresent()) {
      return Arrays.asList(fieldNames.get().split(","));
    }
    return null;
  }


  private FolderServerNodeMapListResponse findFolderContentsAsMaps(FolderServiceSession folderSession, CedarFolderId folderId, String absoluteUrl,
                                                                   List<FolderServerResourceExtract> pathInfo,
                                                                   PagedSortedTypedQuery pagedSortedTypedQuery, List<String> fieldNameList) {
    FolderServerNodeMapListResponse r = new FolderServerNodeMapListResponse();
    r.setNodeListQueryType(NodeListQueryType.FOLDER_CONTENT);

    NodeListRequest req = NodeListUtil.buildNodeListRequest(pagedSortedTypedQuery);

    r.setRequest(req);

    List<Map<String, Object>> resources = folderSession.findFolderContentsExtractMap(folderId, req, fieldNameList);

    boolean isOpenImplicitly = pathInfo.get(pathInfo.size() - 1).getIsOpenImplicitly();
    for(Object resource : resources) {
      // TODO: resource.setIsOpenImplicitly(isOpenImplicitly);
    }

    long total = folderSession.findFolderContentsCount(folderId, req);

    r.setTotalCount(total);
    r.setCurrentOffset(req.getOffset());

    r.setResources(resources);

    r.setPathInfo(pathInfo);

    r.setPaging(LinkHeaderUtil.getPagingLinkHeaders(absoluteUrl, total, req.getLimit(), req.getOffset()));

    return r;
  }

}
