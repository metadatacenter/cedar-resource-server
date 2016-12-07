package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.config.CedarConfig;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

@Path("/template-instances")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/template-instances", description = "Template instance operations")
public class TemplateInstancesResource extends AbstractResourceServerResource {

  private
  @Context
  UriInfo uriInfo;

  private
  @Context
  HttpServletRequest request;

  public TemplateInstancesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @ApiOperation(
      value = "Create template instance",
      httpMethod = "POST")
  public static Result createTemplateInstance(F.Option<Boolean> importMode) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      return forbidden("You do not have write access for this folder");
    }
  }

  @ApiOperation(
      value = "Find template instance by id",
      httpMethod = "GET")
  public static Result findTemplateInstance(String instanceId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      return forbidden("You do not have read access for this instance");
    }
  }

  @ApiOperation(
      value = "Find template instance details by id",
      httpMethod = "GET")
  public static Result findTemplateInstanceDetails(String instanceId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      return forbidden("You do not have read access for this instance");
    }
  }

  @ApiOperation(
      value = "Update template instance",
      httpMethod = "PUT")
  public static Result updateTemplateInstance(String instanceId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      return forbidden("You do not have write access for this instance");
    }
  }

  @ApiOperation(
      value = "Delete template instance",
      httpMethod = "DELETE")
  public static Result deleteTemplateInstance(String instanceId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      return forbidden("You do not have write access for this instance");
    }
  }

  @ApiOperation(
      value = "Get permissions of a template instance",
      httpMethod = "GET")
  public static Result getTemplateInstancePermissions(String instanceId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      return forbidden("You do not have read access for this instance");
    }
  }

  @ApiOperation(
      value = "Update template instance permissions",
      httpMethod = "PUT")
  public static Result updateTemplateInstancePermissions(String instanceId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      return forbidden("You do not have write access for this instance");
    }
  }

}