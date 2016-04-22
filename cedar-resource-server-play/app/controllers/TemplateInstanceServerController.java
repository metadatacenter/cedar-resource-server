package controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;


public class TemplateInstanceServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateInstanceServerController.class);

  public static Result createTemplateInstance() {
    return ok();
  }

  public static Result findTemplateInstance(String templateInstanceId) {
    return ok();

  }

  public static Result updateTemplateInstance(String templateInstanceId) {
    return ok();

  }

  public static Result deleteTemplateInstance(String templateInstanceId) {
    return ok();

  }

}
