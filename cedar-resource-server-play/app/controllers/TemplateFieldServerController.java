package controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

public class TemplateFieldServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateFieldServerController.class);

  public static Result findTemplateField(String templateFieldId) {
    return ok();
  }


}
