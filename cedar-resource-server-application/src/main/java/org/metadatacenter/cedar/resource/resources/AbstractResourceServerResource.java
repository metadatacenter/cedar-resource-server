package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.bridge.FolderServerProxy;
import org.metadatacenter.cedar.resource.search.SearchService;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarObjectNotFoundException;
import org.metadatacenter.exception.CedarPermissionException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.folderserver.FolderServerResource;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.security.model.auth.NodePermission;
import org.metadatacenter.server.security.model.user.CedarUserSummary;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

public class AbstractResourceServerResource {

  protected
  @Context
  UriInfo uriInfo;

  protected
  @Context
  HttpServletRequest request;

  protected
  @Context
  HttpServletResponse response;

  protected static final String PREFIX_RESOURCES = "resources";

  protected static SearchService searchService;

  protected final CedarConfig cedarConfig;
  protected final String folderBase;
  protected final String templateBase;
  protected final String usersBase;
  protected final String groupsURL;
  protected final String usersURL;

  protected AbstractResourceServerResource(CedarConfig cedarConfig) {
    this.cedarConfig = cedarConfig;
    folderBase = cedarConfig.getServers().getFolder().getBase();
    templateBase = cedarConfig.getServers().getTemplate().getBase();
    usersBase = cedarConfig.getServers().getUser().getUsersBase();
    groupsURL = cedarConfig.getServers().getFolder().getGroups();
    usersURL = cedarConfig.getServers().getFolder().getUsers();
  }

  public static void injectSearchService(SearchService searchService) {
    AbstractResourceServerResource.searchService = searchService;
  }

