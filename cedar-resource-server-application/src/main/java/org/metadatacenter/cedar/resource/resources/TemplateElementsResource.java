package org.metadatacenter.cedar.resource.resources;

import org.metadatacenter.config.CedarConfig;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

@Path("/template-elements")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/template-elements", description = "Template element operations")
public class TemplateElementsResource extends AbstractResourceServerResource {

  private
  @Context
  UriInfo uriInfo;

  private
  @Context
  HttpServletRequest request;

  public TemplateElementsResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }


  @ApiOperation(
      value = "Create template element",
      httpMethod = "POST")
  public static Result createTemplateElement(F.Option<Boolean> importMode) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
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

  @ApiOperation(
      value = "Get permissions of a template element",
      httpMethod = "GET")
  public static Result getTemplateElementPermissions(String elementId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_ELEMENT_READ);
      if (userHasReadAccessToResource(folderBase, elementId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while reading the element permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionGetByProxy(elementId);
    } else {
      return forbidden("You do not have read access for this element");
    }
  }

  @ApiOperation(
      value = "Update template element permissions",
      httpMethod = "PUT")
  public static Result updateTemplateElementPermissions(String elementId) {
    boolean canProceed = false;
    try {
      AuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.TEMPLATE_ELEMENT_UPDATE);
      if (userHasWriteAccessToResource(folderBase, elementId)) {
        canProceed = true;
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while updating the element permissions", e);
      return forbiddenWithError(e);
    }
    if (canProceed) {
      return executeResourcePermissionPutByProxy(elementId);
    } else {
      return forbidden("You do not have write access for this element");
    }
  }
}