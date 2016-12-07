package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.config.CedarConfig;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/templates", description = "Template operations")
public class TemplatesResource extends AbstractResourceServerResource {

  private
  @Context
  UriInfo uriInfo;

  private
  @Context
  HttpServletRequest request;

  public TemplatesResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @ApiOperation(
      value = "Create template",
      httpMethod = "POST")
  public static Result createTemplate(F.Option<Boolean> importMode) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_CREATE);

      String folderId = request().getQueryString("folderId");
      if (folderId != null) {
        folderId = folderId.trim();
      }
      if (userHasWriteAccessToFolder(folderBase, folderId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while creating the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePostByProxy(CedarNodeType.TEMPLATE, importMode);
    } else {
      return forbidden("You do not have write access for this folder");
    }
  }

  @ApiOperation(
      value = "Find template by id",
      httpMethod = "GET")
  public static Result findTemplate(String templateId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_READ);
      if (userHasReadAccessToResource(folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetByProxy(CedarNodeType.TEMPLATE, templateId);
    } else {
      return forbidden("You do not have read access for this template");
    }
  }

  @ApiOperation(
      value = "Find template details by id",
      httpMethod = "GET")
  public static Result findTemplateDetails(String templateId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_READ);
      if (userHasReadAccessToResource(folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceGetDetailsByProxy(CedarNodeType.TEMPLATE, templateId);
    } else {
      return forbidden("You do not have read access for this template");
    }
  }

  @ApiOperation(
      value = "Update template",
      httpMethod = "PUT")
  public static Result updateTemplate(String templateId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_UPDATE);
      if (userHasWriteAccessToResource(folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePutByProxy(CedarNodeType.TEMPLATE, templateId);
    } else {
      return forbidden("You do not have write access for this template");
    }
  }

  @ApiOperation(
      value = "Delete template",
      httpMethod = "DELETE")
  public static Result deleteTemplate(String templateId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_DELETE);
      if (userHasWriteAccessToResource(folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while deleting the template", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourceDeleteByProxy(CedarNodeType.TEMPLATE, templateId);
    } else {
      return forbidden("You do not have write access for this folder");
    }
  }

  @ApiOperation(
      value = "Get permissions of a template",
      httpMethod = "GET")
  public static Result getTemplatePermissions(String templateId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_READ);
      if (userHasReadAccessToResource(folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the template permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionGetByProxy(templateId);
    } else {
      return forbidden("You do not have read access for this template");
    }
  }

  @ApiOperation(
      value = "Update template permissions",
      httpMethod = "PUT")
  public static Result updateTemplatePermissions(String templateId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_UPDATE);
      if (userHasWriteAccessToResource(folderBase, templateId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the template permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionPutByProxy(templateId);
    } else {
      return forbidden("You do not have write access for this template");
    }
  }
}