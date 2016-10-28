package controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.*;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.ElasticsearchException;
import org.metadatacenter.cedar.resource.util.FolderServerProxy;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.folderserver.FolderServerResource;
import org.metadatacenter.server.play.AbstractCedarController;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.AuthRequest;
import org.metadatacenter.server.security.model.auth.NodePermission;
import org.metadatacenter.server.security.model.user.CedarUserSummary;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.parameter.ParameterUtil;
import play.libs.F;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import utils.DataServices;

import java.io.IOException;
import java.net.UnknownHostException;

public abstract class AbstractResourceServerController extends AbstractCedarController {

  protected static final String PREFIX_RESOURCES = "resources";

  protected static CedarConfig cedarConfig;
  protected final static String folderBase;
  protected final static String templateBase;
  protected final static String usersBase;
  protected final static String groupsURL;
  protected final static String usersURL;

  static {
    cedarConfig = CedarConfig.getInstance();
    folderBase = cedarConfig.getServers().getFolder().getBase();
    templateBase = cedarConfig.getServers().getTemplate().getBase();
    usersBase = cedarConfig.getServers().getUser().getUsersBase();
    groupsURL = cedarConfig.getServers().getFolder().getGroups();
    usersURL = cedarConfig.getServers().getFolder().getUsers();
  }

