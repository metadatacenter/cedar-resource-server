package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordnik.swagger.annotations.*;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.AuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import play.libs.F;
import play.mvc.Result;
import utils.DataServices;
import utils.ParametersValidator;

import java.util.ArrayList;
import java.util.List;

@Api(value = "/search", description = "Search for resources")
public class SearchController extends AbstractResourceServerController {

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

  @ApiOperation(
      value = "Search for resources. This call is not paged and is not intended for real time user requests, but rather" +
          " for processing large amounts of data.", httpMethod = "GET")
  public static Result searchDeep(F.Option<String> query, F.Option<String> resourceTypes, F.Option<String> templateId, F.Option<String> sort, F
      .Option<Integer> limitParam) {
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
      // The searchDeep method can be used to retrieve all elements in one call, so we set the highest possible maxAllowedLimit
      int limit = ParametersValidator.validateLimit(limitParam,
          cedarConfig.getSearchSettings().getSearchDefaultSettings().getDefaultLimit(), Integer.MAX_VALUE);

      // Get userId from apiKey
      //CedarUser user = Authorization.getUser(frontendRequest);
      //String userId = cedarConfig.getLinkedDataPrefix(CedarNodeType.USER) + user.getId();

      FolderServerNodeListResponse results = DataServices.getInstance().getSearchService().searchDeep(queryString,
          resourceTypeList, tempId, sortList, limit);

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

  // TODO: Search by POST
//  public static Result searchByPost() {
//    try {
//      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
//      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);
//
//      //RSNodeListResponse results = DataServices.getInstance().getSearchService().search("");
//
//      ObjectMapper mapper = new ObjectMapper();
//      //JsonNode resultsNode = mapper.valueToTree(results);
//      //return ok(resultsNode);
//      return ok();
//    } catch (IllegalArgumentException e) {
//      return badRequestWithError(e);
//    } catch (Exception e) {
//      return internalServerErrorWithError(e);
//    }
//  }

  // Reindex all resources
  public static Result regenerateSearchIndex() {
    try {
      AuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(authRequest, CedarPermission.SEARCH_INDEX_REINDEX);
      // Read input parameters from body
      JsonNode json = request().body().asJson();
      boolean force = false;
      if (json.get("force") != null) {
        force = Boolean.parseBoolean(json.get("force").toString());
      }
      DataServices.getInstance().getSearchService().regenerateSearchIndex(force, authRequest);

    } catch (Exception e) {
      play.Logger.error("Error regenerating search index", e);
      return internalServerErrorWithError(e);
    }
    return ok();
  }

}