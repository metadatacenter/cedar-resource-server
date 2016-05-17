package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.F;
import play.mvc.Result;
import utils.DataServices;
import utils.InputValidator;

import java.util.List;

@Api(value = "/search", description = "Search for resources")
public class SearchController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(SearchController.class);

  @ApiOperation(
      value = "Search for resources",
      httpMethod = "GET")
  public static Result search(F.Option<String> query, F.Option<String> resourceTypes, F.Option<String> sort, F
      .Option<Integer>
      limit, F.Option<Integer> offset, F.Option<Boolean> foldersFirst) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

      // Parameters validation
      String queryString = InputValidator.validateQuery(query);
      List<String> resourceTypeStringList = InputValidator.validateResourceTypes(resourceTypes);

      RSNodeListResponse results = DataServices.getInstance().getSearchService().search(queryString,
          resourceTypeStringList);

      ObjectMapper mapper = new ObjectMapper();
      JsonNode resultsNode = mapper.valueToTree(results);
      return ok(resultsNode);
    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  // TODO:
  // POST
  public static Result searchByPost() {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);

      //RSNodeListResponse results = DataServices.getInstance().getSearchService().search("");

      ObjectMapper mapper = new ObjectMapper();
      //JsonNode resultsNode = mapper.valueToTree(results);
      //return ok(resultsNode);
      return ok();
    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

}
