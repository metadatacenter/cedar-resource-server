package controllers;

import org.metadatacenter.server.play.AbstractCedarController;
import play.Configuration;
import play.Play;
import play.mvc.Result;

public class MainResourceServerController extends AbstractResourceServerController {

  public static Configuration config;

  static {
    config = Play.application().configuration();
  }

  public static Result index() {
    return ok("CEDAR Resource Server.");
  }

  /* For CORS */
  public static Result preflight(String all) {
    return AbstractCedarController.preflight(all);
  }

}
