package controllers;

import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.F;
import play.mvc.Result;

import java.util.ArrayList;
import java.util.List;

public class FolderContentsController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(FolderContentsController.class);

  final static List<String> knownSortKeys;

  static {
    knownSortKeys = new ArrayList<>();
    knownSortKeys.add("name");
    knownSortKeys.add("createdOn");
    knownSortKeys.add("lastUpdatedOn");
  }

  public static Result findFolderContents(F.Option<String> pathParam, F.Option<String> resourceTypes, F
      .Option<String> sort, F.Option<Integer> limitParam, F.Option<Integer> offsetParam) {
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(frontendRequest, CedarPermission.JUST_AUTHORIZED);

      return null;

    } catch (IllegalArgumentException e) {
      return badRequestWithError(e);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }


}
