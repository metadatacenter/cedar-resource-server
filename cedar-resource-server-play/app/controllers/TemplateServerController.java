package controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

public class TemplateServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateServerController.class);

  public static Result createTemplate() {
    return ok();
  }

  public static Result findTemplate(String templateId) {
    return ok();
  }

  public static Result updateTemplate(String templateId) {
    return ok();
  }

  public static Result deleteTemplate(String templateId) {
    return ok();
  }

}