  protected static FolderServerFolder getCedarFolderById(String id) throws IOException, EncoderException {
    String url = folderBase + CedarNodeType.FOLDER.getPrefix() + "/" + new URLCodec().encode(id);

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
    ProxyUtil.proxyResponseHeaders(proxyResponse, response());

    int statusCode = proxyResponse.getStatusLine().getStatusCode();

    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      if (HttpStatus.SC_OK == statusCode) {
        return (FolderServerFolder) deserializeResource(proxyResponse);
      }
    }
    return null;
  }

  protected static FolderServerNode deserializeResource(HttpResponse proxyResponse) throws IOException {
    FolderServerNode resource = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      System.out.println(responseString);
      resource = JsonMapper.MAPPER.readValue(responseString, FolderServerNode.class);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    return resource;
  }

  private static FolderServerNode addProvenanceDisplayName(FolderServerNode resource, Http.Request request) {
    if (resource != null) {
      CedarUserSummary creator = getUserSummary(request, extractUserUUID(resource.getCreatedBy()));
      CedarUserSummary updater = getUserSummary(request, extractUserUUID(resource.getLastUpdatedBy()));
      CedarUserSummary owner = getUserSummary(request, extractUserUUID(resource.getOwnedBy()));
      if (creator != null) {
        resource.setCreatedByUserName(creator.getScreenName());
      }
      if (updater != null) {
        resource.setLastUpdatedByUserName(updater.getScreenName());
      }
      if (owner != null) {
        resource.setOwnedByUserName(owner.getScreenName());
      }
    }
    return resource;
  }

  protected static String extractUserUUID(String userURL) {
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

  private static CedarUserSummary getUserSummary(Http.Request request, String id) {
    String url = usersBase + id + "/" + "summary";
    HttpResponse proxyResponse = null;
    try {
      proxyResponse = ProxyUtil.proxyGet(url, request);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        String userSummaryString = EntityUtils.toString(entity);
        if (userSummaryString != null && !userSummaryString.isEmpty()) {
          JsonNode jsonNode = JsonMapper.MAPPER.readTree(userSummaryString);
          JsonNode at = jsonNode.at("/screenName");
          if (at != null && !at.isMissingNode()) {
            CedarUserSummary summary = new CedarUserSummary();
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

  protected static JsonNode resourceWithExpandedProvenanceInfo(Http.Request request, HttpResponse proxyResponse)
      throws IOException {
    FolderServerNode resource = deserializeResource(proxyResponse);
    addProvenanceDisplayName(resource, request);
    return JsonMapper.MAPPER.valueToTree(resource);
  }


  protected static String responseAsJsonString(HttpResponse proxyResponse) throws IOException {
    return EntityUtils.toString(proxyResponse.getEntity());
  }

  // Proxy methods for resource types
  protected static Result executeResourcePostByProxy(CedarNodeType nodeType, F.Option<Boolean> importMode) {
    AuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
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

      FolderServerFolder targetFolder = getCedarFolderById(folderId);
      if (targetFolder == null) {
        ObjectNode errorParams = JsonNodeFactory.instance.objectNode();
        errorParams.put("folderId", folderId);
        return badRequest(generateErrorDescription("folderNotFound",
            "The folder with the given id can not be found!", errorParams));
      }

      String url = templateBase + nodeType.getPrefix();
      if (importMode != null && importMode.isDefined() && importMode.get()) {
        url += "?importMode=true";
      }
      System.out.println("***RESOURCE PROXY:" + url);
      play.Logger.debug("***RESOURCE PROXY:" + url);

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
          JsonNode jsonNode = JsonMapper.MAPPER.readTree(entityContent);
          String id = jsonNode.get("@id").asText();

          String resourceUrl = folderBase + PREFIX_RESOURCES;
          //System.out.println(resourceUrl);
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", targetFolder.getId());
          resourceRequestBody.put("id", id);
          resourceRequestBody.put("nodeType", nodeType.getValue());
          resourceRequestBody.put("name", extractNameFromResponseObject(nodeType, jsonNode));
          resourceRequestBody.put("description", extractDescriptionFromResponseObject(nodeType, jsonNode));
          String resourceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(resourceRequestBody);

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
                DataServices.getInstance().getSearchService().indexResource(JsonMapper.MAPPER.readValue
                    (resourceCreateResponse.getEntity().getContent(), FolderServerResource.class), jsonNode,
                    authRequest);
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
    } catch (UnknownHostException e) {
      play.Logger.error("Error while indexing the resource", e);
      return internalServerErrorWithError(e);
    } catch (ElasticsearchException e) {
      play.Logger.error("Error while indexing the resource", e);
      return internalServerErrorWithError(e);
    } catch (Exception e) {
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

  protected static String extractNameFromResponseObject(CedarNodeType nodeType, JsonNode jsonNode) {
    String title = "";
    JsonNode titleNode = null;
    if (nodeType == CedarNodeType.FIELD || nodeType == CedarNodeType.ELEMENT || nodeType == CedarNodeType.TEMPLATE) {
      titleNode = jsonNode.at("/_ui/title");
    } else if (nodeType == CedarNodeType.INSTANCE) {
      titleNode = jsonNode.at("/schema:name");
    }
    if (titleNode != null && !titleNode.isMissingNode()) {
      title = titleNode.textValue();
    }
    return title;
  }

  protected static String extractDescriptionFromResponseObject(CedarNodeType nodeType, JsonNode jsonNode) {
    String description = "";
    JsonNode descriptionNode = null;
    if (nodeType == CedarNodeType.FIELD || nodeType == CedarNodeType.ELEMENT || nodeType == CedarNodeType.TEMPLATE) {
      descriptionNode = jsonNode.at("/_ui/description");
    } else if (nodeType == CedarNodeType.INSTANCE) {
      descriptionNode = jsonNode.at("/schema:description");
    }
    if (descriptionNode != null && !descriptionNode.isMissingNode()) {
      description = descriptionNode.textValue();
    }
    return description;
  }

  protected static Result executeResourceGetByProxy(CedarNodeType nodeType, String id) {
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      //System.out.println(url);
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

  protected static Result executeResourceGetDetailsByProxy(CedarNodeType nodeType, String id) {
    try {
      String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + new URLCodec().encode(id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(resourceUrl, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Results.status(statusCode, resourceWithExpandedProvenanceInfo(request(), proxyResponse));
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      play.Logger.error("Error while reading details of " + nodeType.getValue(), e);
      return internalServerErrorWithError(e);
    }
  }

  protected static Result executeResourcePutByProxy(CedarNodeType nodeType, String id) {
    AuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      //System.out.println(url);
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
          JsonNode jsonNode = JsonMapper.MAPPER.readTree(entityContent);

          String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + new URLCodec().encode(id);
          //System.out.println(resourceUrl);

          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("name", extractNameFromResponseObject(nodeType, jsonNode));
          resourceRequestBody.put("description", extractDescriptionFromResponseObject(nodeType, jsonNode));
          String resourceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(resourceRequestBody);

          HttpResponse resourceUpdateResponse = ProxyUtil.proxyPut(resourceUrl, request(), resourceRequestBodyAsString);
          int resourceUpdateStatusCode = resourceUpdateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceUpdateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_OK == resourceUpdateStatusCode) {
              if (proxyResponse.getEntity() != null) {
                // update the resource on the index
                DataServices.getInstance().getSearchService().updateIndexedResource(JsonMapper.MAPPER.readValue
                    (resourceUpdateResponse.getEntity().getContent(),
                        FolderServerResource.class), jsonNode, authRequest);
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
    } catch (UnknownHostException e) {
      play.Logger.error("Error while updating the resource on the index", e);
      return internalServerErrorWithError(e);
    } catch (ElasticsearchException e) {
      play.Logger.error("Error while updating the resource on the index", e);
      return internalServerErrorWithError(e);
    } catch (Exception e) {
      play.Logger.error("Error while updating " + nodeType.getValue(), e);
      return internalServerErrorWithError(e);
    }
  }


  protected static Result executeResourceDeleteByProxy(CedarNodeType nodeType, String id) {
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      //System.out.println(url);
      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_NO_CONTENT) {
        // resource was not deleted
        return generateStatusResponse(proxyResponse);
      } else {
        String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + new URLCodec().encode(id);
        //System.out.println(resourceUrl);

        HttpResponse resourceDeleteResponse = ProxyUtil.proxyDelete(resourceUrl, request());
        int resourceDeleteStatusCode = resourceDeleteResponse.getStatusLine().getStatusCode();
        if (HttpStatus.SC_NO_CONTENT == resourceDeleteStatusCode) {
          // remove the resource from the index
          DataServices.getInstance().getSearchService().removeResourceFromIndex(id);
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

  protected static String getFolderIdFromBody() throws CedarAccessException {
    JsonNode requestBody = request().body().asJson();
    if (requestBody == null) {
      throw new IllegalArgumentException("You must supply the request body as a json object!");
    }
    return ParameterUtil.getString(requestBody, "folderId", null);
  }

  protected static boolean userHasReadAccessToFolder(String folderBase, String
      folderId) throws CedarAccessException {
    String url = folderBase + CedarNodeType.Prefix.FOLDERS;
    FolderServerFolder fsFolder = FolderServerProxy.getFolder(url, folderId, request());
    if (fsFolder == null) {
      throw new IllegalArgumentException("Folder not found for id:" + folderId);
    }
    return fsFolder.currentUserCan(NodePermission.READ);
  }

  protected static boolean userHasWriteAccessToFolder(String folderBase, String
      folderId) throws CedarAccessException {
    String url = folderBase + CedarNodeType.Prefix.FOLDERS;
    FolderServerFolder fsFolder = FolderServerProxy.getFolder(url, folderId, request());
    if (fsFolder == null) {
      throw new IllegalArgumentException("Folder not found for id:" + folderId);
    }
    return fsFolder.currentUserCan(NodePermission.WRITE);
  }

  protected static boolean userHasReadAccessToResource(String folderBase, String
      nodeId) throws CedarAccessException {
    String url = folderBase + PREFIX_RESOURCES;
    FolderServerResource fsResource = FolderServerProxy.getResource(url, nodeId, request());
    if (fsResource == null) {
      throw new IllegalArgumentException("Resource not found:" + nodeId);
    }
    return fsResource.currentUserCan(NodePermission.READ);
  }

  protected static boolean userHasWriteAccessToResource(String folderBase, String
      nodeId) throws CedarAccessException {
    String url = folderBase + PREFIX_RESOURCES;
    FolderServerResource fsResource = FolderServerProxy.getResource(url, nodeId, request());
    if (fsResource == null) {
      throw new IllegalArgumentException("Resource not found:" + nodeId);
    }
    return fsResource.currentUserCan(NodePermission.WRITE);
  }

  protected static Result executeResourcePermissionGetByProxy(String resourceId) {
    try {
      String url = folderBase + "resources" + "/" + new URLCodec().encode(resourceId) + "/permissions";

      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        return Results.status(statusCode, entity.getContent());
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

  protected static Result executeResourcePermissionPutByProxy(String resourceId) {
    try {
      String url = folderBase + "resources" + "/" + new URLCodec().encode(resourceId) + "/permissions";

      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());

      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        return Results.status(statusCode, entity.getContent());
      } else {
        return Results.status(statusCode);
      }
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }
  }

}
