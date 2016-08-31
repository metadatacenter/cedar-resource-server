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

@Api(value = "/templates", description = "Template operations")
public class TemplateServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateServerController.class);

  @ApiOperation(
      value = "Create template",
      httpMethod = "POST")
  public static Result createTemplate(F.Option<Boolean> importMode) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_CREATE);

      String folderId = request().getQueryString("folderId");
      if (folderId != null) {
        folderId = folderId.trim();
      }
      if (userHasWriteAccessToResource(frontendRequest, folderBase, folderId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while creating the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePostByProxy(CedarNodeType.TEMPLATE, importMode);
    } else {
      return unauthorized("You do not have write access for this folder");
    }
  }

  @ApiOperation(
      value = "Find template by id",
      httpMethod = "GET")
  public static Result findTemplate(String templateId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_READ);
      if (userHasReadAccessToResource(frontendRequest, folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetByProxy(CedarNodeType.TEMPLATE, templateId);
    } else {
      return unauthorized("You do not have read access for this template");
    }
  }

  @ApiOperation(
      value = "Find template details by id",
      httpMethod = "GET")
  public static Result findTemplateDetails(String templateId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_READ);
      if (userHasReadAccessToResource(frontendRequest, folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetDetailsByProxy(CedarNodeType.TEMPLATE, templateId);
    } else {
      return unauthorized("You do not have read access for this template");
    }
  }

  @ApiOperation(
      value = "Update template",
      httpMethod = "PUT")
  public static Result updateTemplate(String templateId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_UPDATE);
      if (userHasWriteAccessToResource(frontendRequest, folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePutByProxy(CedarNodeType.TEMPLATE, templateId);
    } else {
      return unauthorized("You do not have write access for this template");
    }
  }

  @ApiOperation(
      value = "Delete template",
      httpMethod = "DELETE")
  public static Result deleteTemplate(String templateId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_DELETE);
      if (userHasWriteAccessToResource(frontendRequest, folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while deleting the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceDeleteByProxy(CedarNodeType.TEMPLATE, templateId);
    } else {
      return unauthorized("You do not have write access for this folder");
    }
  }

  @ApiOperation(
      value = "Get permissions of a template",
      httpMethod = "GET")
  public static Result getTemplatePermissions(String templateId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_READ);
      if (userHasReadAccessToResource(frontendRequest, folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the template permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionGetByProxy(templateId);
    } else {
      return unauthorized("You do not have read access for this template");
    }
  }

  @ApiOperation(
      value = "Update template permissions",
      httpMethod = "PUT")
  public static Result updateTemplatePermissions(String templateId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_UPDATE);
      if (userHasWriteAccessToResource(frontendRequest, folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the template permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionPutByProxy(templateId);
    } else {
      return unauthorized("You do not have write access for this template");
    }
  }

}
