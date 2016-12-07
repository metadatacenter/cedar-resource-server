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

@Path("/search-deep")
@Produces(MediaType.APPLICATION_JSON)
public class SearchDeepResource extends AbstractResourceServerResource {

  private
  @Context
  UriInfo uriInfo;

  private
  @Context
  HttpServletRequest request;

  public SearchDeepResource(CedarConfig cedarConfig) {
    super(cedarConfig);
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