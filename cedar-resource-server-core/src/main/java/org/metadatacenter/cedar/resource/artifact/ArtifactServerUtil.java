package org.metadatacenter.cedar.resource.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarSchemaArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.util.http.ArtifactServiceClient;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;

public class ArtifactServerUtil {

  public record ArtifactContent(String content, String etag) {
  }

  public static String getSchemaArtifactFromArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id, CedarRequestContext context, CedarConfig cedarConfig,
                                                           HttpServletResponse response) throws CedarProcessingException {
    return getSchemaArtifactWithEtagFromArtifactServer(resourceType, id, context, cedarConfig, response).content();
  }

  public static ArtifactContent getSchemaArtifactWithEtagFromArtifactServer(CedarResourceType resourceType,
                                                                              CedarSchemaArtifactId id,
                                                                              CedarRequestContext context,
                                                                              CedarConfig cedarConfig,
                                                                              HttpServletResponse response)
      throws CedarProcessingException {
    String url = cedarConfig.getMicroserviceUrlUtil().getArtifact().getArtifactTypeWithId(resourceType, id);
    // An outage is thrown as one and keeps its 503; wrapping it made every unreachable artifact
    // server a 500.
    ClassicHttpResponse proxyResponse = new ArtifactServiceClient(cedarConfig).get(url, context);
    String content;
    try {
      HttpEntity entity = proxyResponse.getEntity();
      content = entity == null ? null : EntityUtils.toString(entity, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
    int status = proxyResponse.getCode();
    if (status < 200 || status >= 300) {
      throw new ArtifactServerRefusal(status, content);
    }
    if (response != null) {
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    }
    String etag = proxyResponse.getFirstHeader(HttpHeaders.ETAG) == null ? null
        : proxyResponse.getFirstHeader(HttpHeaders.ETAG).getValue();
    return new ArtifactContent(content, etag);
  }

  public static Response putSchemaArtifactToArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id, CedarRequestContext context, String content,
                                                           CedarConfig cedarConfig) throws CedarProcessingException {
    return putSchemaArtifactToArtifactServer(resourceType, id, context, content, cedarConfig,
        context.getIfMatchHeader());
  }

  public static Response putSchemaArtifactToArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id,
                                                           CedarRequestContext context, String content,
                                                           CedarConfig cedarConfig, String expectedEtag)
      throws CedarProcessingException {
    return putSchemaArtifactToArtifactServer(resourceType, id, context, content, cedarConfig, expectedEtag, false);
  }

  public static Response putSchemaArtifactToArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id,
                                                           CedarRequestContext context, String content,
                                                           CedarConfig cedarConfig, String expectedEtag,
                                                           boolean requireNoInstances) throws CedarProcessingException {
    String url = cedarConfig.getMicroserviceUrlUtil().getArtifact().getArtifactTypeWithId(resourceType, id);
    if (requireNoInstances) {
      if (resourceType != CedarResourceType.TEMPLATE) throw new IllegalArgumentException("Only templates have instance references");
      url += "/inclusion";
    }
    ClassicHttpResponse templateProxyResponse = new ArtifactServiceClient(cedarConfig).put(url, context, content, expectedEtag);
    return buildPutResponse(templateProxyResponse);
  }

  static Response buildPutResponse(ClassicHttpResponse templateProxyResponse) {
    HttpEntity entity = templateProxyResponse.getEntity();
    int statusCode = templateProxyResponse.getCode();
    if (entity != null) {
      JsonNode responseNode = null;
      try {
        String responseString = EntityUtils.toString(entity, StandardCharsets.UTF_8);
        responseNode = JsonMapper.STRICT_MAPPER.readTree(responseString);
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
