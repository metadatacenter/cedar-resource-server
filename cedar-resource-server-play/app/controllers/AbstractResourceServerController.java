package controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.*;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.ElasticsearchException;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.constant.ConfigConstants;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.resourceserver.CedarRSFolder;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.resourceserver.CedarRSResource;
import org.metadatacenter.server.play.AbstractCedarController;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.user.CedarUser;
import play.Configuration;
import play.Play;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import utils.IndexUtils;

import java.io.IOException;
import java.net.UnknownHostException;

public abstract class AbstractResourceServerController extends AbstractCedarController {

  private static final String PREFIX_RESOURCES = "resources";

  protected static Configuration config;
  protected final static String folderBase;
  protected final static String templateBase;
  protected final static String userBase;
  protected final static ObjectMapper MAPPER = new ObjectMapper();

  static {
    config = Play.application().configuration();
    folderBase = config.getString(ConfigConstants.FOLDER_SERVER_BASE);
    templateBase = config.getString(ConfigConstants.TEMPLATE_SERVER_BASE);
    userBase = config.getString(ConfigConstants.USER_SERVER_BASE);
  }

  protected static CedarRSFolder getCedarFolderById(String id) throws IOException, EncoderException {
    String url = folderBase + "folders/" + new URLCodec().encode(id);

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
    ProxyUtil.proxyResponseHeaders(proxyResponse, response());

    int statusCode = proxyResponse.getStatusLine().getStatusCode();

    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      if (HttpStatus.SC_OK == statusCode) {
        return (CedarRSFolder) deserializeResource(proxyResponse);
      }
    }
    return null;
  }

  protected static CedarRSNode deserializeResource(HttpResponse proxyResponse) throws IOException {
    CedarRSNode resource = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      resource = MAPPER.readValue(responseString, CedarRSNode.class);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    return resource;
  }

  private static CedarRSNode addProvenanceDisplayName(CedarRSNode resource, Http.Request request) {
    if (resource != null) {
      CedarUser creator = getUserSummary(request, extractUserUUID(resource.getCreatedBy()));
      CedarUser updater = getUserSummary(request, extractUserUUID(resource.getLastUpdatedBy()));
      if (creator != null) {
        resource.setCreatedByUserName(creator.getScreenName());
      }
      if (updater != null) {
        resource.setLastUpdatedByUserName(updater.getScreenName());
      }
    }
    return resource;
  }

  protected static CedarRSNode addUserHomeFolderDisplayName(CedarRSNode resource, Http.Request request) {
    if (resource != null) {
      if (resource instanceof CedarRSFolder) {
        CedarRSFolder f = (CedarRSFolder)resource;
        if (f.isUserHome()) {
          CedarUser owner = getUserSummary(request, f.getName());
          if (owner != null) {
            resource.setName(owner.getScreenName());
          }
        }
      }
    }
    return resource;
  }

  private static String extractUserUUID(String userURL) {
    String id = userURL;
    try {
      int pos = userURL.lastIndexOf('/');
      if (pos > -1) {
        id = userURL.substring(pos + 1);
      }
      id = new URLCodec().encode(id);
    } catch (EncoderException e) {
      e.printStackTrace();
    }
    return id;
  }

  private static CedarUser getUserSummary(Http.Request request, String id) {
    String url = userBase + "users" + "/" + id + "/" + "summary";
    HttpResponse proxyResponse = null;
    try {
      proxyResponse = ProxyUtil.proxyGet(url, request);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        String userSummaryString = EntityUtils.toString(entity);
        if (userSummaryString != null && !userSummaryString.isEmpty()) {
          JsonNode jsonNode = MAPPER.readTree(userSummaryString);
          JsonNode at = jsonNode.at("/screenName");
          if (at != null && !at.isMissingNode()) {
            CedarUser summary = new CedarUser();
            summary.setScreenName(at.asText());
            summary.setUserId(id);
            return summary;
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  protected static JsonNode resourceWithExpandedProvenanceInfo(Http.Request request, HttpResponse proxyResponse,
                                                               boolean addProvenanceDisplayName, boolean
                                                                   addUserHomeFolderName)
      throws IOException {
    CedarRSNode resource = deserializeResource(proxyResponse);
    if (addProvenanceDisplayName) {
      addProvenanceDisplayName(resource, request);
    }
    if (addUserHomeFolderName) {
      addUserHomeFolderDisplayName(resource, request);
    }
    return MAPPER.valueToTree(resource);
  }

  protected static String responseAsJsonString(HttpResponse proxyResponse) throws IOException {
    return EntityUtils.toString(proxyResponse.getEntity());
  }

  // Proxy methods for resource types
  protected static Result executeResourcePostByProxy(CedarNodeType nodeType, CedarPermission permission) {
    try {
      IAuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while creating " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

    try {
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

      String url = templateBase + nodeType.getPrefix();
      System.out.println(url);

      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // resource was not created
        return generateStatusResponse(proxyResponse);
      } else {
        // resource was created
        HttpEntity entity = proxyResponse.getEntity();
        if (entity != null) {
          Header locationHeader = proxyResponse.getFirstHeader(HttpHeaders.LOCATION);
          String entityContent = EntityUtils.toString(entity);
          JsonNode jsonNode = MAPPER.readTree(entityContent);
          String id = jsonNode.get("@id").asText();

          String resourceUrl = folderBase + PREFIX_RESOURCES;
          System.out.println(resourceUrl);
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", targetFolder.getId());
          resourceRequestBody.put("id", id);
          resourceRequestBody.put("resourceType", nodeType.getValue());
          resourceRequestBody.put("name", extractNameFromResponseObject(nodeType, jsonNode));
          resourceRequestBody.put("description", extractDescriptionFromResponseObject(nodeType, jsonNode));
          String resourceRequestBodyAsString = MAPPER.writeValueAsString(resourceRequestBody);

          HttpResponse resourceCreateResponse = ProxyUtil.proxyPost(resourceUrl, request(),
              resourceRequestBodyAsString);
          int resourceCreateStatusCode = resourceCreateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceCreateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_CREATED == resourceCreateStatusCode) {
              if (locationHeader != null) {
                response().setHeader(locationHeader.getName(), locationHeader.getValue());
              }
              if (proxyResponse.getEntity() != null) {
                // index the resource that has been created
                IndexUtils.indexResource(MAPPER.readValue(resourceCreateResponse.getEntity().getContent(),
                    CedarRSResource.class), jsonNode);
                return created(proxyResponse.getEntity().getContent());
              } else {
                return ok();
              }
            } else {
              System.out.println("Resource not created #1, rollback resource and signal error");
              return Results.status(resourceCreateStatusCode, resourceEntity.getContent());
            }
          } else {
            System.out.println("Resource not created #2, rollback resource and signal error");
            return Results.status(resourceCreateStatusCode);
          }

        } else {
          return ok();
        }
      }
    }
    catch (UnknownHostException e) {
      play.Logger.error("Error while indexing the resource", e);
      return internalServerErrorWithError(e);
    }
    catch (ElasticsearchException e) {
      play.Logger.error("Error while indexing the resource", e);
      return internalServerErrorWithError(e);
    }
    catch (Exception e) {
      play.Logger.error("Error while creating the resource", e);
      return internalServerErrorWithError(e);
    }

  }

  protected static Result generateStatusResponse(HttpResponse proxyResponse) throws IOException {
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      return Results.status(statusCode, entity.getContent());
    } else {
      return Results.status(statusCode);
    }
  }

  private static String extractNameFromResponseObject(CedarNodeType nodeType, JsonNode jsonNode) {
    String title = "";
    if (nodeType == CedarNodeType.FIELD || nodeType == CedarNodeType.ELEMENT || nodeType == CedarNodeType.TEMPLATE) {
      JsonNode titleNode = jsonNode.at("/_ui/title");
      if (titleNode != null && !titleNode.isMissingNode()) {
        title = titleNode.textValue();
      }
    } else if (nodeType == CedarNodeType.INSTANCE) {
      JsonNode titleNode = jsonNode.at("/_ui/templateTitle");
      if (titleNode != null && !titleNode.isMissingNode()) {
        title = titleNode.textValue();
      }
    }
    return title;
  }

  private static String extractDescriptionFromResponseObject(CedarNodeType nodeType, JsonNode jsonNode) {
    String description = "";
    if (nodeType == CedarNodeType.FIELD || nodeType == CedarNodeType.ELEMENT || nodeType == CedarNodeType.TEMPLATE) {
      JsonNode titleNode = jsonNode.at("/_ui/description");
      if (titleNode != null && !titleNode.isMissingNode()) {
        description = titleNode.textValue();
      }
    }
    return description;
  }

  protected static Result executeResourceGetByProxy(CedarNodeType nodeType, CedarPermission permission, String id) {
    try {
      IAuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while reading " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      System.out.println(url);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Results.status(statusCode, entity.getContent());
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      play.Logger.error("Error while reading " + nodeType.getValue(), e);
      return internalServerErrorWithError(e);
    }
  }

  protected static Result executeResourceGetDetailsByProxy(CedarNodeType nodeType, CedarPermission permission, String
      id) {
    try {
      IAuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while reading details of " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

    try {
      String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + new URLCodec().encode(id);
      System.out.println(resourceUrl);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(resourceUrl, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Results.status(statusCode, resourceWithExpandedProvenanceInfo(request(), proxyResponse, true, true));
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      play.Logger.error("Error while reading details of " + nodeType.getValue(), e);
      return internalServerErrorWithError(e);
    }
  }

  protected static Result executeResourcePutByProxy(CedarNodeType nodeType, CedarPermission permission, String id) {
    try {
      IAuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while updating " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      System.out.println(url);
      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_OK) {
        // resource was not created
        return generateStatusResponse(proxyResponse);
      } else {
        // resource was created
        HttpEntity entity = proxyResponse.getEntity();
        if (entity != null) {
          String entityContent = EntityUtils.toString(entity);
          JsonNode jsonNode = MAPPER.readTree(entityContent);

          String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + new URLCodec().encode(id);
          System.out.println(resourceUrl);

          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("name", extractNameFromResponseObject(nodeType, jsonNode));
          resourceRequestBody.put("description", extractDescriptionFromResponseObject(nodeType, jsonNode));
          String resourceRequestBodyAsString = MAPPER.writeValueAsString(resourceRequestBody);

          HttpResponse resourceUpdateResponse = ProxyUtil.proxyPut(resourceUrl, request(), resourceRequestBodyAsString);
          int resourceUpdateStatusCode = resourceUpdateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceUpdateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_OK == resourceUpdateStatusCode) {
              if (proxyResponse.getEntity() != null) {
                // update the resource on the index
                IndexUtils.updateIndexedResource(MAPPER.readValue(resourceUpdateResponse.getEntity().getContent(),
                    CedarRSResource.class), jsonNode);
                return ok(proxyResponse.getEntity().getContent());
              } else {
                return ok();
              }
            } else {
              System.out.println("Resource not updated #1, rollback resource and signal error");
              return Results.status(resourceUpdateStatusCode, resourceEntity.getContent());
            }
          } else {
            System.out.println("Resource not updated #2, rollback resource and signal error");
            return Results.status(resourceUpdateStatusCode);
          }

        } else {
          return ok();
        }
      }
    }
    catch (UnknownHostException e) {
      play.Logger.error("Error while updating the resource on the index", e);
      return internalServerErrorWithError(e);
    }
    catch (ElasticsearchException e) {
      play.Logger.error("Error while updating the resource on the index", e);
      return internalServerErrorWithError(e);
    }
    catch (Exception e) {
      play.Logger.error("Error while updating " + nodeType.getValue(), e);
      return internalServerErrorWithError(e);
    }
  }


  protected static Result executeResourceDeleteByProxy(CedarNodeType nodeType, CedarPermission permission, String id) {
    try {
      IAuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.mustHavePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while deleting " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      System.out.println(url);
      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_NO_CONTENT) {
        // resource was not deleted
        return generateStatusResponse(proxyResponse);
      } else {
        String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + new URLCodec().encode(id);
        System.out.println(resourceUrl);

        HttpResponse resourceDeleteResponse = ProxyUtil.proxyDelete(resourceUrl, request());
        int resourceDeleteStatusCode = resourceDeleteResponse.getStatusLine().getStatusCode();
        if (HttpStatus.SC_NO_CONTENT == resourceDeleteStatusCode) {
          // remove the resource from the index
          IndexUtils.unindexResource(id);
          return noContent();
        } else {
          return generateStatusResponse(resourceDeleteResponse);
        }
      }
    } catch (UnknownHostException e) {
      play.Logger.error("Error while un-indexing the resource", e);
      return internalServerErrorWithError(e);
    } catch (ElasticsearchException e) {
      play.Logger.error("Error while un-indexing the resource", e);
      return internalServerErrorWithError(e);
    } catch (Exception e) {
      play.Logger.error("Error while deleting " + nodeType.getValue(), e);
      return internalServerErrorWithError(e);
    }
  }

}
