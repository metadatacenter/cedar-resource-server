package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.metadatacenter.model.CedarResource;
import org.metadatacenter.server.IResourceService;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

import java.util.ArrayList;
import java.util.List;

public class SearchController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(SearchController.class);

  private static IResourceService resourceService;

  public static void injectResourceService(IResourceService rs) {
    resourceService = rs;
  }

  public static Result search() {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      List<CedarResource> hits = new ArrayList<>();

      ObjectMapper mapper = new ObjectMapper();
      JsonNode hitsNode = mapper.valueToTree(hits);
      return created(hitsNode);
    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

}
