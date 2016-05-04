package controllers;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

@Api(value = "/template-fields", description = "Template field operations")
public class TemplateFieldServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateFieldServerController.class);

  @ApiOperation(
      value = "Create template field",
      httpMethod = "POST")
  public static Result createTemplateField() {
    return executeResourcePostByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_CREATE);
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
    return executeResourcePutByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_UPDATE, fieldId);
  }

  @ApiOperation(
      value = "Delete template field",
      httpMethod = "DELETE")
  public static Result deleteTemplateField(String fieldId) {
    return executeResourceDeleteByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_DELETE, fieldId);
  }


}
