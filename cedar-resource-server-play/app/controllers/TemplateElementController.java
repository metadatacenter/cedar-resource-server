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
public class TemplateElementController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateElementController.class);

  @ApiOperation(
      value = "Create template element",
      httpMethod = "POST")
  public static Result createTemplateElement(F.Option<Boolean> importMode) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_ELEMENT_CREATE);

      String folderId = request().getQueryString("folderId");
      if (folderId != null) {
        folderId = folderId.trim();
      }
      if (userHasWriteAccessToFolder(folderBase, folderId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while creating the element", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePostByProxy(CedarNodeType.ELEMENT, importMode);
    } else {
      return forbidden("You do not have write access for this folder");
    }
  }

  @ApiOperation(
      value = "Find template element by id",
      httpMethod = "GET")
  public static Result findTemplateElement(String elementId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_ELEMENT_READ);
      if (userHasReadAccessToResource(folderBase, elementId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the element", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetByProxy(CedarNodeType.ELEMENT, elementId);
    } else {
      return forbidden("You do not have read access for this element");
    }
  }

  @ApiOperation(
      value = "Find template element details by id",
      httpMethod = "GET")
  public static Result findTemplateElementDetails(String elementId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_ELEMENT_READ);
      if (userHasReadAccessToResource(folderBase, elementId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the element", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetDetailsByProxy(CedarNodeType.ELEMENT, elementId);
    } else {
      return forbidden("You do not have read access for this element");
    }
  }

  @ApiOperation(
      value = "Update template element",
      httpMethod = "PUT")
  public static Result updateTemplateElement(String elementId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_ELEMENT_UPDATE);
      if (userHasWriteAccessToResource(folderBase, elementId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the element", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePutByProxy(CedarNodeType.ELEMENT, elementId);
    } else {
      return forbidden("You do not have write access for this element");
    }
  }

  @ApiOperation(
      value = "Delete template element",
      httpMethod = "DELETE")
  public static Result deleteTemplateElement(String elementId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_ELEMENT_DELETE);
      if (userHasWriteAccessToResource(folderBase, elementId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while deleting the element", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceDeleteByProxy(CedarNodeType.ELEMENT, elementId);
    } else {
      return forbidden("You do not have write access for this element");
    }
  }

}
