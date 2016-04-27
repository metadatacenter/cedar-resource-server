package controllers;

import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

public class TemplateFieldServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateFieldServerController.class);

  public static Result createTemplateField() {
    return executeResourcePostByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_CREATE);
  }

  public static Result findTemplateField(String fieldId) {
    return executeResourceGetByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_READ, fieldId);
  }

  public static Result findTemplateFieldDetails(String fieldId) {
    return executeResourceGetDetailsByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_READ, fieldId);
  }

  public static Result updateTemplateField(String fieldId) {
    return executeResourcePutByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_UPDATE, fieldId);
  }

  public static Result deleteTemplateField(String fieldId) {
    return executeResourceDeleteByProxy(CedarNodeType.FIELD, CedarPermission.TEMPLATE_FIELD_DELETE, fieldId);
  }


}
