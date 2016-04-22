package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.constant.HttpConnectionConstants;
import org.metadatacenter.model.resourceserver.CedarRSFolder;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.resource.CedarResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;
import play.mvc.Results;

public class TemplateElementServerController extends AbstractResourceServerController {
  private static Logger log = LoggerFactory.getLogger(TemplateElementServerController.class);

  public static Result createTemplateElement() {
    try {
      IAuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(authRequest, CedarPermission.TEMPLATE_ELEMENT_CREATE);
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while creating the template element", e);
      return forbiddenWithError(e);
    }

    try {
      JsonNode templateElement = request().body().asJson();

      String folderId = request().getQueryString("folderId");
      if (folderId != null) {
        folderId = folderId.trim();
      }

      if (folderId == null || folderId.length() == 0) {
        ObjectNode errorParams = JsonNodeFactory.instance.objectNode();
        errorParams.put("paramName", "folderId");
        return badRequest(generateErrorDescription("parameterMissing",
            "You must specify the folderId as a request parameter!", errorParams));
      }

      CedarRSFolder targetFolder = getCedarFolderById(folderId);
      if (targetFolder == null) {
        ObjectNode errorParams = JsonNodeFactory.instance.objectNode();
        errorParams.put("folderId", folderId);
        return badRequest(generateErrorDescription("folderNotFound",
            "The folder with the given id can not be found!", errorParams));
      }

      ObjectMapper mapper = new ObjectMapper();

      System.out.println("Target folder:");
      System.out.println(targetFolder);

      String url = templateBase + "template-elements";

      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());


      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // template element was not created
        HttpEntity entity = proxyResponse.getEntity();
        if (entity != null) {
          return Results.status(statusCode, entity.getContent());
        } else {
          return Results.status(statusCode);
        }
      } else {
        // template element was created
        HttpEntity entity = proxyResponse.getEntity();
        if (entity != null) {
          String entityContent = EntityUtils.toString(entity);
          JsonNode jsonNode = mapper.readTree(entityContent);
          String id = jsonNode.get("@id").asText();
          String uuid = CedarResourceUtil.extractUUID(id);

          String resourceUrl = folderBase + "resources";
          System.out.println(resourceUrl);

          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", targetFolder.getId());
          resourceRequestBody.put("id", uuid);
          resourceRequestBody.put("resourceType", "element");
          // TODO name of the template element comes here
          resourceRequestBody.put("name", "new resource name");
          // TODO description of the template element comes here
          resourceRequestBody.put("description", "new resource description");
          String resourceRequestBodyAsString = mapper.writeValueAsString(resourceRequestBody);
          System.out.println("The request to create resource:");
          System.out.println(resourceRequestBodyAsString);

          Request proxyRequest = Request.Post(resourceUrl)
              .bodyString(resourceRequestBodyAsString, ContentType.APPLICATION_JSON)
              .connectTimeout(HttpConnectionConstants.CONNECTION_TIMEOUT)
              .socketTimeout(HttpConnectionConstants.SOCKET_TIMEOUT);
          proxyRequest.addHeader(HttpHeaders.AUTHORIZATION, request().getHeader(HttpHeaders.AUTHORIZATION));

          HttpResponse resourceCreateResponse = proxyRequest.execute().returnResponse();

          int resourceCreateStatusCode = resourceCreateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceCreateResponse.getEntity();
          System.out.println("resource creation code:" + resourceCreateStatusCode);
          System.out.println("resource creation response:");
          System.out.println(EntityUtils.toString(resourceEntity));
          if (resourceEntity != null) {
            if (HttpStatus.SC_CREATED == resourceCreateStatusCode) {
              return ok(resourceWithExpandedProvenanceInfo(proxyResponse));
            } else {
              System.out.println("Resource not created #1, rollback template element and signal error");
              return Results.status(resourceCreateStatusCode, resourceEntity.getContent());
            }
          } else {
            System.out.println("Resource not created #2, rollback template element and signal error");
            return Results.status(resourceCreateStatusCode);
          }

        } else {
          return ok();
        }
      }
    } catch (Exception e) {
      play.Logger.error("Error while creating the template element", e);
      return internalServerErrorWithError(e);
    }
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
