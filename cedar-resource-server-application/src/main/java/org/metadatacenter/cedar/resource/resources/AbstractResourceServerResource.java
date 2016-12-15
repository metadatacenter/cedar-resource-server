package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.metadatacenter.bridge.FolderServerProxy;
import org.metadatacenter.cedar.resource.search.SearchService;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarObjectNotFoundException;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.folderserver.FolderServerResource;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.rest.exception.CedarAssertionException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.server.security.model.auth.NodePermission;
import org.metadatacenter.server.security.model.user.CedarUserSummary;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.Optional;

import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

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

  protected FolderServerFolder getCedarFolderById(String id) throws CedarException {
    String url = folderBase + CedarNodeType.FOLDER.getPrefix() + "/" + CedarUrlUtil.urlEncode(id);

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request);
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

  protected static FolderServerNode deserializeResource(HttpResponse proxyResponse) throws CedarAssertionException {
    FolderServerNode resource = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      System.out.println(responseString);
      resource = JsonMapper.MAPPER.readValue(responseString, FolderServerNode.class);
    } catch (IOException e) {
      throw new CedarAssertionException(e);
    }
    return resource;
  }

  private FolderServerNode addProvenanceDisplayName(FolderServerNode resource) throws CedarException {
    if (resource != null) {
      CedarUserSummary creator = getUserSummary(extractUserUUID(resource.getCreatedBy()));
      CedarUserSummary updater = getUserSummary(extractUserUUID(resource.getLastUpdatedBy()));
      CedarUserSummary owner = getUserSummary(extractUserUUID(resource.getOwnedBy()));
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

  private CedarUserSummary getUserSummary(String id) throws CedarException {
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
      throw new CedarAssertionException(e);
    }
    return null;
  }

  protected JsonNode resourceWithExpandedProvenanceInfo(HttpResponse proxyResponse) throws CedarException {
    FolderServerNode resource = deserializeResource(proxyResponse);
    addProvenanceDisplayName(resource);
    return JsonMapper.MAPPER.valueToTree(resource);
  }

  protected static String responseAsJsonString(HttpResponse proxyResponse) throws IOException {
    return EntityUtils.toString(proxyResponse.getEntity());
  }

  // Proxy methods for resource types
  protected Response executeResourcePostByProxy(CedarNodeType nodeType, Optional<Boolean> importMode) throws
      CedarAssertionException {

    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);
    c.must(c.user()).be(LoggedIn);
    try {
      String folderId = request.getParameter("folderId");
      if (folderId != null) {
        folderId = folderId.trim();
      }

      if (folderId == null || folderId.length() == 0) {
        return CedarResponse.badRequest()
            .parameter("parameterName", "folderId")
            .errorMessage("You must specify the folderId as a request parameter!")
            .errorKey(CedarErrorKey.MISSING_PARAMETER)
            .build();
      }

      FolderServerFolder targetFolder = getCedarFolderById(folderId);
      if (targetFolder == null) {
        //TODO should this be NOT_FOUND
        return CedarResponse.badRequest()
            .parameter("folderId", folderId)
            .errorMessage("The folder with the given id can not be found!")
            .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
            .build();
      }

      String url = templateBase + nodeType.getPrefix();
      if (importMode != null && importMode.isPresent() && importMode.get()) {
        url += "?importMode=true";
      }
      System.out.println("***RESOURCE PROXY:" + url);

      HttpResponse proxyResponse = ProxyUtil.proxyPost(url, request);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);

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

          HttpResponse resourceCreateResponse = ProxyUtil.proxyPost(resourceUrl, request, resourceRequestBodyAsString);
          int resourceCreateStatusCode = resourceCreateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceCreateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_CREATED == resourceCreateStatusCode) {
              if (locationHeader != null) {
                response.setHeader(locationHeader.getName(), locationHeader.getValue());
              }
              if (proxyResponse.getEntity() != null) {
                // index the resource that has been created
                searchService.indexResource(JsonMapper.MAPPER.readValue(resourceCreateResponse.getEntity().getContent
                    (), FolderServerResource.class), jsonNode, request);
                //TODO use created url
                return Response.created(null).entity(proxyResponse.getEntity()).build();
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
      throw new CedarAssertionException(e);
    }
  }

  protected Response generateStatusResponse(HttpResponse proxyResponse) throws CedarAssertionException {
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      try {
        return Response.status(statusCode).entity(entity.getContent()).build();
      } catch (Exception e) {
        throw new CedarAssertionException(e);
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

  protected Response executeResourceGetByProxy(CedarNodeType nodeType, String id) throws CedarAssertionException {
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      //System.out.println(url);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Response.status(statusCode).entity(entity.getContent()).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarAssertionException(e);
    }
  }

  protected Response executeResourceGetDetailsByProxy(CedarNodeType nodeType, String id) throws
      CedarAssertionException {
    try {
      String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + CedarUrlUtil.urlEncode(id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(resourceUrl, request);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Response.status(statusCode).entity(resourceWithExpandedProvenanceInfo(proxyResponse)).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarAssertionException(e);
    }
  }

  protected Response executeResourcePutByProxy(CedarNodeType nodeType, String id) throws CedarProcessingException {
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + CedarUrlUtil.urlEncode(id);
      HttpResponse proxyResponse = ProxyUtil.proxyPut(url, request);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
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

          HttpResponse resourceUpdateResponse = ProxyUtil.proxyPut(resourceUrl, request, resourceRequestBodyAsString);
          int resourceUpdateStatusCode = resourceUpdateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceUpdateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_OK == resourceUpdateStatusCode) {
              if (proxyResponse.getEntity() != null) {
                // update the resource on the index
                searchService.updateIndexedResource(JsonMapper.MAPPER.readValue(resourceUpdateResponse.getEntity()
                    .getContent(), FolderServerResource.class), jsonNode, request);
                return Response.ok().entity(proxyResponse.getEntity().getContent()).build();
              } else {
                return Response.ok().build();
              }
            } else {
              System.out.println("Resource not updated #1, rollback resource and signal error");
              return Response.status(resourceUpdateStatusCode).entity(resourceEntity.getContent()).build();
            }
          } else {
            System.out.println("Resource not updated #2, rollback resource and signal error");
            return Response.status(resourceUpdateStatusCode).build();
          }

        } else {
          return Response.ok().build();
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException("Error while updating the resource on the index", e);
    }
  }


  protected Response executeResourceDeleteByProxy(CedarNodeType nodeType, String id) throws CedarProcessingException {
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + CedarUrlUtil.urlEncode(id);
      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, request);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_NO_CONTENT) {
        // resource was not deleted
        return generateStatusResponse(proxyResponse);
      } else {
        String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + CedarUrlUtil.urlEncode(id);
        HttpResponse resourceDeleteResponse = ProxyUtil.proxyDelete(resourceUrl, request);
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

  protected boolean userHasReadAccessToFolder(String folderBase, String folderId) throws CedarException {
    String url = folderBase + CedarNodeType.Prefix.FOLDERS;
    FolderServerFolder fsFolder = FolderServerProxy.getFolder(url, folderId, request);
    if (fsFolder == null) {
      throw new CedarObjectNotFoundException("Folder not found by id")
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .parameter("folderId", folderId);
    }
    return fsFolder.currentUserCan(NodePermission.READ);
  }

  protected boolean userHasWriteAccessToFolder(String folderBase, String folderId) throws CedarException {
    String url = folderBase + CedarNodeType.Prefix.FOLDERS;
    FolderServerFolder fsFolder = FolderServerProxy.getFolder(url, folderId, request);
    if (fsFolder == null) {
      throw new CedarObjectNotFoundException("Folder not found by id")
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .parameter("folderId", folderId);
    }
    return fsFolder.currentUserCan(NodePermission.WRITE);
  }

  protected boolean userHasReadAccessToResource(String folderBase, String nodeId) throws CedarProcessingException {
    String url = folderBase + PREFIX_RESOURCES;
    FolderServerResource fsResource = FolderServerProxy.getResource(url, nodeId, request);
    if (fsResource == null) {
      throw new IllegalArgumentException("Resource not found:" + nodeId);
    }
    return fsResource.currentUserCan(NodePermission.READ);
  }

  protected boolean userHasWriteAccessToResource(String folderBase, String nodeId) throws CedarProcessingException {
    String url = folderBase + PREFIX_RESOURCES;
    FolderServerResource fsResource = FolderServerProxy.getResource(url, nodeId, request);
    if (fsResource == null) {
      throw new IllegalArgumentException("Resource not found:" + nodeId);
    }
    return fsResource.currentUserCan(NodePermission.WRITE);
  }

  protected Response executeResourcePermissionGetByProxy(String resourceId) throws CedarProcessingException {
    String url = folderBase + "resources" + "/" + CedarUrlUtil.urlEncode(resourceId) + "/permissions";
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return buildResponse(proxyResponse);
  }

  protected Response executeResourcePermissionPutByProxy(String resourceId) throws CedarProcessingException {
    String url = folderBase + "resources" + "/" + CedarUrlUtil.urlEncode(resourceId) + "/permissions";
    HttpResponse proxyResponse = ProxyUtil.proxyPut(url, request);
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
}
