package org.metadatacenter.cedar.resource.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarSchemaArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.url.MicroserviceUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;

public class ArtifactServerUtil {

  public record ArtifactContent(String content, String etag) {
  }

  public static String getSchemaArtifactFromArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id, CedarRequestContext context, MicroserviceUrlUtil microserviceUrlUtil,
                                                           HttpServletResponse response) throws CedarProcessingException {
    return getSchemaArtifactWithEtagFromArtifactServer(resourceType, id, context, microserviceUrlUtil, response).content();
  }

  public static ArtifactContent getSchemaArtifactWithEtagFromArtifactServer(CedarResourceType resourceType,
                                                                              CedarSchemaArtifactId id,
                                                                              CedarRequestContext context,
                                                                              MicroserviceUrlUtil microserviceUrlUtil,
                                                                              HttpServletResponse response)
      throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id);
      ClassicHttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
      if (response != null) {
        ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      }
      HttpEntity entity = proxyResponse.getEntity();
      String etag = proxyResponse.getFirstHeader(HttpHeaders.ETAG) == null ? null
          : proxyResponse.getFirstHeader(HttpHeaders.ETAG).getValue();
      return new ArtifactContent(EntityUtils.toString(entity, StandardCharsets.UTF_8), etag);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  public static Response putSchemaArtifactToArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id, CedarRequestContext context, String content,
                                                           MicroserviceUrlUtil microserviceUrlUtil) throws CedarProcessingException {
    return putSchemaArtifactToArtifactServer(resourceType, id, context, content, microserviceUrlUtil,
        context.getIfMatchHeader());
  }

  public static Response putSchemaArtifactToArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id,
                                                           CedarRequestContext context, String content,
                                                           MicroserviceUrlUtil microserviceUrlUtil, String expectedEtag)
      throws CedarProcessingException {
    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id);
    ClassicHttpResponse templateProxyResponse = ProxyUtil.proxyPut(url, context, content, expectedEtag);
    return buildPutResponse(templateProxyResponse);
  }

  static Response buildPutResponse(ClassicHttpResponse templateProxyResponse) {
    HttpEntity entity = templateProxyResponse.getEntity();
    int statusCode = templateProxyResponse.getCode();
    if (entity != null) {
      JsonNode responseNode = null;
      try {
        String responseString = EntityUtils.toString(entity, StandardCharsets.UTF_8);
        responseNode = JsonMapper.MAPPER.readTree(responseString);
      } catch (Exception e) {
        return Response.status(statusCode).build();
      }
      Response.ResponseBuilder builder = Response.status(statusCode).entity(responseNode);
      if (templateProxyResponse.getFirstHeader(HttpHeaders.ETAG) != null) {
        builder.header(HttpHeaders.ETAG, templateProxyResponse.getFirstHeader(HttpHeaders.ETAG).getValue());
      }
      return builder.build();
    } else {
      return Response.status(statusCode).build();
    }
  }


}
