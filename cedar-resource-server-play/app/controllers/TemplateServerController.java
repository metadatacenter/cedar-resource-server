package controllers;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

@Api(value = "/templates", description = "Template operations")
public class TemplateServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateServerController.class);

  @ApiOperation(
      value = "Create template",
      httpMethod = "POST")
  public static Result createTemplate() {
    return executeResourcePostByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_CREATE);
  }

  @ApiOperation(
      value = "Find template by id",
      httpMethod = "GET")
  public static Result findTemplate(String templateId) {
    return executeResourceGetByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_READ, templateId);
  }

  @ApiOperation(
      value = "Find template details by id",
      httpMethod = "GET")
  public static Result findTemplateDetails(String templateId) {
    return executeResourceGetDetailsByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_READ, templateId);
  }

  @ApiOperation(
      value = "Update template",
      httpMethod = "PUT")
  public static Result updateTemplate(String templateId) {
    return executeResourcePutByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_UPDATE, templateId);
  }

  @ApiOperation(
      value = "Delete template",
      httpMethod = "DELETE")
  public static Result deleteTemplate(String templateId) {
    return executeResourceDeleteByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_DELETE, templateId);
  }

}
