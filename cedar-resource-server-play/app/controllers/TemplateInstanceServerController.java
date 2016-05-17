package controllers;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

@Api(value = "/template-instances", description = "Template instance operations")
public class TemplateInstanceServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateInstanceServerController.class);

  @ApiOperation(
      value = "Create template instance",
      httpMethod = "POST")
  public static Result createTemplateInstance() {
    return executeResourcePostByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_CREATE);
  }

  @ApiOperation(
      value = "Find template instance by id",
      httpMethod = "GET")
  public static Result findTemplateInstance(String instanceId) {
    return executeResourceGetByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_READ, instanceId);
  }

  @ApiOperation(
      value = "Find template instance details by id",
      httpMethod = "GET")
  public static Result findTemplateInstanceDetails(String instanceId) {
    return executeResourceGetDetailsByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_READ, instanceId);
  }

  @ApiOperation(
      value = "Update template instance",
      httpMethod = "PUT")
  public static Result updateTemplateInstance(String instanceId) {
    return executeResourcePutByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_UPDATE, instanceId);
  }

  @ApiOperation(
      value = "Delete template instance",
      httpMethod = "DELETE")
  public static Result deleteTemplateInstance(String instanceId) {
    return executeResourceDeleteByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_DELETE, instanceId);
  }

}
