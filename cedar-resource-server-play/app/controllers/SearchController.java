package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import play.libs.F;
import play.mvc.Result;
import utils.DataServices;
import utils.InputValidator;

import java.util.List;

public class SearchController extends AbstractResourceServerController {

  // GET
  public static Result search(F.Option<String> query, F.Option<String> resourceTypes, F.Option<String> sort, F
      .Option<Integer> limit, F.Option<Integer> offset, F.Option<Boolean> foldersFirst) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

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

  // TODO: Search by POST
//  public static Result searchByPost() {
//    try {
//      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);
      // Read input parameters from body
      JsonNode json = request().body().asJson();
      boolean force = false;
      if (json.get("force") != null) {
        force = Boolean.parseBoolean(json.get("force").toString());
      }
      // TODO: get apiKey from request
      String apiKey = "";
      DataServices.getInstance().getSearchService().regenerateSearchIndex(force, apiKey);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
    return ok();
  }

}