package controllers;

import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

public class TemplateElementServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateElementServerController.class);

  public static Result createTemplateElement() {
    return executeResourcePostByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_CREATE);
  }

  public static Result findTemplateElement(String elementId) {
    return executeResourceGetByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_READ, elementId);
  }

  public static Result updateTemplateElement(String elementId) {
    return executeResourcePutByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_UPDATE, elementId);
  }

  public static Result deleteTemplateElement(String elementId) {
    return executeResourceDeleteByProxy(CedarNodeType.ELEMENT, CedarPermission.TEMPLATE_ELEMENT_DELETE, elementId);
  }

}
