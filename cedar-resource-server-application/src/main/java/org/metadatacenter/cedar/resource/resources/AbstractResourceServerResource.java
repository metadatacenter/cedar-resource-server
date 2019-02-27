package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.FolderServerProxy;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.CustomHttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.*;
import org.metadatacenter.model.*;
import org.metadatacenter.model.folderserver.basic.FolderServerFolder;
import org.metadatacenter.model.folderserver.basic.FolderServerInstance;
import org.metadatacenter.model.folderserver.basic.FolderServerNode;
import org.metadatacenter.model.folderserver.basic.FolderServerResource;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerFolderCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerResourceCurrentUserReport;
import org.metadatacenter.model.folderserver.extract.FolderServerNodeExtract;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.folderserver.extract.FolderServerTemplateExtract;
import org.metadatacenter.model.folderserver.report.FolderServerFolderReport;
import org.metadatacenter.model.folderserver.report.FolderServerInstanceReport;
import org.metadatacenter.model.folderserver.report.FolderServerResourceReport;
import org.metadatacenter.model.folderserver.report.FolderServerTemplateReport;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.assertion.noun.CedarInPlaceParameter;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.PermissionServiceSession;
import org.metadatacenter.server.cache.user.UserSummaryCache;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.security.model.auth.CedarNodePermissions;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.user.CedarUserSummary;
import org.metadatacenter.server.valuerecommender.ValuerecommenderReindexQueueService;
import org.metadatacenter.server.valuerecommender.model.ValuerecommenderReindexMessage;
import org.metadatacenter.server.valuerecommender.model.ValuerecommenderReindexMessageActionType;
import org.metadatacenter.server.valuerecommender.model.ValuerecommenderReindexMessageResourceType;
import org.metadatacenter.util.CedarNodeTypeUtil;
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
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.*;

import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.model.ModelNodeNames.BIBO_STATUS;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

public class AbstractResourceServerResource extends CedarMicroserviceResource {

  private static final Logger log = LoggerFactory.getLogger(AbstractResourceServerResource.class);

  private static final String ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";

  protected static NodeIndexingService nodeIndexingService;
  protected static NodeSearchingService nodeSearchingService;
  protected static SearchPermissionEnqueueService searchPermissionEnqueueService;
  protected static ValuerecommenderReindexQueueService valuerecommenderReindexQueueService;

