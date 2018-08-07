package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.bridge.FolderServerProxy;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.CustomHttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.*;
import org.metadatacenter.model.*;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.folderserver.FolderServerResource;
import org.metadatacenter.model.folderserverreport.FolderServerResourceReport;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.search.IndexedDocumentId;
import org.metadatacenter.server.search.elasticsearch.service.*;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.auth.NodePermission;
import org.metadatacenter.server.security.model.user.CedarUserSummary;
import org.metadatacenter.util.JsonPointerValuePair;
import org.metadatacenter.util.ModelUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import static org.metadatacenter.model.ModelNodeNames.BIBO_STATUS;

public class AbstractResourceServerResource extends CedarMicroserviceResource {

  private static final Logger log = LoggerFactory.getLogger(AbstractResourceServerResource.class);

  private static final String ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";

  protected static NodeIndexingService nodeIndexingService;
  protected static ContentIndexingService contentIndexingService;
  protected static ContentSearchingService contentSearchingService;
  protected static NodeSearchingService nodeSearchingService;
  protected static SearchPermissionEnqueueService searchPermissionEnqueueService;
  protected static UserPermissionIndexingService userPermissionIndexingService;
  protected static GroupPermissionIndexingService groupPermissionIndexingService;

  protected AbstractResourceServerResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(NodeIndexingService nodeIndexingService,
                                    NodeSearchingService nodeSearchingService,
                                    ContentIndexingService contentIndexingService,
                                    ContentSearchingService contentSearchingService,
                                    SearchPermissionEnqueueService searchPermissionEnqueueService,
                                    UserPermissionIndexingService userPermissionIndexingService,
                                    GroupPermissionIndexingService groupPermissionIndexingService) {
    AbstractResourceServerResource.nodeIndexingService = nodeIndexingService;
    AbstractResourceServerResource.nodeSearchingService = nodeSearchingService;
    AbstractResourceServerResource.contentIndexingService = contentIndexingService;
    AbstractResourceServerResource.contentSearchingService = contentSearchingService;
    AbstractResourceServerResource.searchPermissionEnqueueService = searchPermissionEnqueueService;
    AbstractResourceServerResource.userPermissionIndexingService = userPermissionIndexingService;
    AbstractResourceServerResource.groupPermissionIndexingService = groupPermissionIndexingService;
  }

