package controllers;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.F;
import play.mvc.Result;

@Api(value = "/template-elements", description = "Template element operations")
public class TemplateElementServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateElementServerController.class);

  @ApiOperation(
      value = "Create template element",
      httpMethod = "POST")
  public static Result createTemplateElement(F.Option<Boolean> importMode) {
    return executeResourcePostByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_CREATE, importMode);
  }

  @ApiOperation(
      value = "Find template element by id",
      httpMethod = "GET")
  public static Result findTemplateElement(String elementId) {
    return executeResourceGetByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_READ, elementId);
  }

  @ApiOperation(
      value = "Find template element details by id",
      httpMethod = "GET")
  public static Result findTemplateElementDetails(String elementId) {
    return executeResourceGetDetailsByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_READ, elementId);
  }

  @ApiOperation(
      value = "Update template element",
      httpMethod = "PUT")
  public static Result updateTemplateElement(String elementId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);
      if (userHasWriteAccessToResource(frontendRequest, folderBase, elementId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the element", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePutByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_UPDATE, elementId);
    } else {
      return unauthorized("You do not have write access for this element");
    }
  }

  @ApiOperation(
      value = "Delete template element",
      httpMethod = "DELETE")
  public static Result deleteTemplateElement(String elementId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);
      if (userHasWriteAccessToResource(frontendRequest, folderBase, elementId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while deleting the element", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceDeleteByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_DELETE, elementId);
    } else {
      return unauthorized("You do not have write access for this element");
    }
  }

}