  protected AbstractResourceServerResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectServices(NodeIndexingService nodeIndexingService,
                                    NodeSearchingService nodeSearchingService,
                                    SearchPermissionEnqueueService searchPermissionEnqueueService,
                                    ValuerecommenderReindexQueueService valuerecommenderReindexQueueService
  ) {
    AbstractResourceServerResource.nodeIndexingService = nodeIndexingService;
    AbstractResourceServerResource.nodeSearchingService = nodeSearchingService;
    AbstractResourceServerResource.searchPermissionEnqueueService = searchPermissionEnqueueService;
    AbstractResourceServerResource.valuerecommenderReindexQueueService = valuerecommenderReindexQueueService;
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

  protected void addProvenanceDisplayName(FolderServerNode resource) throws CedarProcessingException {
    if (resource != null) {
      CedarUserSummary creator = UserSummaryCache.getInstance().getUser(resource.getCreatedBy());
      CedarUserSummary updater = UserSummaryCache.getInstance().getUser(resource.getLastUpdatedBy());
      CedarUserSummary owner = UserSummaryCache.getInstance().getUser(resource.getOwnedBy());
      if (creator != null) {
        resource.setCreatedByUserName(creator.getScreenName());
      }
      if (updater != null) {
        resource.setLastUpdatedByUserName(updater.getScreenName());
      }
      if (owner != null) {
        resource.setOwnedByUserName(owner.getScreenName());
      }
      for (FolderServerNodeExtract pi : resource.getPathInfo()) {
        addProvenanceDisplayName(pi);
      }
    }
  }

  private void addProvenanceDisplayName(FolderServerNodeExtract resource) {
    if (resource != null) {
      CedarUserSummary creator = UserSummaryCache.getInstance().getUser(resource.getCreatedBy());
      CedarUserSummary updater = UserSummaryCache.getInstance().getUser(resource.getLastUpdatedBy());
      CedarUserSummary owner = UserSummaryCache.getInstance().getUser(resource.getOwnedBy());
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
  }

  private void addProvenanceDisplayNames(FolderServerFolderReport report) {
    if (report != null) {
      CedarUserSummary creator = UserSummaryCache.getInstance().getUser(report.getCreatedBy());
      CedarUserSummary updater = UserSummaryCache.getInstance().getUser(report.getLastUpdatedBy());
      CedarUserSummary owner = UserSummaryCache.getInstance().getUser(report.getOwnedBy());
      if (creator != null) {
        report.setCreatedByUserName(creator.getScreenName());
      }
      if (updater != null) {
        report.setLastUpdatedByUserName(updater.getScreenName());
      }
      if (owner != null) {
        report.setOwnedByUserName(owner.getScreenName());
      }
    }
  }

  protected void addProvenanceDisplayNames(FolderServerResourceReport report) {
    for (FolderServerNodeExtract v : report.getVersions()) {
      addProvenanceDisplayName(v);
    }
    for (FolderServerNodeExtract pi : report.getPathInfo()) {
      addProvenanceDisplayName(pi);
    }
    addProvenanceDisplayName(report.getDerivedFromExtract());
    if (report instanceof FolderServerInstanceReport) {
      FolderServerInstanceReport instanceReport = (FolderServerInstanceReport) report;
      addProvenanceDisplayName(instanceReport.getIsBasedOnExtract());
    }
  }

  protected void addProvenanceDisplayNames(FolderServerNodeListResponse nodeList) {
    for (FolderServerNodeExtract r : nodeList.getResources()) {
      addProvenanceDisplayName(r);
    }
    if (nodeList.getPathInfo() != null) {
      for (FolderServerNodeExtract pi : nodeList.getPathInfo()) {
        addProvenanceDisplayName(pi);
      }
    }
  }

  protected <T extends FolderServerNode> T resourceWithProvenanceDisplayNames(HttpResponse proxyResponse,
                                                                              Class<T> klazz)
      throws CedarProcessingException {
    T resource = deserializeResource(proxyResponse, klazz);
    addProvenanceDisplayName(resource);
    return resource;
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
  protected Response executeResourceCreationOnTemplateServerAndGraphDb(CedarRequestContext context,
                                                                       CedarNodeType nodeType,
                                                                       Optional<String> folderId)
      throws CedarException {

    String folderIdS;

    CedarParameter folderIdP = context.request().wrapQueryParam(QP_FOLDER_ID, folderId);
    if (folderIdP.isEmpty()) {
      folderIdS = context.getCedarUser().getHomeFolderId();
    } else {
      folderIdS = folderIdP.stringValue();
    }
    FolderServerFolderCurrentUserReport folderReport = userMustHaveWriteAccessToFolder(context, folderIdS);

    FolderServerFolder folder = FolderServerFolder.fromFolderServerFolderCurrentUserReport(folderReport);

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
          String id = ModelUtil.extractAtIdFromResource(nodeType, templateJsonNode).getValue();

          JsonPointerValuePair namePair = ModelUtil.extractNameFromResource(nodeType, templateJsonNode);
          JsonPointerValuePair descriptionPair = ModelUtil.extractDescriptionFromResource(nodeType, templateJsonNode);
          JsonPointerValuePair identifierPair = ModelUtil.extractIdentifierFromResource(nodeType, templateJsonNode);

          FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);

          CedarParameter name = new CedarInPlaceParameter("name", namePair.getValue());
          context.must(name).be(NonEmpty);

          CedarParameter versionP = new CedarInPlaceParameter("version",
              ModelUtil.extractVersionFromResource(nodeType, templateJsonNode).getValue());
          CedarParameter publicationStatusP = new CedarInPlaceParameter("publicationStatus",
              ModelUtil.extractPublicationStatusFromResource(nodeType, templateJsonNode).getValue());
          CedarParameter isBasedOnP = new CedarInPlaceParameter("isBasedOn",
              ModelUtil.extractIsBasedOnFromInstance(templateJsonNode).getValue());

          if (nodeType.isVersioned()) {
            context.must(versionP).be(NonEmpty);
            context.must(publicationStatusP).be(NonEmpty);
          }
          if (nodeType == CedarNodeType.INSTANCE) {
            context.must(isBasedOnP).be(NonEmpty);
          }

          if (CedarNodeTypeUtil.isNotValidForRestCall(nodeType)) {
            return CedarResponse.badRequest()
                .errorMessage("You passed an illegal nodeType:'" + nodeType +
                    "'. The allowed values are:" + CedarNodeTypeUtil.getValidNodeTypesForRestCalls())
                .errorKey(CedarErrorKey.INVALID_NODE_TYPE)
                .parameter("invalidNodeTypes", nodeType)
                .parameter("allowedNodeTypes", CedarNodeTypeUtil.getValidNodeTypeValuesForRestCalls())
                .build();
          }

          String versionString = versionP.stringValue();
          ResourceVersion version = ResourceVersion.forValue(versionString);

          String publicationStatusString = publicationStatusP.stringValue();
          BiboStatus publicationStatus = BiboStatus.forValue(publicationStatusString);

          String isBasedOnString = isBasedOnP.stringValue();

          CedarParameter description = new CedarInPlaceParameter("description", descriptionPair.getValue());
          CedarParameter identifier = new CedarInPlaceParameter("identifier", identifierPair.getValue());

          // Later we will guarantee some kind of uniqueness for the resource names
          // Currently we allow duplicate names, the id is the PK
          FolderServerResource brandNewResource = WorkspaceObjectBuilder.forNodeType(nodeType, id,
              name.stringValue(), description.stringValue(), identifier.stringValue(), version, publicationStatus);
          if (nodeType.isVersioned()) {
            brandNewResource.setLatestVersion(true);
            brandNewResource.setLatestDraftVersion(publicationStatus == BiboStatus.DRAFT);
            brandNewResource.setLatestPublishedVersion(publicationStatus == BiboStatus.PUBLISHED);
          }
          if (nodeType == CedarNodeType.INSTANCE) {
            FolderServerInstance brandNewInstance = (FolderServerInstance) brandNewResource;
            brandNewInstance.setIsBasedOn(isBasedOnString);
          }
          FolderServerResource newResource = folderSession.createResourceAsChildOfId(brandNewResource, folder.getId());

          if (newResource == null) {
            return CedarResponse.badRequest()
                .parameter("id", id)
                .parameter("parentId", folder.getId())
                .parameter("resourceType", nodeType.getValue())
                .errorKey(CedarErrorKey.RESOURCE_NOT_CREATED)
                .errorMessage("The resource was not created!")
                .build();
          }
          UriBuilder builder = uriInfo.getAbsolutePathBuilder();
          URI uri = builder.path(CedarUrlUtil.urlEncode(id)).build();

          return Response.created(uri).entity(templateJsonNode).build();
        } else {
          return CedarResponse.internalServerError().errorKey(CedarErrorKey.RESOURCE_NOT_CREATED).build();
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

  protected Response executeResourceGetByProxyFromTemplateServer(CedarNodeType nodeType, String id,
                                                                 CedarRequestContext context)
      throws CedarProcessingException {
    return executeResourceGetByProxyFromTemplateServer(nodeType, id, Optional.empty(), context);
  }

  protected Response executeResourceGetByProxyFromTemplateServer(CedarNodeType nodeType, String id,
                                                                 Optional<String> format, CedarRequestContext context)
      throws CedarProcessingException {
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

  protected Response getDetails(CedarRequestContext context, String id) throws CedarException {

    FolderServerResourceCurrentUserReport resourceReport = userMustHaveReadAccessToResource(context, id);

    FolderServerResource resource = FolderServerResource.fromFolderServerResourceCurrentUserReport(resourceReport);

    addProvenanceDisplayName(resource);
    return CedarResponse.ok().entity(resource).build();
  }

  protected Response executeResourcePutByProxy(CedarRequestContext context, CedarNodeType nodeType, String id) throws
      CedarException {
    return executeResourcePutByProxy(context, nodeType, id, null, null);
  }

  protected Response executeResourcePutByProxy(CedarRequestContext context, CedarNodeType nodeType, String id,
                                               FolderServerFolder folder, String content) throws CedarException {

    FolderServerResourceCurrentUserReport folderServerResourceReport = userMustHaveWriteAccessToResource(context, id);
    FolderServerResource folderServerOldResource =
        FolderServerResource.fromFolderServerResourceCurrentUserReport(folderServerResourceReport);

    try {
      String url = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id);

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
          String newIdentifier = ModelUtil.extractIdentifierFromResource(nodeType, templateJsonNode).getValue();

          FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
          FolderServerResource resource = folderSession.findResourceById(id);

          if (resource == null) {
            return CedarResponse.notFound()
                .id(id)
                .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
                .errorMessage("The resource can not be found by id")
                .build();
          } else {
            Map<NodeProperty, String> updateFields = new HashMap<>();
            updateFields.put(NodeProperty.DESCRIPTION, newDescription);
            updateFields.put(NodeProperty.NAME, newName);
            updateFields.put(NodeProperty.IDENTIFIER, newIdentifier);
            FolderServerResource updatedResource =
                folderSession.updateResourceById(id, resource.getType(), updateFields);
            if (updatedResource == null) {
              return CedarResponse.internalServerError().build();
            } else {
              updateIndexResource(updatedResource, context);
              updateValuerecommenderResource(updatedResource);
              return Response.ok().entity(updatedResource).build();
            }
          }
        } else {
          return Response.ok().build();
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response executeResourceDelete(CedarRequestContext c, CedarNodeType nodeType, String id)
      throws CedarException {

    // Check delete preconditions

    FolderServerResourceCurrentUserReport resourceReport = userMustHaveWriteAccessToResource(c, id);

    if (resourceReport.getType().isVersioned()) {
      if (resourceReport.getPublicationStatus() == BiboStatus.PUBLISHED) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.PUBLISHED_RESOURCES_CAN_NOT_BE_DELETED)
            .errorMessage("Published resources can not be deleted!")
            .parameter("id", id)
            .parameter("name", resourceReport.getName())
            .parameter(BIBO_STATUS, resourceReport.getPublicationStatus())
            .build();
      }
    }

    // Delete from template server

    try {
      String url = microserviceUrlUtil.getTemplate().getNodeTypeWithId(nodeType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_NO_CONTENT && statusCode != HttpStatus.SC_NOT_FOUND) {
        // resource was not deleted
        return generateStatusResponse(proxyResponse);
      } else {
        if (statusCode == HttpStatus.SC_NOT_FOUND) {
          log.warn("Resource not found on template server, but still trying to delete on workspace. Id:" + id);
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

    ResourceUri previousVersion = null;
    if (resourceReport.getType().isVersioned() && resourceReport.isLatestVersion() != null &&
        resourceReport.isLatestVersion()) {
      previousVersion = resourceReport.getPreviousVersion();
    }

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    boolean deleted = folderSession.deleteResourceById(id);
    if (deleted) {
      if (previousVersion != null) {
        folderSession.setLatestVersion(previousVersion.getValue());
        folderSession.setLatestPublishedVersion(previousVersion.getValue());
      }
    } else {
      return CedarResponse.internalServerError()
          .id(id)
          .errorKey(CedarErrorKey.RESOURCE_NOT_DELETED)
          .errorMessage("The resource can not be delete by id")
          .build();
    }

    removeIndexDocument(id);
    removeValuerecommenderResource(FolderServerResource.fromFolderServerResourceCurrentUserReport(resourceReport));
    // reindex the previous version, since that just became the latest
    if (resourceReport.hasPreviousVersion()) {
      String previousId = resourceReport.getPreviousVersion().getValue();
      FolderServerResourceCurrentUserReport folderServerPreviousResource =
          userMustHaveReadAccessToResource(c, previousId);
      String getResponse = getResourceFromTemplateServer(nodeType, previousId, c);
      if (getResponse != null) {
        JsonNode getJsonNode = null;
        try {
          getJsonNode = JsonMapper.MAPPER.readTree(getResponse);
          if (getJsonNode != null) {
            FolderServerResource folderServerPreviousR =
                FolderServerResource.fromFolderServerResourceCurrentUserReport(folderServerPreviousResource);
            updateIndexResource(folderServerPreviousR, c);
          }
        } catch (Exception e) {
          log.error("There was an error while reindexing the new latest version", e);
        }
      }
    }

    return Response.noContent().build();
  }

  protected FolderServerFolderCurrentUserReport userMustHaveReadAccessToFolder(CedarRequestContext context,
                                                                               String folderId) throws CedarException {
    FolderServerFolderCurrentUserReport fsFolder = FolderServerProxy.getFolderCurrentUserReport(context, folderId);
    if (fsFolder == null) {
      throw new CedarObjectNotFoundException("Folder not found by id")
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .parameter("folderId", folderId);
    }
    if (context.getCedarUser().has(CedarPermission.READ_NOT_READABLE_NODE) ||
        fsFolder.getCurrentUserPermissions().isCanRead()) {
      return fsFolder;
    } else {
      throw new CedarPermissionException("You do not have read access to the folder")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_FOLDER)
          .parameter("folderId", folderId);
    }
  }

  protected FolderServerFolderCurrentUserReport userMustHaveWriteAccessToFolder(CedarRequestContext context,
                                                                                String folderId) throws CedarException {
    FolderServerFolderCurrentUserReport fsFolder = FolderServerProxy.getFolderCurrentUserReport(context, folderId);
    if (fsFolder == null) {
      throw new CedarObjectNotFoundException("Folder not found by id")
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .parameter("folderId", folderId);
    }
    if (context.getCedarUser().has(CedarPermission.WRITE_NOT_WRITABLE_NODE) ||
        fsFolder.getCurrentUserPermissions().isCanWrite()) {
      return fsFolder;
    } else {
      throw new CedarPermissionException("You do not have write access to the folder")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_FOLDER)
          .parameter("folderId", folderId);
    }
  }

  protected FolderServerResourceCurrentUserReport userMustHaveReadAccessToResource(CedarRequestContext context,
                                                                                   String resourceId)
      throws CedarException {
    FolderServerResourceCurrentUserReport
        fsResource = FolderServerProxy.getResourceCurrentUserReport(context, cedarConfig, resourceId);
    if (fsResource == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
          .parameter("resourceId", resourceId);
    }
    if (context.getCedarUser().has(CedarPermission.READ_NOT_READABLE_NODE) ||
        fsResource.getCurrentUserPermissions().isCanRead()) {
      return fsResource;
    } else {
      throw new CedarPermissionException("You do not have read access to the resource")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_RESOURCE)
          .parameter("resourceId", resourceId);
    }
  }

  protected FolderServerResourceCurrentUserReport userMustHaveWriteAccessToResource(CedarRequestContext context,
                                                                                    String resourceId)
      throws CedarException {
    String url = microserviceUrlUtil.getWorkspace().getResources();
    FolderServerResourceCurrentUserReport
        fsResource = FolderServerProxy.getResourceCurrentUserReport(context, cedarConfig, resourceId);
    if (fsResource == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
          .parameter("resourceId", resourceId);
    }
    if (context.getCedarUser().has(CedarPermission.WRITE_NOT_WRITABLE_NODE) ||
        fsResource.getCurrentUserPermissions().isCanWrite()) {
      return fsResource;
    } else {
      throw new CedarPermissionException("You do not have write access to the resource")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_RESOURCE)
          .parameter("resourceId", resourceId);
    }
  }

  protected FolderServerNode userMustHaveReadAccessToNode(CedarRequestContext context, String nodeId) throws
      CedarException {
    Exception genericException;
    try {
      return userMustHaveReadAccessToResource(context, nodeId);
    } catch (CedarPermissionException e) {
      throw e;
    } catch (Exception e) {
      genericException = e;
    }
    try {
      return userMustHaveReadAccessToFolder(context, nodeId);
    } catch (CedarPermissionException e) {
      throw e;
    } catch (Exception e) {
      if (genericException == null) {
        genericException = e;
      }
    }
    if (genericException != null) {
      throw new CedarProcessingException(genericException);
    }
    return null;
  }

  protected FolderServerNode userMustHaveWriteAccessToNode(CedarRequestContext context, String nodeId) throws
      CedarException {
    Exception genericException;
    try {
      return userMustHaveWriteAccessToResource(context, nodeId);
    } catch (CedarPermissionException e) {
      throw e;
    } catch (Exception e) {
      genericException = e;
    }
    try {
      return userMustHaveWriteAccessToFolder(context, nodeId);
    } catch (CedarPermissionException e) {
      throw e;
    } catch (Exception e) {
      if (genericException == null) {
        genericException = e;
      }
    }
    if (genericException != null) {
      throw new CedarProcessingException(genericException);
    }
    return null;
  }

  protected Response generateNodePermissionsResponse(CedarRequestContext c, String id) throws
      CedarException {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerNode node = folderSession.findNodeById(id);
    if (node == null) {
      return CedarResponse.notFound()
          .errorKey(CedarErrorKey.NODE_NOT_FOUND)
          .errorMessage("Node not found")
          .parameter("id", id)
          .build();
    }

    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(c);

    userMustHaveReadAccess(permissionSession, id);

    CedarNodePermissions permissions = permissionSession.getNodePermissions(id);
    return Response.ok().entity(permissions).build();
  }

  protected Response updateNodePermissions(CedarRequestContext c, String id) throws CedarException {

    c.must(c.request().getRequestBody()).be(NonEmpty);
    JsonNode permissionUpdateRequest = c.request().getRequestBody().asJson();

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(c);

    CedarNodePermissionsRequest permissionsRequest = null;
    try {
      permissionsRequest = JsonMapper.MAPPER.treeToValue(permissionUpdateRequest, CedarNodePermissionsRequest.class);
    } catch (JsonProcessingException e) {
      log.error("Error while reading permission update request", e);
    }

    FolderServerNode node = folderSession.findNodeById(id);
    if (node == null) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.NODE_NOT_FOUND)
          .errorMessage("The node can not be found by id")
          .build();
    } else {
      BackendCallResult backendCallResult = permissionSession.updateNodePermissions(id, permissionsRequest);
      if (backendCallResult.isError()) {
        throw new CedarBackendException(backendCallResult);
      }

      if (node.getType() == CedarNodeType.FOLDER) {
        searchPermissionEnqueueService.folderPermissionsChanged(id);
      } else {
        searchPermissionEnqueueService.resourcePermissionsChanged(id);
      }

      CedarNodePermissions permissions = permissionSession.getNodePermissions(id);
      return Response.ok().entity(permissions).build();
    }
  }

