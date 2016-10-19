package controllers;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.AuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.F;
import play.mvc.Result;

@Api(value = "/template-fields", description = "Template field operations")
public class TemplateFieldController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateFieldController.class);

  @ApiOperation(
      value = "Create template field",
      httpMethod = "POST")
  public static Result createTemplateField(F.Option<Boolean> importMode) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_FIELD_CREATE);

      String folderId = request().getQueryString("folderId");
      if (folderId != null) {
        folderId = folderId.trim();
      }
      if (userHasWriteAccessToFolder(folderBase, folderId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while creating the field", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePostByProxy(CedarNodeType.FIELD, importMode);
    } else {
      return forbidden("You do not have write access for this folder");
    }
  }

  @ApiOperation(
      value = "Find template field by id",
      httpMethod = "GET")
  public static Result findTemplateField(String fieldId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_FIELD_READ);
      if (userHasReadAccessToResource(folderBase, fieldId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the field", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetByProxy(CedarNodeType.FIELD, fieldId);
    } else {
      return forbidden("You do not have read access for this field");
    }
  }

  @ApiOperation(
      value = "Find template field details by id",
      httpMethod = "GET")
  public static Result findTemplateFieldDetails(String fieldId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_FIELD_READ);
      if (userHasReadAccessToResource(folderBase, fieldId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the field", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetDetailsByProxy(CedarNodeType.FIELD, fieldId);
    } else {
      return forbidden("You do not have read access for this field");
    }
  }

  @ApiOperation(
      value = "Update template field",
      httpMethod = "PUT")
  public static Result updateTemplateField(String fieldId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_FIELD_UPDATE);
      if (userHasWriteAccessToResource(folderBase, fieldId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the field", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePutByProxy(CedarNodeType.FIELD, fieldId);
    } else {
      return forbidden("You do not have write access for this field");
    }
  }

  @ApiOperation(
      value = "Delete template field",
      httpMethod = "DELETE")
  public static Result deleteTemplateField(String fieldId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_FIELD_DELETE);
      if (userHasWriteAccessToResource(folderBase, fieldId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while deleting the field", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceDeleteByProxy(CedarNodeType.FIELD, fieldId);
    } else {
      return forbidden("You do not have write access for this field");
    }
  }

  @ApiOperation(
      value = "Get permissions of a template field",
      httpMethod = "GET")
  public static Result getTemplateFieldPermissions(String fieldId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_FIELD_READ);
      if (userHasReadAccessToResource(folderBase, fieldId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the field permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionGetByProxy(fieldId);
    } else {
      return forbidden("You do not have read access for this field");
    }
  }

  @ApiOperation(
      value = "Update template field permissions",
      httpMethod = "PUT")
  public static Result updateTemplateFieldPermissions(String fieldId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_FIELD_UPDATE);
      if (userHasWriteAccessToResource(folderBase, fieldId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the field permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionPutByProxy(fieldId);
    } else {
      return forbidden("You do not have write access for this field");
    }
  }

}
