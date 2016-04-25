package controllers;

import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;


public class TemplateInstanceServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateInstanceServerController.class);

  public static Result createTemplateInstance() {
    return executeResourcePostByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_CREATE);
  }

  public static Result findTemplateInstance(String instanceId) {
    return executeResourcePostByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_READ, instanceId);
  }

  public static Result updateTemplateInstance(String instanceId) {
    return executeResourcePutByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_UPDATE, instanceId);
  }

  public static Result deleteTemplateInstance(String instanceId) {
    return executeResourceDeleteByProxy(CedarNodeType.INSTANCE, CedarPermission.TEMPLATE_INSTANCE_DELETE, instanceId);
  }

}