  /**
   * Private methods: move these into a separate service
   */

  private ValuerecommenderReindexMessage buildValuerecommenderEvent(FolderServerResource folderServerResource,
                                                                    ValuerecommenderReindexMessageActionType actionType) {
    ValuerecommenderReindexMessage event = null;
    if (folderServerResource.getType() == CedarNodeType.TEMPLATE) {
      event = new ValuerecommenderReindexMessage(folderServerResource.getId(), null,
          ValuerecommenderReindexMessageResourceType.TEMPLATE, actionType);
    } else if (folderServerResource.getType() == CedarNodeType.INSTANCE) {
      FolderServerInstance instance = (FolderServerInstance) folderServerResource;
      event = new ValuerecommenderReindexMessage(instance.getIsBasedOn().getValue(), instance.getId(),
          ValuerecommenderReindexMessageResourceType.INSTANCE, actionType);
    }
    return event;
  }

  protected void createIndexResource(FolderServerResource folderServerResource, CedarRequestContext c)
      throws CedarProcessingException {
    nodeIndexingService.indexDocument(folderServerResource, c);
  }

  protected void createIndexFolder(FolderServerFolder folderServerFolder, CedarRequestContext c) throws
      CedarProcessingException {
    nodeIndexingService.indexDocument(folderServerFolder, c);
  }

