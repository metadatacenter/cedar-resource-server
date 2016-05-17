package controllers;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

@Api(value = "/template-elements", description = "Template element operations")
public class TemplateElementServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateElementServerController.class);

  @ApiOperation(
      value = "Create template element",
      httpMethod = "POST")
  public static Result createTemplateElement() {
    return executeResourcePostByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_CREATE);
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
    return executeResourcePutByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_UPDATE, elementId);
  }

  @ApiOperation(
      value = "Delete template element",
      httpMethod = "DELETE")
  public static Result deleteTemplateElement(String elementId) {
    return executeResourceDeleteByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_DELETE, elementId);
  }

}