  protected static <T extends FolderServerNode> T deserializeResource(HttpResponse proxyResponse, Class<T> klazz)
      throws CedarProcessingException {
    T resource = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      resource = JsonMapper.MAPPER.readValue(responseString, klazz);
    } catch (IOException e) {
      throw new CedarProcessingException(e);
    }
    return resource;
  }

  protected static void updateNameInObject(CedarNodeType nodeType, JsonNode jsonNode, String name) {
    ((ObjectNode) jsonNode).put(ModelNodeNames.SCHEMA_NAME, name);
  }

  protected static void updateDescriptionInObject(CedarNodeType nodeType, JsonNode jsonNode, String description) {
    ((ObjectNode) jsonNode).put(ModelNodeNames.SCHEMA_DESCRIPTION, description);
  }

  protected static Response newResponseWithValidationHeader(Response.ResponseBuilder responseBuilder, HttpResponse
      proxyResponse,
                                                            Object responseContent) {
    return responseBuilder
        .header(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS, getValidationStatus(proxyResponse))
        .header(ACCESS_CONTROL_EXPOSE_HEADERS, printCedarValidationHeaderList())
        .entity(responseContent).build();
  }

  private static String getValidationStatus(HttpResponse response) {
    return response.getFirstHeader(CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS).getValue();
  }

  private static String printCedarValidationHeaderList() {
    return String.format("%s", CustomHttpConstants.HEADER_CEDAR_VALIDATION_STATUS);
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

  private FolderServerNode addProvenanceDisplayName(FolderServerNode resource, CedarRequestContext context) throws
      CedarProcessingException {
    if (resource != null) {
      CedarUserSummary creator = getUserSummary(resource.getCreatedBy(), context);
      CedarUserSummary updater = getUserSummary(resource.getLastUpdatedBy(), context);
      CedarUserSummary owner = getUserSummary(resource.getOwnedBy(), context);
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

  protected <T extends FolderServerNode> JsonNode resourceWithExpandedProvenanceInfo(HttpResponse proxyResponse,
                                                                                     CedarRequestContext context,
                                                                                     Class<T> klazz)
      throws CedarProcessingException {
    T resource = deserializeResource(proxyResponse, klazz);
    addProvenanceDisplayName(resource, context);
    return JsonMapper.MAPPER.valueToTree(resource);
  }

  protected Response executeResourcePostToTemplateServer(CedarRequestContext context, CedarNodeType nodeType, String
      content) throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getTemplate().getNodeType(nodeType);

      HttpResponse templateProxyResponse = ProxyUtil.proxyPost(url, context, content);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);

      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // resource was not created
        return generateStatusResponse(templateProxyResponse);
      } else {
        HttpEntity entity = templateProxyResponse.getEntity();
        String mediaType = entity.getContentType().getValue();
        String location = templateProxyResponse.getFirstHeader(HttpHeaders.LOCATION).getValue();
        URI locationURI = new URI(location);
        if (entity != null) {
          return Response.created(locationURI).type(mediaType).entity(entity.getContent()).build();
        } else {
          return Response.created(locationURI).type(mediaType).build();
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  // Proxy methods for resource types
  protected Response executeResourcePostByProxy(CedarRequestContext context, CedarNodeType nodeType, FolderServerFolder
      folder) throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getTemplate().getNodeType(nodeType);

      HttpResponse templateProxyResponse = ProxyUtil.proxyPost(url, context);
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
          JsonNode templateJsonNode = JsonMapper.MAPPER.readTree(templateEntityContent);
          String id = templateJsonNode.get("@id").asText();

          JsonPointerValuePair namePair = ModelUtil.extractNameFromResource(nodeType, templateJsonNode);
          JsonPointerValuePair descriptionPair = ModelUtil.extractDescriptionFromResource(nodeType, templateJsonNode);

          String resourceUrl = microserviceUrlUtil.getWorkspace().getResources();
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", folder.getId());
          resourceRequestBody.put("id", id);
          resourceRequestBody.put("nodeType", nodeType.getValue());
          resourceRequestBody.put("name", namePair.getValue());
          resourceRequestBody.put("description", descriptionPair.getValue());
          if (nodeType.isVersioned()) {
            JsonPointerValuePair versionPair = ModelUtil.extractVersionFromResource(nodeType, templateJsonNode);
            JsonPointerValuePair publicationStatusPair = ModelUtil.extractPublicationStatusFromResource(nodeType,
                templateJsonNode);
            resourceRequestBody.put("version", versionPair.getValue());
            resourceRequestBody.put("publicationStatus", publicationStatusPair.getValue());
          }
          if (nodeType == CedarNodeType.INSTANCE) {
            resourceRequestBody.put("isBasedOn", ModelUtil.extractIsBasedOnFromInstance(templateJsonNode).getValue());
          }
          String resourceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(resourceRequestBody);

          HttpResponse resourceCreateResponse = ProxyUtil.proxyPost(resourceUrl, context, resourceRequestBodyAsString);
          int resourceCreateStatusCode = resourceCreateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = resourceCreateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_CREATED == resourceCreateStatusCode) {
              if (templateEntityContent != null) {
                // index the resource that has been created
                FolderServerResource folderServerResource = JsonMapper.MAPPER.readValue(resourceCreateResponse
                    .getEntity().getContent(), FolderServerResource.class);
                createIndexResource(folderServerResource, templateJsonNode, context);
                URI location = CedarUrlUtil.getLocationURI(templateProxyResponse);
                return newResponseWithValidationHeader(Response.created(location), templateProxyResponse,
                    templateEntityContent);
              } else {
                return Response.ok().build();
              }
            } else {
              return newResponseWithValidationHeader(Response.status(resourceCreateStatusCode), templateProxyResponse,
                  resourceEntity.getContent());
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

  protected Response executeResourceGetByProxy(CedarNodeType nodeType, String id,
                                               CedarRequestContext context) throws CedarProcessingException {
    return executeResourceGetByProxy(nodeType, id, Optional.empty(), context);
  }

  protected Response executeResourceGetByProxy(CedarNodeType nodeType, String id, Optional<String> format,
                                               CedarRequestContext context) throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id, format);
      // parameter
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      String mediaType = entity.getContentType().getValue();
      if (entity != null) {
        return Response.status(statusCode).type(mediaType).entity(entity.getContent()).build();
      } else {
        return Response.status(statusCode).type(mediaType).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected String getResourceFromTemplateServer(CedarNodeType nodeType, String id, CedarRequestContext context) throws
      CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      return EntityUtils.toString(entity);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response putResourceToTemplateServer(CedarNodeType nodeType, String id, CedarRequestContext context, String
      content) throws
      CedarProcessingException {
    String url = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id);
    HttpResponse templateProxyResponse = ProxyUtil.proxyPut(url, context, content);
    HttpEntity entity = templateProxyResponse.getEntity();
    int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
    if (entity != null) {
      JsonNode responseNode = null;
      try {
        String responseString = EntityUtils.toString(entity);
        responseNode = JsonMapper.MAPPER.readTree(responseString);
      } catch (Exception e) {
        Response.status(statusCode).build();
      }
      return Response.status(statusCode).entity(responseNode).build();
    } else {
      return Response.status(statusCode).build();
    }
  }

  protected Response executeResourceGetDetailsByProxy(CedarNodeType nodeType, String id, CedarRequestContext context)
      throws CedarProcessingException {
    try {
      String resourceUrl = microserviceUrlUtil.getWorkspace().getResourceWithId(id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(resourceUrl, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Response.status(statusCode).entity(resourceWithExpandedProvenanceInfo(proxyResponse, context,
            FolderServerNode.class)).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response executeResourcePutByProxy(CedarRequestContext context, CedarNodeType nodeType, String id,
                                               FolderServerResource folderServerOldResource) throws
      CedarProcessingException {
    return executeResourcePutByProxy(context, nodeType, id, null, null, folderServerOldResource);
  }

  protected Response executeResourcePutByProxy(CedarRequestContext context, CedarNodeType nodeType, String id,
                                               FolderServerFolder folder, FolderServerResource folderServerOldResource)
      throws CedarProcessingException {
    return executeResourcePutByProxy(context, nodeType, id, folder, null, folderServerOldResource);
  }

  protected Response executeResourcePutByProxy(CedarRequestContext context, CedarNodeType nodeType, String id,
                                               FolderServerFolder folder, String content, FolderServerResource
                                                   folderServerOldResource) throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id);

      String currentName = null;
      boolean foundOnTemplateServer = false;

      HttpResponse templateCurrentProxyResponse = ProxyUtil.proxyGet(url, context);
      int currentStatusCode = templateCurrentProxyResponse.getStatusLine().getStatusCode();
      if (currentStatusCode == HttpStatus.SC_OK) {
        HttpEntity currentTemplateEntity = templateCurrentProxyResponse.getEntity();
        if (currentTemplateEntity != null) {
          String currentTemplateEntityContent = EntityUtils.toString(currentTemplateEntity);
          JsonNode currentTemplateJsonNode = JsonMapper.MAPPER.readTree(currentTemplateEntityContent);
          currentName = ModelUtil.extractNameFromResource(nodeType, currentTemplateJsonNode).getValue();
          foundOnTemplateServer = true;
        }
      }

      HttpResponse templateProxyResponse = null;
      if (content == null) {
        templateProxyResponse = ProxyUtil.proxyPut(url, context);
      } else {
        templateProxyResponse = ProxyUtil.proxyPut(url, context, content);
      }
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);
      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      CreateOrUpdate createOrUpdate = null;
      if (statusCode == HttpStatus.SC_OK) {
        createOrUpdate = CreateOrUpdate.UPDATE;
      } else if (statusCode == HttpStatus.SC_CREATED) {
        createOrUpdate = CreateOrUpdate.CREATE;
      }
      if (createOrUpdate == null) {
        // resource was not created or updated
        return generateStatusResponse(templateProxyResponse);
      } else {
        if (createOrUpdate == CreateOrUpdate.UPDATE) {
          if (folderServerOldResource != null) {
            if (folderServerOldResource.getPublicationStatus() == BiboStatus.PUBLISHED) {
              return CedarResponse.badRequest()
                  .errorKey(CedarErrorKey.PUBLISHED_RESOURCES_CAN_NOT_BE_CHANGED)
                  .errorMessage("The resource can not be changed since it is published!")
                  .parameter("name", folderServerOldResource.getName())
                  .build();
            }
          }
        }
        // resource was updated
        HttpEntity templateEntity = templateProxyResponse.getEntity();
        if (templateEntity != null) {
          String templateEntityContent = EntityUtils.toString(templateEntity);
          JsonNode templateJsonNode = JsonMapper.MAPPER.readTree(templateEntityContent);

          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          String newName = ModelUtil.extractNameFromResource(nodeType, templateJsonNode).getValue();
          String newDescription = ModelUtil.extractDescriptionFromResource(nodeType, templateJsonNode).getValue();
          resourceRequestBody.put("name", newName);
          resourceRequestBody.put("description", newDescription);
          if (folder != null) {
            resourceRequestBody.put("parentId", folder.getId());
            resourceRequestBody.put("nodeType", nodeType.getValue());
          }
          String resourceRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(resourceRequestBody);

          // Check if this was a rename.
          // If it was, we need to reindex the node and the children
          // Otherwise we just reindex the content child
          boolean wasRename = false;
          if (currentName == null || !currentName.equals(newName)) {
            wasRename = true;
          }

          String resourceUrl = microserviceUrlUtil.getWorkspace().getResourceWithId(id);

          HttpResponse folderServerUpdateResponse = ProxyUtil.proxyPut(resourceUrl, context,
              resourceRequestBodyAsString);
          int folderServerUpdateStatusCode = folderServerUpdateResponse.getStatusLine().getStatusCode();
          HttpEntity resourceEntity = folderServerUpdateResponse.getEntity();
          if (resourceEntity != null) {
            if (HttpStatus.SC_OK == folderServerUpdateStatusCode) {
              if (templateEntityContent != null) {
                // update the resource on the index
                FolderServerResource folderServerResource = JsonMapper.MAPPER.readValue(folderServerUpdateResponse
                    .getEntity().getContent(), FolderServerResource.class);
                if (wasRename) {
                  indexRemoveDocument(id);
                  createIndexResource(folderServerResource, templateJsonNode, context);
                } else {
                  updateIndexResource(folderServerResource, templateJsonNode, context);
                }
                return newResponseWithValidationHeader(Response.ok(), templateProxyResponse, templateEntityContent);
              } else {
                return Response.ok().build();
              }
            } else {
              log.error("Resource not updated #1, rollback resource and signal error");
              return Response.status(folderServerUpdateStatusCode).entity(resourceEntity.getContent()).build();
            }
          } else {
            log.error("Resource not updated #2, rollback resource and signal error");
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

  protected Response executeResourceDeleteByProxy(CedarRequestContext context, CedarNodeType nodeType, String id)
      throws CedarException {

    ResourceUri previousVersion = null;

    FolderServerResource folderServerResource = userMustHaveWriteAccessToResource(context, id);
    if (folderServerResource.getType().isVersioned()) {
      if (folderServerResource.getPublicationStatus() == BiboStatus.PUBLISHED) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.PUBLISHED_RESOURCES_CAN_NOT_BE_DELETED)
            .errorMessage("Published resources can not be deleted!")
            .parameter("id", id)
            .parameter("name", folderServerResource.getName())
            .parameter(BIBO_STATUS, folderServerResource.getPublicationStatus())
            .build();
      }
      previousVersion = folderServerResource.getPreviousVersion();
    }

    try {
      String url = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_NO_CONTENT && statusCode != HttpStatus.SC_NOT_FOUND) {
        // resource was not deleted
        return generateStatusResponse(proxyResponse);
      } else {
        if (statusCode == HttpStatus.SC_NOT_FOUND) {
          log.warn("Resource not found on template server, but still trying to delete on workspace. Id:" + id);
        }
        String resourceUrl = microserviceUrlUtil.getWorkspace().getResourceWithId(id);
        HttpResponse resourceDeleteResponse = ProxyUtil.proxyDelete(resourceUrl, context);
        int resourceDeleteStatusCode = resourceDeleteResponse.getStatusLine().getStatusCode();
        if (HttpStatus.SC_NO_CONTENT == resourceDeleteStatusCode) {
          // remove the resource from the index
          indexRemoveDocument(id);
          // reindex the previous version, since that just became the latest
          if (previousVersion != null) {
            String previousId = previousVersion.getValue();
            if (previousId != null) {
              // TODO: what happens if the user does not have read access / and write access to given resource.
              // This is a system level call, it should be executed with such a user
              FolderServerResource folderServerPreviousResource = userMustHaveReadAccessToResource(context, previousId);
              String getResponse = getResourceFromTemplateServer(nodeType, previousId, context);
              if (getResponse != null) {
                JsonNode getJsonNode = null;
                try {
                  getJsonNode = JsonMapper.MAPPER.readTree(getResponse);
                  if (getJsonNode != null) {
                    updateIndexResource(folderServerPreviousResource, getJsonNode, context);
                  }
                } catch (Exception e) {
                  log.error("There was an error while reindexing the new latest version", e);
                }
              }
            }
          }
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
    String url = microserviceUrlUtil.getWorkspace().getFolders();
    FolderServerFolder fsFolder = FolderServerProxy.getFolder(url, folderId, context);
    if (fsFolder == null) {
      throw new CedarObjectNotFoundException("Folder not found by id")
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .parameter("folderId", folderId);
    }
    if (context.getCedarUser().has(CedarPermission.READ_NOT_READABLE_NODE) || fsFolder.currentUserCan(NodePermission
        .READ)) {
      return fsFolder;
    } else {
      throw new CedarPermissionException("You do not have read access to the folder")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_FOLDER)
          .parameter("folderId", folderId);
    }
  }

  protected FolderServerFolder userMustHaveWriteAccessToFolder(CedarRequestContext context, String folderId)
      throws CedarException {
    String url = microserviceUrlUtil.getWorkspace().getFolders();
    FolderServerFolder fsFolder = FolderServerProxy.getFolder(url, folderId, context);
    if (fsFolder == null) {
      throw new CedarObjectNotFoundException("Folder not found by id")
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .parameter("folderId", folderId);
    }
    if (context.getCedarUser().has(CedarPermission.WRITE_NOT_WRITABLE_NODE) || fsFolder.currentUserCan(NodePermission
        .WRITE)) {
      return fsFolder;
    } else {
      throw new CedarPermissionException("You do not have write access to the folder")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_FOLDER)
          .parameter("folderId", folderId);
    }
  }

  protected FolderServerResource userMustHaveReadAccessToResource(CedarRequestContext context, String resourceId) throws
      CedarException {
    String url = microserviceUrlUtil.getWorkspace().getResources();
    FolderServerResource fsResource = FolderServerProxy.getResource(url, resourceId, context);
    if (fsResource == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
          .parameter("resourceId", resourceId);
    }
    if (context.getCedarUser().has(CedarPermission.READ_NOT_READABLE_NODE) || fsResource.currentUserCan
        (NodePermission.READ)) {
      return fsResource;
    } else {
      throw new CedarPermissionException("You do not have read access to the resource")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_RESOURCE)
          .parameter("resourceId", resourceId);
    }
  }

  protected FolderServerResource userMustHaveWriteAccessToResource(CedarRequestContext context, String resourceId)
      throws CedarException {
    String url = microserviceUrlUtil.getWorkspace().getResources();
    FolderServerResource fsResource = FolderServerProxy.getResource(url, resourceId, context);
    if (fsResource == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
          .parameter("resourceId", resourceId);
    }
    if (context.getCedarUser().has(CedarPermission.WRITE_NOT_WRITABLE_NODE) || fsResource.currentUserCan
        (NodePermission.WRITE)) {
      return fsResource;
    } else {
      throw new CedarPermissionException("You do not have write access to the resource")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_RESOURCE)
          .parameter("resourceId", resourceId);
    }
  }

  protected FolderServerNode userMustHaveReadAccessToNode(CedarRequestContext context, String nodeId) throws
      CedarException {
    CedarObjectNotFoundException notFoundException = null;
    try {
      return userMustHaveReadAccessToResource(context, nodeId);
    } catch (CedarObjectNotFoundException e) {
      notFoundException = e;
    } catch (CedarPermissionException e) {
      throw e;
    }
    try {
      return userMustHaveReadAccessToFolder(context, nodeId);
    } catch (CedarObjectNotFoundException e) {
      notFoundException = e;
    } catch (CedarPermissionException e) {
      throw e;
    }
    if (notFoundException != null) {
      throw notFoundException;
    }
    return null;
  }

  protected FolderServerNode userMustHaveWriteAccessToNode(CedarRequestContext context, String nodeId) throws
      CedarException {
    CedarObjectNotFoundException notFoundException;
    try {
      return userMustHaveWriteAccessToResource(context, nodeId);
    } catch (CedarObjectNotFoundException e) {
      notFoundException = e;
    } catch (CedarPermissionException e) {
      throw e;
    }
    try {
      return userMustHaveWriteAccessToFolder(context, nodeId);
    } catch (CedarObjectNotFoundException e) {
      notFoundException = e;
    } catch (CedarPermissionException e) {
      throw e;
    }
    if (notFoundException != null) {
      throw notFoundException;
    }
    return null;
  }

  protected Response executeResourcePermissionGetByProxy(String resourceId, CedarRequestContext context) throws
      CedarProcessingException {
    String url = microserviceUrlUtil.getWorkspace().getResourceWithIdPermissions(resourceId);
    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return buildResponse(proxyResponse);
  }

  protected Response executeResourceReportGetByProxy(String resourceId, CedarRequestContext context) throws
      CedarProcessingException {
    try {
      String resourceUrl = microserviceUrlUtil.getWorkspace().getResourceWithIdReport(resourceId);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(resourceUrl, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Response.status(statusCode).entity(resourceWithExpandedProvenanceInfo(proxyResponse, context,
            FolderServerResourceReport.class)).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response executeResourcePermissionPutByProxy(String resourceId, CedarRequestContext context) throws
      CedarProcessingException, CedarBadRequestException {
    String url = microserviceUrlUtil.getWorkspace().getResourceWithIdPermissions(resourceId);
    HttpResponse proxyResponse = ProxyUtil.proxyPut(url, context);
    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    if (statusCode == HttpStatus.SC_OK) {
      searchPermissionEnqueueService.resourcePermissionsChanged(resourceId);
    }
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);
    return buildResponse(proxyResponse);
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

  /**
   * Private methods: move these into a separate service
   */

  protected void createIndexResource(FolderServerResource folderServerResource, JsonNode templateJsonNode,
                                     CedarRequestContext c) throws CedarProcessingException {
    String newId = folderServerResource.getId();
    IndexedDocumentId parentId = nodeIndexingService.indexDocument(newId, folderServerResource.getName(),
        folderServerResource.getType());
    IndexedDocumentId indexedContentId = contentIndexingService.indexResource(folderServerResource, templateJsonNode,
        c, parentId);
    // The content was not indexed, we should remove the node
    if (indexedContentId == null) {
      nodeIndexingService.removeDocumentFromIndex(parentId);
      // and do not index permissions
    } else {
      // othwerwise index permissions
      searchPermissionEnqueueService.resourceCreated(newId, parentId);
    }
  }

  protected void createIndexFolder(FolderServerFolder folderServerFolder, CedarRequestContext c) throws
      CedarProcessingException {
    String newId = folderServerFolder.getId();
    IndexedDocumentId parentId = nodeIndexingService.indexDocument(newId, folderServerFolder.getName(),
        folderServerFolder.getType());
    contentIndexingService.indexFolder(folderServerFolder, c, parentId);
    searchPermissionEnqueueService.folderCreated(newId, parentId);
  }

  protected void updateIndexResource(FolderServerResource folderServerResource, JsonNode templateJsonNode,
                                     CedarRequestContext c) throws CedarProcessingException {
    // get the id old id based on the cid
    IndexedDocumentId parentId = nodeSearchingService.getByCedarId(folderServerResource.getId());
    IndexedDocumentId indexedContentId = contentIndexingService.updateResource(folderServerResource,
        templateJsonNode, c, parentId);
    // The content was not indexed: remove permissions and parent node
    if (indexedContentId == null) {
      userPermissionIndexingService.removeDocumentFromIndex(folderServerResource.getId(), parentId);
      groupPermissionIndexingService.removeDocumentFromIndex(folderServerResource.getId(), parentId);
      nodeIndexingService.removeDocumentFromIndex(parentId);
    }
  }

  protected void updateIndexFolder(FolderServerFolder folderServerFolder, CedarRequestContext c) throws
      CedarProcessingException {
    // get the id old id based on the cid
    IndexedDocumentId parentId = nodeSearchingService.getByCedarId(folderServerFolder.getId());
    contentIndexingService.updateFolder(folderServerFolder, c, parentId);
  }

  protected void indexRemoveDocument(String id) throws CedarProcessingException {
    IndexedDocumentId parent = nodeSearchingService.getByCedarId(id);
    contentIndexingService.removeDocumentFromIndex(id, parent);
    userPermissionIndexingService.removeDocumentFromIndex(id, parent);
    groupPermissionIndexingService.removeDocumentFromIndex(id, parent);
    nodeIndexingService.removeDocumentFromIndex(id);
  }

  protected Response updateFolderNameAndDescriptionOnFolderServer(CedarRequestContext c, String id) throws
      CedarException {
    FolderServerFolder folderServerFolder = userMustHaveWriteAccessToFolder(c, id);
    String oldName = folderServerFolder.getName();

    String url = microserviceUrlUtil.getWorkspace().getFolderWithId(id);

    HttpResponse proxyResponse = ProxyUtil.proxyPut(url, c);
    ProxyUtil.proxyResponseHeaders(proxyResponse, response);

    int statusCode = proxyResponse.getStatusLine().getStatusCode();
    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      try {
        if (HttpStatus.SC_OK == statusCode) {
          // update the folder on the index
          FolderServerFolder folderServerFolderUpdated = JsonMapper.MAPPER.readValue(entity.getContent(),
              FolderServerFolder.class);
          String newName = folderServerFolderUpdated.getName();
          if (oldName == null || !oldName.equals(newName)) {
            indexRemoveDocument(id);
            createIndexFolder(folderServerFolderUpdated, c);
          } else {
            updateIndexFolder(folderServerFolderUpdated, c);
          }
          return Response.ok().entity(resourceWithExpandedProvenanceInfo(proxyResponse, c, FolderServerNode.class))
              .build();
        } else {
          return Response.status(statusCode).entity(entity.getContent()).build();
        }
      } catch (IOException e) {
        throw new CedarProcessingException(e);
      }
    } else {
      return Response.status(statusCode).build();
    }
  }

  protected Response executeResourceVersionsGetByProxy(CedarRequestContext context, String resourceId) throws CedarProcessingException {
    try {
      String resourceUrl = microserviceUrlUtil.getWorkspace().getResourceWithIdVersions(resourceId);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(resourceUrl, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        return Response.status(statusCode).entity(resourceListWithExpandedProvenanceInfo(proxyResponse, context)).build();
      } else {
        return Response.status(statusCode).build();
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  private FolderServerNodeListResponse resourceListWithExpandedProvenanceInfo(HttpResponse proxyResponse,
                                                                              CedarRequestContext context) throws CedarProcessingException {
    try {
      FolderServerNodeListResponse nodeList = null;
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      nodeList = JsonMapper.MAPPER.readValue(responseString, FolderServerNodeListResponse.class);
      //addProvenanceDisplayNames(nodeList, context);
      return nodeList;
    } catch (IOException e) {
      throw new CedarProcessingException(e);
    }
  }

}