  protected FolderServerFolder getCedarFolderById(String id, CedarRequestContext context) throws
      CedarProcessingException {
    String url = folderBase + CedarNodeType.FOLDER.getPrefix() + "/" + CedarUrlUtil.urlEncode(id);

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int statusCode = proxyResponse.getStatusLine().getStatusCode();

    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      if (HttpStatus.SC_OK == statusCode) {
        return (FolderServerFolder) deserializeResource(proxyResponse);
      }
    }
    return null;
  }

  protected static FolderServerNode deserializeResource(HttpResponse proxyResponse) throws CedarProcessingException {
    FolderServerNode resource = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      System.out.println(responseString);
      resource = JsonMapper.MAPPER.readValue(responseString, FolderServerNode.class);
    } catch (IOException e) {
      throw new CedarProcessingException(e);
    }
    return resource;
  }

  private FolderServerNode addProvenanceDisplayName(FolderServerNode resource, CedarRequestContext context) throws
      CedarProcessingException {
    if (resource != null) {
      CedarUserSummary creator = getUserSummary(extractUserUUID(resource.getCreatedBy()), context);
      CedarUserSummary updater = getUserSummary(extractUserUUID(resource.getLastUpdatedBy()), context);
      CedarUserSummary owner = getUserSummary(extractUserUUID(resource.getOwnedBy()), context);
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

  private CedarUserSummary getUserSummary(String id, CedarRequestContext context) throws CedarProcessingException {
    String url = usersBase + id + "/" + "summary";
    HttpResponse proxyResponse = null;
    try {
      proxyResponse = ProxyUtil.proxyGet(url, context);
      HttpEntity entity = proxyResponse.getEntity();
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
      throw new CedarProcessingException(e);
    }
    return null;
  }

  protected JsonNode resourceWithExpandedProvenanceInfo(HttpResponse proxyResponse, CedarRequestContext context)
      throws CedarProcessingException {
    FolderServerNode resource = deserializeResource(proxyResponse);
    addProvenanceDisplayName(resource, context);
    return JsonMapper.MAPPER.valueToTree(resource);
  }

  protected static String responseAsJsonString(HttpResponse proxyResponse) throws IOException {
    return EntityUtils.toString(proxyResponse.getEntity());
  }

  // Proxy methods for resource types
  protected Response executeResourcePostByProxy(CedarRequestContext c, CedarNodeType nodeType, FolderServerFolder
      folder, Optional<Boolean> importMode) throws CedarProcessingException {
    try {
      String url = templateBase + nodeType.getPrefix();
      if (importMode != null && importMode.isPresent() && importMode.get()) {
        url += "?importMode=true";
      }
      System.out.println("***RESOURCE PROXY:" + url);

      HttpResponse templateProxyResponse = ProxyUtil.proxyPost(url, c);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);

      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // resource was not created
        return generateStatusResponse(templateProxyResponse);
      } else {
        // resource was created
        HttpEntity templateProxyResponseEntity = templateProxyResponse.getEntity();
        if (templateProxyResponseEntity != null) {
          String templateEntityContent = EntityUtils.toString(templateProxyResponseEntity);
          JsonNode jsonNode = JsonMapper.MAPPER.readTree(templateEntityContent);
          String id = jsonNode.get("@id").asText();

          String resourceUrl = folderBase + PREFIX_RESOURCES;
          //System.out.println(resourceUrl);
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", folder.getId());
          resourceRequestBody.put("id", id);
          resourceRequestBody.put("nodeType", nodeType.getValue());
          resourceRequestBody.put("name", extractNameFromResponseObject(nodeType, jsonNode));
          resourceRequestBody.put("description", extractDescriptionFromResponseObject(nodeType, jsonNode));
          String resourceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(resourceRequestBody);

          HttpResponse resourceCreateResponse = ProxyUtil.proxyPost(resourceUrl, c, resourceRequestBodyAsString);
          int resourceCreateStatusCode = resourceCreateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceCreateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_CREATED == resourceCreateStatusCode) {
              if (templateEntityContent != null) {
                // index the resource that has been created
                searchService.indexResource(JsonMapper.MAPPER.readValue(resourceCreateResponse.getEntity().getContent
                    (), FolderServerResource.class), jsonNode, c);
                URI location = CedarUrlUtil.getLocationURI(templateProxyResponse);
                return Response.created(location).entity(templateEntityContent).build();
              } else {
                return Response.ok().build();
              }
            } else {
              return Response.status(resourceCreateStatusCode).entity(resourceEntity.getContent()).build();
            }
          } else {
            return Response.status(resourceCreateStatusCode).build();
          }
        } else {
          return Response.ok().build();
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response generateStatusResponse(HttpResponse proxyResponse) throws CedarProcessingException {
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      try {
        return Response.status(statusCode).entity(entity.getContent()).build();
      } catch (Exception e) {
        throw new CedarProcessingException(e);
      }
    } else {
      return Response.status(statusCode).build();
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

  protected Response executeResourceGetByProxy(CedarNodeType nodeType, String id, CedarRequestContext context) throws
      CedarProcessingException {
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      //System.out.println(url);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Response.status(statusCode).entity(entity.getContent()).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response executeResourceGetDetailsByProxy(CedarNodeType nodeType, String id, CedarRequestContext context)
      throws CedarProcessingException {
    try {
      String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + CedarUrlUtil.urlEncode(id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(resourceUrl, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Response.status(statusCode).entity(resourceWithExpandedProvenanceInfo(proxyResponse, context)).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response executeResourcePutByProxy(CedarNodeType nodeType, String id, CedarRequestContext context) throws
      CedarProcessingException {
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + CedarUrlUtil.urlEncode(id);
      HttpResponse templateProxyResponse = ProxyUtil.proxyPut(url, context);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);
      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_OK) {
        // resource was not created
        return generateStatusResponse(templateProxyResponse);
      } else {
        // resource was created
        HttpEntity templateEntity = templateProxyResponse.getEntity();
        if (templateEntity != null) {
          String templateEntityContent = EntityUtils.toString(templateEntity);
          JsonNode jsonNode = JsonMapper.MAPPER.readTree(templateEntityContent);

          String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + CedarUrlUtil.urlEncode(id);
          //System.out.println(resourceUrl);

          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("name", extractNameFromResponseObject(nodeType, jsonNode));
          resourceRequestBody.put("description", extractDescriptionFromResponseObject(nodeType, jsonNode));
          String resourceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(resourceRequestBody);

          HttpResponse folderServerUpdateResponse = ProxyUtil.proxyPut(resourceUrl, context,
              resourceRequestBodyAsString);
          int folderServerUpdateStatusCode = folderServerUpdateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = folderServerUpdateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_OK == folderServerUpdateStatusCode) {
              if (templateEntityContent != null) {
                // update the resource on the index
                searchService.updateIndexedResource(JsonMapper.MAPPER.readValue(folderServerUpdateResponse.getEntity()
                    .getContent(), FolderServerResource.class), jsonNode, context);
                return Response.ok().entity(templateEntityContent).build();
              } else {
                return Response.ok().build();
              }
            } else {
              System.out.println("Resource not updated #1, rollback resource and signal error");
              return Response.status(folderServerUpdateStatusCode).entity(resourceEntity.getContent()).build();
            }
          } else {
            System.out.println("Resource not updated #2, rollback resource and signal error");
            return Response.status(folderServerUpdateStatusCode).build();
          }

        } else {
          return Response.ok().build();
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }


  protected Response executeResourceDeleteByProxy(CedarNodeType nodeType, String id, CedarRequestContext context)
      throws CedarProcessingException {
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + CedarUrlUtil.urlEncode(id);
      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_NO_CONTENT) {
        // resource was not deleted
        return generateStatusResponse(proxyResponse);
      } else {
        String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + CedarUrlUtil.urlEncode(id);
        HttpResponse resourceDeleteResponse = ProxyUtil.proxyDelete(resourceUrl, context);
        int resourceDeleteStatusCode = resourceDeleteResponse.getStatusLine().getStatusCode();
        if (HttpStatus.SC_NO_CONTENT == resourceDeleteStatusCode) {
          // remove the resource from the index
          searchService.removeResourceFromIndex(id);
          return Response.noContent().build();
        } else {
          return generateStatusResponse(resourceDeleteResponse);
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected FolderServerFolder userMustHaveReadAccessToFolder(CedarRequestContext context, String folderId) throws
      CedarException {
    String url = folderBase + CedarNodeType.Prefix.FOLDERS;
    FolderServerFolder fsFolder = FolderServerProxy.getFolder(url, folderId, context);
    if (fsFolder == null) {
      throw new CedarObjectNotFoundException("Folder not found by id")
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .parameter("folderId", folderId);
    }
    if (fsFolder.currentUserCan(NodePermission.READ)) {
      return fsFolder;
    } else {
      throw new CedarPermissionException("You do not have read access to the folder")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_FOLDER)
          .parameter("folderId", folderId);
    }
  }

  protected FolderServerFolder userMustHaveWriteAccessToFolder(CedarRequestContext context, String folderId)
      throws CedarException {
    String url = folderBase + CedarNodeType.Prefix.FOLDERS;
    FolderServerFolder fsFolder = FolderServerProxy.getFolder(url, folderId, context);
    if (fsFolder == null) {
      throw new CedarObjectNotFoundException("Folder not found by id")
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .parameter("folderId", folderId);
    }
    if (fsFolder.currentUserCan(NodePermission.WRITE)) {
      return fsFolder;
    } else {
      throw new CedarPermissionException("You do not have write access to the folder")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_FOLDER)
          .parameter("folderId", folderId);
    }
  }

  protected FolderServerResource userMustHaveReadAccessToResource(CedarRequestContext context, String resourceId) throws
      CedarException {
    String url = folderBase + PREFIX_RESOURCES;
    FolderServerResource fsResource = FolderServerProxy.getResource(url, resourceId, context);
    if (fsResource == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
          .parameter("resourceId", resourceId);
    }
    if (fsResource.currentUserCan(NodePermission.READ)) {
      return fsResource;
    } else {
      throw new CedarPermissionException("You do not have read access to the resource")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_RESOURCE)
          .parameter("resourceId", resourceId);
    }
  }

  protected FolderServerResource userMustHaveWriteAccessToResource(CedarRequestContext context, String resourceId)
      throws CedarException {
    String url = folderBase + PREFIX_RESOURCES;
    FolderServerResource fsResource = FolderServerProxy.getResource(url, resourceId, context);
    if (fsResource == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
          .parameter("resourceId", resourceId);
    }
    if (fsResource.currentUserCan(NodePermission.WRITE)) {
      return fsResource;
    } else {
      throw new CedarPermissionException("You do not have write access to the resource")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_RESOURCE)
          .parameter("resourceId", resourceId);
    }
  }

  protected Response executeResourcePermissionGetByProxy(String resourceId, CedarRequestContext context) throws
      CedarProcessingException {
    String url = folderBase + "resources" + "/" + CedarUrlUtil.urlEncode(resourceId) + "/permissions";
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return buildResponse(proxyResponse);
  }

  protected Response executeResourcePermissionPutByProxy(String resourceId, CedarRequestContext context) throws
      CedarProcessingException {
    String url = folderBase + "resources" + "/" + CedarUrlUtil.urlEncode(resourceId) + "/permissions";
    HttpResponse proxyResponse = ProxyUtil.proxyPut(url, context);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return buildResponse(proxyResponse);
  }

  private static Response buildResponse(HttpResponse proxyResponse) throws CedarProcessingException {
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      try {
        return Response.status(statusCode).entity(entity.getContent()).build();
      } catch (IOException e) {
        throw new CedarProcessingException(e);
      }
    } else {
      return Response.status(statusCode).build();
    }
  }

  protected Response deserializeAndConvertFolderNamesIfNecessary(HttpResponse proxyResponse) throws
      CedarProcessingException {
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    if (statusCode == HttpStatus.SC_OK) {
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        try {
          FolderServerNodeListResponse response = null;
          String responseString = EntityUtils.toString(proxyResponse.getEntity());
          response = JsonMapper.MAPPER.readValue(responseString, FolderServerNodeListResponse.class);
          // it can not be deserialized as RSNodeListResponse
          if (response == null) {
            return Response.status(statusCode).entity(entity.getContent()).build();
          } else {
            return Response.status(statusCode).entity(JsonMapper.MAPPER.writeValueAsString(response)).build();
          }
        } catch (IOException e) {
          throw new CedarProcessingException(e);
        }
      } else {
        return Response.status(statusCode).build();
      }
    } else {
      HttpEntity entity = proxyResponse.getEntity();
      try {
        if (entity != null) {
          return Response.status(statusCode).entity(entity.getContent()).build();
        } else {
          return Response.status(statusCode).build();
        }
      } catch (IOException e) {
        throw new CedarProcessingException(e);
      }
    }
  }

}