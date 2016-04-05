package controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

public class TemplateElementServerController extends AbstractTemplateServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateElementServerController.class);

  public static Result createTemplateElement() {
    return ok();
  }

  public static Result findAllTemplateElements(Integer limit, Integer offset, boolean summary, String fieldNames) {
    return ok();
  }

  public static Result findTemplateElement(String templateElementId) {
    return ok();
  }

  public static Result updateTemplateElement(String templateElementId) {
    return ok();
  }

  public static Result deleteTemplateElement(String templateElementId) {
    return ok();
  }

}
