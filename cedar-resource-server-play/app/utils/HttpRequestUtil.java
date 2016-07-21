package utils;

import play.mvc.Http;

public class HttpRequestUtil {

  public static String getApikeyFromRequest(Http.Request request) {
    String[] authFragments = request.getHeader("Authorization").split("\\s+");
    if (authFragments.length != 2) {
      throw new IllegalArgumentException("Wrong authorization header. Expected format: apiKey <your apiKey>");
    }
    else {
      return authFragments[1];
    }
  }

}
