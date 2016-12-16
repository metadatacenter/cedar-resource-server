package org.metadatacenter.cedar.resource.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.annotations.*;
import org.metadatacenter.cedar.resource.util.ParametersValidator;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.util.http.CedarURIBuilder;
import org.metadatacenter.util.json.JsonMapper;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
      @ApiImplicitParam(name = "Authorization", value = "Authorization header. Format: 'apiKey {your_apiKey}'. Example: 'apiKey eb110fac-4970-492a-87b7-ccbfac4f31cc'", required = true, dataType = "string", paramType = "header"),
      @ApiImplicitParam(name = "q", value = "Search query. Example: 'q=investigation'", required = true, dataType = "string", paramType = "query"),
      @ApiImplicitParam(name = "resource_types", value="Comma-separated list of resource types. Allowed values: {template, element, field, instance, folder}. Example: 'resource_types=template,element'. If template_id is provided, resource_types is automatically set to 'instance'", required = false, dataType = "string", paramType = "query"),
      @ApiImplicitParam(name = "template_id", value="Template identifier. Example: 'template_id=https://repo.metadatacenter.net/templates/432db060-8ac1-4f26-9e5b-082e563d8e34'. If this parameter is provided, all instances for the given template will be returned", required = false, dataType = "string", paramType = "query"),
      @ApiImplicitParam(name = "sort", value="Sort by. Example: 'sort=createdOnTS'. The '-' prefix may be used to apply inverse sorting", allowableValues = "name,createdOnTS,lastUpdatedOnTS,-name,-createdOnTS,-lastUpdatedOnTS", defaultValue = "name", required = false, dataType = "string", paramType = "query"),
      @ApiImplicitParam(name = "limit", value="Maximum number of resources to be retrieved", defaultValue = "50", required = false, dataType = "int", paramType = "query"),
      @ApiImplicitParam(name = "offset", value="Offset", defaultValue = "0", required = false, dataType = "int", paramType = "query")})
  @GET
  @Timed
  @Path("/search")
  public Response search(@QueryParam("query") Optional<String> q,
                         @QueryParam("resource_types") Optional<String> resourceTypes,
                         @QueryParam("template_id") Optional<String> templateId,
                         @QueryParam("sort") Optional<String> sort,
                         @QueryParam("limit") Optional<Integer> limitParam,
                         @QueryParam("offset") Optional<Integer> offsetParam) throws CedarException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);

    try {
      // Parameters validation
      String queryString = ParametersValidator.validateQuery(q);
      String tempId = ParametersValidator.validateTemplateId(templateId);
      // If templateId is specified, the resource types is limited to instances
      List<String> resourceTypeList = null;
      if (tempId != null) {
        resourceTypeList = new ArrayList<>();
        resourceTypeList.add(CedarNodeType.Types.INSTANCE);
      } else {
        resourceTypeList = ParametersValidator.validateResourceTypes(resourceTypes);
      }
      List<String> sortList = ParametersValidator.validateSort(sort);
      int limit = ParametersValidator.validateLimit(limitParam,
          cedarConfig.getSearchSettings().getSearchDefaultSettings().getDefaultLimit(),
          cedarConfig.getSearchSettings().getSearchDefaultSettings().getMaxAllowedLimit());
      int offset = ParametersValidator.validateOffset(offsetParam);

      CedarURIBuilder builder = new CedarURIBuilder(uriInfo)
          .queryParam("query", q)
          .queryParam("resource_types", resourceTypes)
          .queryParam("templateId", templateId)
          .queryParam("sort", sort)
          .queryParam("limit", limitParam)
          .queryParam("offset", offsetParam);

      String absoluteUrl = builder.build().toString();

      FolderServerNodeListResponse results = searchService.search(queryString, resourceTypeList, tempId, sortList, limit, offset, absoluteUrl, c);

      JsonNode resultsNode = JsonMapper.MAPPER.valueToTree(results);
      return Response.ok().entity(resultsNode).build();
    } catch (Exception e) {
      throw new CedarAssertionException(e);
    }
  }
}