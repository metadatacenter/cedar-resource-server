package org.metadatacenter.cedar.resource.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.http.Header;
import org.apache.http.HeaderIterator;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import play.mvc.Http;

import java.io.IOException;

public class ProxyUtil {

  public static final String ZERO_LENGTH = "0";

  private static final int connectTimeout = 1000;
  private static final int socketTimeout = 10000;

  public static HttpResponse proxyGet(String url, Http.Request request) throws IOException {
    Request proxyRequest = Request.Get(url)
        .connectTimeout(connectTimeout)
        .socketTimeout(socketTimeout);
    proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, request.getHeader(HttpHeaders.AUTHORIZATION));
    proxyRequest.addHeader(HttpHeaders.CONTENT_LENGTH, ZERO_LENGTH);
    proxyRequest.addHeader(HttpHeaders.CONTENT_TYPE, request.getHeader(ContentType.APPLICATION_JSON.toString()));
    return proxyRequest.execute().returnResponse();
  }

  public static HttpResponse proxyDelete(String url, Http.Request request) throws IOException {
    Request proxyRequest = Request.Delete(url)
        .connectTimeout(connectTimeout)
        .socketTimeout(socketTimeout);
    proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, request.getHeader(HttpHeaders.AUTHORIZATION));
    proxyRequest.addHeader(HttpHeaders.CONTENT_LENGTH, ZERO_LENGTH);
    proxyRequest.addHeader(HttpHeaders.CONTENT_TYPE, request.getHeader(ContentType.APPLICATION_JSON.toString()));
    return proxyRequest.execute().returnResponse();
  }

  public static HttpResponse proxyPost(String url, Http.Request request) throws IOException {
    JsonNode jsonBody = request.body().asJson();
    String jsonString = jsonBody.toString();
    Request proxyRequest = Request.Post(url)
        .bodyString(jsonString, ContentType.APPLICATION_JSON)
        .connectTimeout(connectTimeout)
        .socketTimeout(socketTimeout);
    proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, request.getHeader(HttpHeaders.AUTHORIZATION));
    return proxyRequest.execute().returnResponse();
  }

  public static HttpResponse proxyPut(String url, Http.Request request) throws IOException {
    JsonNode jsonBody = request.body().asJson();
    String jsonString = jsonBody.toString();
    Request proxyRequest = Request.Put(url)
        .bodyString(jsonString, ContentType.APPLICATION_JSON)
        .connectTimeout(connectTimeout)
        .socketTimeout(socketTimeout);
    proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, request.getHeader(HttpHeaders.AUTHORIZATION));
    return proxyRequest.execute().returnResponse();
  }

  public static void proxyResponseHeaders(HttpResponse proxyResponse, Http.Response response) {
    HeaderIterator headerIterator = proxyResponse.headerIterator();
    while (headerIterator.hasNext()) {
      Header header = headerIterator.nextHeader();
      if (HttpHeaders.CONTENT_TYPE.equals(header.getName())) {
        response.setHeader(header.getName(), header.getValue());
      }
    }
  }

}
