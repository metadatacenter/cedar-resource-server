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

@Api(value = "/template-fields", description = "Template field operations")
public class TemplateFieldServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateFieldServerController.class);

  @ApiOperation(
      value = "Create template field",
      httpMethod = "POST")
  public static Result createTemplateField(F.Option<Boolean> importMode) {
    return executeResourcePostByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_CREATE, importMode);
  }

  @ApiOperation(
      value = "Find template field by id",
      httpMethod = "GET")
  public static Result findTemplateField(String fieldId) {
    return executeResourceGetByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_READ, fieldId);
  }

  @ApiOperation(
      value = "Find template field details by id",
      httpMethod = "GET")
  public static Result findTemplateFieldDetails(String fieldId) {
    return executeResourceGetDetailsByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_READ, fieldId);
  }

  @ApiOperation(
      value = "Update template field",
      httpMethod = "PUT")
  public static Result updateTemplateField(String fieldId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);
      if (userHasWriteAccessToResource(frontendRequest, folderBase, fieldId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the field", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePutByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_UPDATE, fieldId);
    } else {
      return unauthorized("You do not have write access for this field");
    }
  }

  @ApiOperation(
      value = "Delete template field",
      httpMethod = "DELETE")
  public static Result deleteTemplateField(String fieldId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);
      if (userHasWriteAccessToResource(frontendRequest, folderBase, fieldId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while deleting the field", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceDeleteByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_DELETE, fieldId);
    } else {
      return unauthorized("You do not have write access for this field");
    }
  }


}
