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

@Api(value = "/template-instances", description = "Template instance operations")
public class TemplateInstanceController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateInstanceController.class);

  @ApiOperation(
      value = "Create template instance",
      httpMethod = "POST")
  public static Result createTemplateInstance(F.Option<Boolean> importMode) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_INSTANCE_CREATE);

      String folderId = request().getQueryString("folderId");
      if (folderId != null) {
        folderId = folderId.trim();
      }
      if (userHasWriteAccessToFolder(folderBase, folderId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while creating the instance", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePostByProxy(CedarNodeType.INSTANCE, importMode);
    } else {
      return unauthorized("You do not have write access for this folder");
    }
  }

  @ApiOperation(
      value = "Find template instance by id",
      httpMethod = "GET")
  public static Result findTemplateInstance(String instanceId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_INSTANCE_READ);
      if (userHasReadAccessToResource(folderBase, instanceId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the element", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetByProxy(CedarNodeType.INSTANCE, instanceId);
    } else {
      return unauthorized("You do not have read access for this instance");
    }
  }

  @ApiOperation(
      value = "Find template instance details by id",
      httpMethod = "GET")
  public static Result findTemplateInstanceDetails(String instanceId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_INSTANCE_READ);
      if (userHasReadAccessToResource(folderBase, instanceId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the instance", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetDetailsByProxy(CedarNodeType.INSTANCE, instanceId);
    } else {
      return unauthorized("You do not have read access for this instance");
    }
  }

  @ApiOperation(
      value = "Update template instance",
      httpMethod = "PUT")
  public static Result updateTemplateInstance(String instanceId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_INSTANCE_UPDATE);
      if (userHasWriteAccessToResource(folderBase, instanceId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the instance", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePutByProxy(CedarNodeType.INSTANCE, instanceId);
    } else {
      return unauthorized("You do not have write access for this instance");
    }
  }

  @ApiOperation(
      value = "Delete template instance",
      httpMethod = "DELETE")
  public static Result deleteTemplateInstance(String instanceId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_INSTANCE_DELETE);
      if (userHasWriteAccessToResource(folderBase, instanceId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while deleting the instance", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceDeleteByProxy(CedarNodeType.INSTANCE, instanceId);
    } else {
      return unauthorized("You do not have write access for this instance");
    }
  }

  @ApiOperation(
      value = "Get permissions of a template instance",
      httpMethod = "GET")
  public static Result getTemplateInstancePermissions(String instanceId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_INSTANCE_READ);
      if (userHasReadAccessToResource(folderBase, instanceId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the instance permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionGetByProxy(instanceId);
    } else {
      return unauthorized("You do not have read access for this instance");
    }
  }

  @ApiOperation(
      value = "Update template instance permissions",
      httpMethod = "PUT")
  public static Result updateTemplateInstancePermissions(String instanceId) {
    boolean canProceed = false;
    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_INSTANCE_UPDATE);
      if (userHasWriteAccessToResource(folderBase, instanceId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the instance permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionPutByProxy(instanceId);
    } else {
      return unauthorized("You do not have write access for this instance");
    }
  }

}
