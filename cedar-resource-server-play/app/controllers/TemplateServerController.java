package controllers;

import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

public class TemplateServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateServerController.class);

  public static Result createTemplate() {
    return executeResourcePostByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_CREATE);
  }

  public static Result findTemplate(String templateId) {
    return executeResourcePostByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_READ, templateId);
  }

  public static Result updateTemplate(String templateId) {
    return executeResourcePutByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_UPDATE, templateId);
  }

  public static Result deleteTemplate(String templateId) {
    return executeResourceDeleteByProxy(CedarNodeType.TEMPLATE, CedarPermission.TEMPLATE_DELETE, templateId);
  }

}
