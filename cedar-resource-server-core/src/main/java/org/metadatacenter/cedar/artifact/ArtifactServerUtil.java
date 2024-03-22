package org.metadatacenter.cedar.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang.CharEncoding;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.id.CedarSchemaArtifactId;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.url.MicroserviceUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;

public class ArtifactServerUtil {

  public static String getSchemaArtifactFromArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id, CedarRequestContext context, MicroserviceUrlUtil microserviceUrlUtil,
                                                           HttpServletResponse response) throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
      if (response != null) {
        ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      }
      HttpEntity entity = proxyResponse.getEntity();
      return EntityUtils.toString(entity, CharEncoding.UTF_8);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  public static Response putSchemaArtifactToArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id, CedarRequestContext context, String content,
                                                           MicroserviceUrlUtil microserviceUrlUtil) throws CedarProcessingException {
    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id);
    HttpResponse templateProxyResponse = ProxyUtil.proxyPut(url, context, content);
    HttpEntity entity = templateProxyResponse.getEntity();
    int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
    if (entity != null) {
      JsonNode responseNode = null;
      try {
        String responseString = EntityUtils.toString(entity, CharEncoding.UTF_8);
        responseNode = JsonMapper.MAPPER.readTree(responseString);
      } catch (Exception e) {
        Response.status(statusCode).build();
      }
      return Response.status(statusCode).entity(responseNode).build();
    } else {
      return Response.status(statusCode).build();
    }
  }


}