  protected void createValuerecommenderResource(FolderServerResource folderServerResource) {
    ValuerecommenderReindexMessage event =
        buildValuerecommenderEvent(folderServerResource, ValuerecommenderReindexMessageActionType.CREATED);
    if (event != null) {
      valuerecommenderReindexQueueService.enqueueEvent(event);
    }
  }

  protected void updateIndexResource(FolderServerResource folderServerResource, CedarRequestContext c)
      throws CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(folderServerResource.getId());
    nodeIndexingService.indexDocument(folderServerResource, c);
  }

  protected void updateIndexFolder(FolderServerFolder folderServerFolder, CedarRequestContext c) throws
      CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(folderServerFolder.getId());
    nodeIndexingService.indexDocument(folderServerFolder, c);
  }

  protected void updateValuerecommenderResource(FolderServerResource folderServerResource) {
    ValuerecommenderReindexMessage event =
        buildValuerecommenderEvent(folderServerResource, ValuerecommenderReindexMessageActionType.UPDATED);
    if (event != null) {
      valuerecommenderReindexQueueService.enqueueEvent(event);
    }
  }

  protected void removeIndexDocument(String id) throws CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(id);
  }

  protected void removeValuerecommenderResource(FolderServerResource folderServerResource) {
    ValuerecommenderReindexMessage event =
        buildValuerecommenderEvent(folderServerResource, ValuerecommenderReindexMessageActionType.DELETED);
    if (event != null) {
      valuerecommenderReindexQueueService.enqueueEvent(event);
    }
  }

  protected Response updateFolderNameAndDescriptionInGraphDb(CedarRequestContext c, String id) throws
      CedarException {
    FolderServerFolderCurrentUserReport folderServerFolder = userMustHaveWriteAccessToFolder(c, id);
    String oldName = folderServerFolder.getName();

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    CedarParameter name = c.request().getRequestBody().get("name");

    String nameV = null;
    if (!name.isEmpty()) {
      nameV = name.stringValue();
      nameV = nameV.trim();
      String normalizedName = folderSession.sanitizeName(nameV);
      if (!normalizedName.equals(nameV)) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.UPDATE_INVALID_FOLDER_NAME)
            .errorMessage("The folder name contains invalid characters!")
            .parameter("name", name.stringValue())
            .build();
      }
    }

    CedarParameter description = c.request().getRequestBody().get("description");

    String descriptionV = null;
    if (!description.isEmpty()) {
      descriptionV = description.stringValue();
      descriptionV = descriptionV.trim();
    }

    if ((name == null || name.isEmpty()) && (description == null || description.isEmpty())) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.MISSING_NAME_AND_DESCRIPTION)
          .errorMessage("You must supply the new description or the new name of the folder!")
          .build();
    }

    FolderServerFolder folder = folderSession.findFolderById(id);
    if (folder == null) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.FOLDER_NOT_FOUND)
          .errorMessage("The folder can not be found by id")
          .build();
    } else {
      Map<NodeProperty, String> updateFields = new HashMap<>();
      if (descriptionV != null) {
        updateFields.put(NodeProperty.DESCRIPTION, descriptionV);
      }
      if (nameV != null) {
        updateFields.put(NodeProperty.NAME, nameV);
      }
      FolderServerFolder folderServerFolderUpdated = folderSession.updateFolderById(id, updateFields);

      String newName = folderServerFolderUpdated.getName();
      if (oldName == null || !oldName.equals(newName)) {
        removeIndexDocument(id);
        createIndexFolder(folderServerFolderUpdated, c);
      } else {
        updateIndexFolder(folderServerFolderUpdated, c);
      }


      addProvenanceDisplayName(folderServerFolderUpdated);
      return Response.ok().entity(folderServerFolderUpdated).build();
    }
  }

  protected Response generateNodeVersionsResponse(CedarRequestContext c, String id)
      throws CedarException {

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerResource resource = folderSession.findResourceById(id);
    if (resource == null) {
      return CedarResponse.notFound()
          .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
          .errorMessage("Resource not found")
          .parameter("id", id)
          .build();
    }

    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(c);

    userMustHaveReadAccess(permissionSession, id);

    if (!resource.getType().isVersioned()) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .errorMessage("Invalid resource type")
          .parameter("nodeType", resource.getType().getValue())
          .build();
    }

    FolderServerNodeListResponse r = new FolderServerNodeListResponse();
    NodeListRequest req = new NodeListRequest();
    req.setId(id);
    r.setRequest(req);

    NodeListQueryType nlqt = NodeListQueryType.ALL_VERSIONS;
    r.setNodeListQueryType(nlqt);

    List<FolderServerResourceExtract> resources = folderSession.getVersionHistory(id);
    r.setResources(resources);

    r.setCurrentOffset(0);
    r.setTotalCount(resources.size());

    for (FolderServerNodeExtract node : r.getResources()) {
      addProvenanceDisplayName(node);
    }

    return Response.ok().entity(r).build();
  }

  protected void userMustHaveReadAccess(PermissionServiceSession permissionServiceSession, String id)
      throws CedarException {
    boolean b = permissionServiceSession.userHasReadAccessToNode(id);
    if (!b) {
      throw new CedarPermissionException("You do not have read access to the node")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_NODE)
          .parameter("nodeId", id);
    }
  }

  protected void decorateResourceWithNumberOfInstances(CedarRequestContext c, FolderServiceSession folderSession,
                                                       FolderServerTemplateReport templateReport) {
    templateReport.setNumberOfInstances(folderSession.getNumberOfInstances(templateReport.getId()));
  }

  protected void decorateResourceWithVersionHistory(CedarRequestContext c, FolderServiceSession folderSession,
                                                    FolderServerResourceReport resourceReport) {
    List<FolderServerResourceExtract> allVersions = folderSession.getVersionHistory(resourceReport.getId());
    List<FolderServerResourceExtract> allVersionsWithPermission =
        folderSession.getVersionHistoryWithPermission(resourceReport.getId());
    Map<String, FolderServerResourceExtract> accessibleMap = new HashMap<>();
    for (FolderServerResourceExtract e : allVersionsWithPermission) {
      accessibleMap.put(e.getId(), e);
    }

    List<FolderServerResourceExtract> visibleVersions = new ArrayList<>();
    for (FolderServerResourceExtract v : allVersions) {
      if (accessibleMap.containsKey(v.getId())) {
        visibleVersions.add(v);
      } else {
        visibleVersions.add(FolderServerNodeExtract.anonymous(v));
      }
    }
    resourceReport.setVersions(visibleVersions);
  }

  protected void decorateResourceWithIsBasedOn(FolderServiceSession folderSession,
                                               PermissionServiceSession permissionServiceSession,
                                               FolderServerInstanceReport instanceReport) {
    if (instanceReport.getIsBasedOn() != null) {
      FolderServerTemplateExtract resourceExtract =
          (FolderServerTemplateExtract) folderSession.findResourceExtractById(instanceReport.getIsBasedOn());
      if (resourceExtract != null) {
        boolean hasReadAccess = permissionServiceSession.userHasReadAccessToNode(resourceExtract.getId());
        if (hasReadAccess) {
          instanceReport.setIsBasedOnExtract(resourceExtract);
        } else {
          instanceReport.setIsBasedOnExtract(FolderServerNodeExtract.anonymous(resourceExtract));
        }
      }
    }
  }

  protected void decorateResourceWithDerivedFrom(FolderServiceSession folderSession,
                                                 PermissionServiceSession permissionServiceSession,
                                                 FolderServerResourceReport resourceReport) {
    if (resourceReport.getDerivedFrom() != null && resourceReport.getDerivedFrom().getValue() != null) {
      FolderServerResourceExtract resourceExtract =
          folderSession.findResourceExtractById(resourceReport.getDerivedFrom());
      if (resourceExtract != null) {
        boolean hasReadAccess = permissionServiceSession.userHasReadAccessToNode(resourceExtract.getId());
        if (hasReadAccess) {
          resourceReport.setDerivedFromExtract(resourceExtract);
        } else {
          resourceReport.setDerivedFromExtract(FolderServerNodeExtract.anonymous(resourceExtract));
        }
      }
    }
  }

  protected Response generateNodeReportResponse(CedarRequestContext c, String id) throws CedarException {

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerResource resource = folderSession.findResourceById(id);
    if (resource == null) {
      return CedarResponse.notFound()
          .errorKey(CedarErrorKey.RESOURCE_NOT_FOUND)
          .errorMessage("Resource not found")
          .parameter("id", id)
          .build();
    }

    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(c);

    userMustHaveReadAccess(permissionSession, resource.getId());

    folderSession.addPathAndParentId(resource);

    List<FolderServerNodeExtract> pathInfo = folderSession.findNodePathExtract(resource);
    resource.setPathInfo(pathInfo);

    FolderServerResourceReport resourceReport = FolderServerResourceReport.fromResource(resource);

    decorateResourceWithDerivedFrom(folderSession, permissionSession, resourceReport);
    FolderServerProxy.decorateResourceWithCurrentUserPermissions(c, cedarConfig, resourceReport);

    if (resource.getType() == CedarNodeType.INSTANCE) {
      decorateResourceWithIsBasedOn(folderSession, permissionSession,
          (FolderServerInstanceReport) resourceReport);
    } else if (resource.getType() == CedarNodeType.FIELD) {
      decorateResourceWithVersionHistory(c, folderSession, resourceReport);
    } else if (resource.getType() == CedarNodeType.ELEMENT) {
      decorateResourceWithVersionHistory(c, folderSession, resourceReport);
    } else if (resource.getType() == CedarNodeType.TEMPLATE) {
      decorateResourceWithNumberOfInstances(c, folderSession, (FolderServerTemplateReport) resourceReport);
      decorateResourceWithVersionHistory(c, folderSession, resourceReport);
    }

    addProvenanceDisplayName(resourceReport);
    addProvenanceDisplayNames(resourceReport);
    return Response.ok(resourceReport).build();
  }

}
