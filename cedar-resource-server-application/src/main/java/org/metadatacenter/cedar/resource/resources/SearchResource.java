package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.config.CedarConfig;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/search", description = "Search for resources")
public class SearchResource extends AbstractResourceServerResource {

  private
  @Context
  UriInfo uriInfo;

  private
  @Context
  HttpServletRequest request;

  public SearchResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @ApiOperation(
      value = "Search for resources",
      // notes = ...
      httpMethod = "GET")
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
  public static Result search(F.Option<String> query, F.Option<String> resourceTypes, F.Option<String> templateId, F.Option<String> sort,  F
      .Option<Integer> limitParam, F.Option<Integer> offsetParam) {
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

      // Parameters validation
      String queryString = ParametersValidator.validateQuery(query);
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

      // Get userId from apiKey
      //CedarUser user = Authorization.getUser(frontendRequest);
      //String userId = cedarConfig.getLinkedDataPrefix(CedarNodeType.USER) + user.getId();

      F.Option<Integer> none = new F.None<>();
      String absoluteUrl = routes.SearchController.search(query, resourceTypes, templateId, sort,
          none, none).absoluteURL(request());

      FolderServerNodeListResponse results = DataServices.getInstance().getSearchService().search(queryString,
          resourceTypeList, tempId, sortList, limit, offset, absoluteUrl, frontendRequest);

      ObjectMapper mapper = new ObjectMapper();
      JsonNode resultsNode = mapper.valueToTree(results);
      return ok(resultsNode);
    } catch (IllegalArgumentException e) {
      play.Logger.error("Search error", e);
      return badRequestWithError(e);
    } catch (Exception e) {
      play.Logger.error("Search error", e);
      return internalServerErrorWithError(e);
    }
  }
}