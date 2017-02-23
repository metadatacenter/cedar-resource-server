package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.annotations.*;
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
@Api(value = "/search", description = "Search for resources")
public class SearchResource extends AbstractResourceServerResource {

  public SearchResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @ApiOperation(
      value = "Search for resources")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success!"),
      @ApiResponse(code = 400, message = "Bad Request"),
      @ApiResponse(code = 401, message = "Unauthorized"),
      @ApiResponse(code = 500, message = "Internal Server Error")})

  @ApiImplicitParams(value = {
      @ApiImplicitParam(name = "Authorization", value = "Authorization header. Format: 'apiKey {your_apiKey}'. " +
          "Example: 'apiKey eb110fac-4970-492a-87b7-ccbfac4f31cc'", required = true, dataType = "string", paramType =
          "header"),
      @ApiImplicitParam(name = "q", value = "Search query. Example: 'q=investigation'", required = true, dataType =
          "string", paramType = "query"),
      @ApiImplicitParam(name = "resource_types", value = "Comma-separated list of resource types. Allowed values: " +
          "{template, element, field, instance, folder}. Example: 'resource_types=template,element'. If template_id " +
          "is provided, resource_types is automatically set to 'instance'", required = false, dataType = "string",
          paramType = "query"),
      @ApiImplicitParam(name = "template_id", value = "Template identifier. Example: 'template_id=https://repo" +
          ".metadatacenter.net/templates/432db060-8ac1-4f26-9e5b-082e563d8e34'. If this parameter is provided, all " +
          "instances for the given template will be returned", required = false, dataType = "string", paramType =
          "query"),
      @ApiImplicitParam(name = "sort", value = "Sort by. Example: 'sort=createdOnTS'. The '-' prefix may be used to " +
          "apply inverse sorting", allowableValues = "name,createdOnTS,lastUpdatedOnTS,-name,-createdOnTS," +
          "-lastUpdatedOnTS", defaultValue = "name", required = false, dataType = "string", paramType = "query"),
      @ApiImplicitParam(name = "limit", value = "Maximum number of resources to be retrieved", defaultValue = "50",
          required = false, dataType = "int", paramType = "query"),
      @ApiImplicitParam(name = "offset", value = "Offset", defaultValue = "0", required = false, dataType = "int",
          paramType = "query")})
  @GET
  @Timed
  @Path("/search")
  public Response search(@QueryParam(QP_Q) Optional<String> q,
                         @QueryParam(QP_RESOURCE_TYPES) Optional<String> resourceTypes,
                         @QueryParam(QP_DERIVED_FROM_ID) Optional<String> derivedFromIdParam,
                         @QueryParam(QP_SORT) Optional<String> sortParam,
                         @QueryParam(QP_LIMIT) Optional<Integer> limitParam,
                         @QueryParam(QP_OFFSET) Optional<Integer> offsetParam,
                         @QueryParam(QP_SHARING) Optional<String> sharing) throws CedarException {

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

      String url = builder.getProxyUrl(folderBase);

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

        FolderServerNodeListResponse results = contentSearchingService.search(c,
            queryString, resourceTypeList, templateId, sortList, limit, offset, absoluteUrl);
        results.setNodeListQueryType(nlqt);

        JsonNode resultsNode = JsonMapper.MAPPER.valueToTree(results);
        return Response.ok().entity(resultsNode).build();
      } catch (Exception e) {
        throw new CedarProcessingException(e);
      }
    }
  }
}