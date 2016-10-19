package org.metadatacenter.cedar.resource.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.http.Header;
import org.apache.http.HeaderIterator;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.metadatacenter.constant.HttpConnectionConstants;
import org.metadatacenter.server.security.model.AuthRequest;
import play.mvc.Http;

import java.io.IOException;

public class ProxyUtil {

  public static final String ZERO_LENGTH = "0";

  public static HttpResponse proxyGet(String url, Http.Request request) throws IOException {
    Request proxyRequest = Request.Get(url)
        .connectTimeout(HttpConnectionConstants.CONNECTION_TIMEOUT)
        .socketTimeout(HttpConnectionConstants.SOCKET_TIMEOUT);
    proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, request.getHeader(HttpHeaders.AUTHORIZATION));
    return proxyRequest.execute().returnResponse();
  }

  public static HttpResponse proxyGet(String url, AuthRequest authRequest) throws IOException {
    Request proxyRequest = Request.Get(url)
        .connectTimeout(HttpConnectionConstants.CONNECTION_TIMEOUT)
        .socketTimeout(HttpConnectionConstants.SOCKET_TIMEOUT);
    proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, authRequest.getAuthHeader());
    return proxyRequest.execute().returnResponse();
  }

  public static HttpResponse proxyDelete(String url, Http.Request request) throws IOException {
    Request proxyRequest = Request.Delete(url)
        .connectTimeout(HttpConnectionConstants.CONNECTION_TIMEOUT)
        .socketTimeout(HttpConnectionConstants.SOCKET_TIMEOUT);
    proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, request.getHeader(HttpHeaders.AUTHORIZATION));
    proxyRequest.addHeader(HttpHeaders.CONTENT_LENGTH, ZERO_LENGTH);
    proxyRequest.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.TEXT_PLAIN.toString());
    return proxyRequest.execute().returnResponse();
  }

  public static HttpResponse proxyPost(String url, Http.Request request) throws IOException {
    JsonNode jsonBody = request.body().asJson();
    return proxyPost(url, request, jsonBody.toString());
  }

  public static HttpResponse post(String url, Http.Request request, String content) throws IOException {
    return proxyPost(url, request, content);
  }

  public static HttpResponse proxyPost(String url, Http.Request request, String content) throws IOException {
    Request proxyRequest = Request.Post(url)
        .bodyString(content, ContentType.APPLICATION_JSON)
        .connectTimeout(HttpConnectionConstants.CONNECTION_TIMEOUT)
        .socketTimeout(HttpConnectionConstants.SOCKET_TIMEOUT);
    proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, request.getHeader(HttpHeaders.AUTHORIZATION));
    return proxyRequest.execute().returnResponse();
  }

  public static HttpResponse proxyPut(String url, Http.Request request) throws IOException {
    JsonNode jsonBody = request.body().asJson();
    return proxyPut(url, request, jsonBody.toString());
  }

  public static HttpResponse proxyPut(String url, Http.Request request, String content) throws IOException {
    Request proxyRequest = Request.Put(url)
        .bodyString(content, ContentType.APPLICATION_JSON)
        .connectTimeout(HttpConnectionConstants.CONNECTION_TIMEOUT)
        .socketTimeout(HttpConnectionConstants.SOCKET_TIMEOUT);
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
