package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.metadatacenter.cedar.resource.search.SearchService;
import org.metadatacenter.model.response.RSNodeListResponse;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.F;
import play.mvc.Result;

public class SearchController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(SearchController.class);

  private static SearchService searchService;

  static {
    searchService = new SearchService();
  }

  // GET
  public static Result search(String query, F.Option<String> resourceTypes, F.Option<String> sort, F.Option<Integer>
      limit, F.Option<Integer> offset, F.Option<Boolean> foldersFirst) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      RSNodeListResponse results = searchService.search(query);

      ObjectMapper mapper = new ObjectMapper();
      JsonNode resultsNode = mapper.valueToTree(results);
      return created(resultsNode);
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
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      RSNodeListResponse results = searchService.search("");

      ObjectMapper mapper = new ObjectMapper();
      JsonNode resultsNode = mapper.valueToTree(results);
      return created(resultsNode);
    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

}
