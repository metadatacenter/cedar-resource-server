package org.metadatacenter.cedar.resource.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.bridge.GraphDbPermissionReader;
import org.metadatacenter.bridge.PathInfoBuilder;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.constant.CustomHttpConstants;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.*;
import org.metadatacenter.id.CedarCategoryId;
import org.metadatacenter.model.*;
import org.metadatacenter.model.folderserver.basic.*;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerArtifactCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerCategoryCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerFolderCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerSchemaArtifactCurrentUserReport;
import org.metadatacenter.model.folderserver.datagroup.ResourceWithUsersAndUserNamesData;
import org.metadatacenter.model.folderserver.extract.FolderServerArtifactExtract;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.folderserver.extract.FolderServerTemplateExtract;
import org.metadatacenter.model.folderserver.report.FolderServerArtifactReport;
import org.metadatacenter.model.folderserver.report.FolderServerFolderReport;
import org.metadatacenter.model.folderserver.report.FolderServerInstanceReport;
import org.metadatacenter.model.folderserver.report.FolderServerTemplateReport;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.response.FolderServerCategoryListResponse;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.assertion.noun.CedarInPlaceParameter;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryServiceSession;
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
import org.metadatacenter.util.CedarResourceTypeUtil;
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

import static org.keycloak.adapters.CorsHeaders.ACCESS_CONTROL_EXPOSE_HEADERS;
import static org.metadatacenter.constant.CedarQueryParameters.QP_FOLDER_ID;
import static org.metadatacenter.model.ModelNodeNames.BIBO_STATUS;
import static org.metadatacenter.rest.assertion.GenericAssertions.NonEmpty;

public class AbstractResourceServerResource extends CedarMicroserviceResource {

  private static final Logger log = LoggerFactory.getLogger(AbstractResourceServerResource.class);

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

  protected static <T extends FileSystemResource> T deserializeResource(HttpResponse proxyResponse, Class<T> klazz)
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

  protected static void updateNameInObject(CedarResourceType resourceType, JsonNode jsonNode, String name) {
    ((ObjectNode) jsonNode).put(ModelNodeNames.SCHEMA_ORG_NAME, name);
  }

  protected static void updateDescriptionInObject(CedarResourceType resourceType, JsonNode jsonNode,
                                                  String description) {
    ((ObjectNode) jsonNode).put(ModelNodeNames.SCHEMA_ORG_DESCRIPTION, description);
  }

  protected static Response newResponseWithValidationHeader(Response.ResponseBuilder responseBuilder,
                                                            HttpResponse proxyResponse, Object responseContent) {
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

  protected void addProvenanceDisplayName(ResourceWithUsersAndUserNamesData resource) {
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
      if (resource instanceof FileSystemResource) {
        FileSystemResource res = (FileSystemResource) resource;
        for (FolderServerResourceExtract pi : res.getPathInfo()) {
          addProvenanceDisplayName(pi);
        }
      }
    }
  }

  private void addProvenanceDisplayName(FolderServerResourceExtract resource) {
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

  protected void addProvenanceDisplayNames(FolderServerArtifactReport report) {
    for (FolderServerResourceExtract v : report.getVersions()) {
      addProvenanceDisplayName(v);
    }
    for (FolderServerResourceExtract pi : report.getPathInfo()) {
      addProvenanceDisplayName(pi);
    }
    addProvenanceDisplayName(report.getDerivedFromExtract());
    if (report instanceof FolderServerInstanceReport) {
      FolderServerInstanceReport instanceReport = (FolderServerInstanceReport) report;
      addProvenanceDisplayName(instanceReport.getIsBasedOnExtract());
    }
  }

  protected void addProvenanceDisplayNames(FolderServerNodeListResponse nodeList) {
    for (FolderServerResourceExtract r : nodeList.getResources()) {
      addProvenanceDisplayName(r);
    }
    if (nodeList.getPathInfo() != null) {
      for (FolderServerResourceExtract pi : nodeList.getPathInfo()) {
        addProvenanceDisplayName(pi);
      }
    }
  }

  protected void addProvenanceDisplayNames(FolderServerCategoryListResponse categoryList) {
    for (FolderServerCategory c : categoryList.getCategories()) {
      addProvenanceDisplayName(c);
    }
  }

  protected <T extends FileSystemResource> T resourceWithProvenanceDisplayNames(HttpResponse proxyResponse,
                                                                                Class<T> klazz)
      throws CedarProcessingException {
    T resource = deserializeResource(proxyResponse, klazz);
    addProvenanceDisplayName(resource);
    return resource;
  }

  protected Response executeResourcePostToArtifactServer(CedarRequestContext context, CedarResourceType resourceType,
                                                         String content) throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getArtifact().getResourceType(resourceType);

      HttpResponse templateProxyResponse = ProxyUtil.proxyPost(url, context, content);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);

      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // artifact was not created
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

  // Proxy methods for artifact types
  protected Response executeResourceCreationOnArtifactServerAndGraphDb(CedarRequestContext context,
                                                                       CedarResourceType resourceType,
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
      String url = microserviceUrlUtil.getArtifact().getResourceType(resourceType);

      HttpResponse templateProxyResponse = ProxyUtil.proxyPost(url, context);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);

      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_CREATED) {
        // artifact was not created
        return generateStatusResponse(templateProxyResponse);
      } else {
        // artifact was created
        HttpEntity templateProxyResponseEntity = templateProxyResponse.getEntity();
        if (templateProxyResponseEntity != null) {
          String templateEntityContent = EntityUtils.toString(templateProxyResponseEntity);
          JsonNode templateJsonNode = JsonMapper.MAPPER.readTree(templateEntityContent);
          String id = ModelUtil.extractAtIdFromResource(resourceType, templateJsonNode).getValue();

          JsonPointerValuePair namePair = ModelUtil.extractNameFromResource(resourceType, templateJsonNode);
          JsonPointerValuePair descriptionPair =
              ModelUtil.extractDescriptionFromResource(resourceType, templateJsonNode);
          JsonPointerValuePair identifierPair = ModelUtil.extractIdentifierFromResource(resourceType, templateJsonNode);

          FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);

          CedarParameter name = new CedarInPlaceParameter("name", namePair.getValue());
          context.must(name).be(NonEmpty);

          CedarParameter versionP = new CedarInPlaceParameter("version",
              ModelUtil.extractVersionFromResource(resourceType, templateJsonNode).getValue());
          CedarParameter publicationStatusP = new CedarInPlaceParameter("publicationStatus",
              ModelUtil.extractPublicationStatusFromResource(resourceType, templateJsonNode).getValue());
          CedarParameter isBasedOnP = new CedarInPlaceParameter("isBasedOn",
              ModelUtil.extractIsBasedOnFromInstance(templateJsonNode).getValue());

          if (resourceType.isVersioned()) {
            context.must(versionP).be(NonEmpty);
            context.must(publicationStatusP).be(NonEmpty);
          }
          if (resourceType == CedarResourceType.INSTANCE) {
            context.must(isBasedOnP).be(NonEmpty);
          }

          if (CedarResourceTypeUtil.isNotValidForRestCall(resourceType)) {
            return CedarResponse.badRequest()
                .errorMessage("You passed an illegal resourceType:'" + resourceType +
                    "'. The allowed values are:" + CedarResourceTypeUtil.getValidResourceTypesForRestCalls())
                .errorKey(CedarErrorKey.INVALID_RESOURCE_TYPE)
                .parameter("invalidResourceTypes", resourceType)
                .parameter("allowedResourceTypes", CedarResourceTypeUtil.getValidResourceTypeValuesForRestCalls())
                .build();
          }

          String versionString = versionP.stringValue();
          ResourceVersion version = ResourceVersion.forValue(versionString);

          String publicationStatusString = publicationStatusP.stringValue();
          BiboStatus publicationStatus = BiboStatus.forValue(publicationStatusString);

          String isBasedOnString = isBasedOnP.stringValue();

          CedarParameter description = new CedarInPlaceParameter("description", descriptionPair.getValue());
          CedarParameter identifier = new CedarInPlaceParameter("identifier", identifierPair.getValue());

          // Later we will guarantee some kind of uniqueness for the artifact names
          // Currently we allow duplicate names, the id is the PK
          FolderServerArtifact brandNewResource = GraphDbObjectBuilder.forResourceType(resourceType, id,
              name.stringValue(), description.stringValue(), identifier.stringValue(), version, publicationStatus);
          if (brandNewResource instanceof FolderServerSchemaArtifact) {
            FolderServerSchemaArtifact schemaArtifact = (FolderServerSchemaArtifact) brandNewResource;
            schemaArtifact.setLatestVersion(true);
            schemaArtifact.setLatestDraftVersion(publicationStatus == BiboStatus.DRAFT);
            schemaArtifact.setLatestPublishedVersion(publicationStatus == BiboStatus.PUBLISHED);
          }
          if (brandNewResource instanceof FolderServerInstanceArtifact) {
            FolderServerInstanceArtifact brandNewInstance = (FolderServerInstanceArtifact) brandNewResource;
            brandNewInstance.setIsBasedOn(isBasedOnString);
          }
          FolderServerArtifact newResource = folderSession.createResourceAsChildOfId(brandNewResource, folder.getId());

          if (newResource == null) {
            return CedarResponse.badRequest()
                .parameter("id", id)
                .parameter("parentId", folder.getId())
                .parameter("resourceType", resourceType.getValue())
                .errorKey(CedarErrorKey.RESOURCE_NOT_CREATED)
                .errorMessage("The artifact was not created!")
                .build();
          }
          UriBuilder builder = uriInfo.getAbsolutePathBuilder();
          URI uri = builder.path(CedarUrlUtil.urlEncode(id)).build();

          createIndexArtifact(newResource, context);
          createValuerecommenderResource(newResource);
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

  protected Response executeResourceGetByProxyFromArtifactServer(CedarResourceType resourceType, String id,
                                                                 CedarRequestContext context)
      throws CedarProcessingException {
    return executeResourceGetByProxyFromArtifactServer(resourceType, id, Optional.empty(), context);
  }

  protected Response executeResourceGetByProxyFromArtifactServer(CedarResourceType resourceType, String id,
                                                                 Optional<String> format, CedarRequestContext context)
      throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getArtifact().getResourceTypeWithId(resourceType, id, format);
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

  protected String getResourceFromArtifactServer(CedarResourceType resourceType, String id, CedarRequestContext context)
      throws
      CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getArtifact().getResourceTypeWithId(resourceType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      return EntityUtils.toString(entity);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response putResourceToArtifactServer(CedarResourceType resourceType, String id, CedarRequestContext context,
                                                 String
                                                     content) throws
      CedarProcessingException {
    String url = microserviceUrlUtil.getArtifact().getResourceTypeWithId(resourceType, id);
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

    FolderServerArtifactCurrentUserReport resourceReport = userMustHaveReadAccessToArtifact(context, id);

    FolderServerArtifact resource = FolderServerArtifact.fromFolderServerResourceCurrentUserReport(resourceReport);

    addProvenanceDisplayName(resource);
    return CedarResponse.ok().entity(resource).build();
  }

  protected Response executeResourceCreateOrUpdateViaPut(CedarRequestContext context, CedarResourceType resourceType,
                                                         String id)
      throws CedarException {
    return executeResourceCreateOrUpdateViaPut(context, resourceType, id,
        context.request().getRequestBody().asJsonString());
  }

  protected Response executeResourceCreateOrUpdateViaPut(CedarRequestContext context, CedarResourceType resourceType,
                                                         String id,
                                                         String content)
      throws CedarException {

    FolderServerArtifactCurrentUserReport folderServerResourceReport = userMustHaveWriteAccessToArtifact(context, id);
    FolderServerArtifact folderServerOldResource =
        FolderServerArtifact.fromFolderServerResourceCurrentUserReport(folderServerResourceReport);

    try {
      String url = microserviceUrlUtil.getArtifact().getResourceTypeWithId(resourceType, id);

      HttpResponse templateProxyResponse = ProxyUtil.proxyPut(url, context, content);
      ProxyUtil.proxyResponseHeaders(templateProxyResponse, response);
      int statusCode = templateProxyResponse.getStatusLine().getStatusCode();
      CreateOrUpdate createOrUpdate = null;
      if (statusCode == HttpStatus.SC_OK) {
        createOrUpdate = CreateOrUpdate.UPDATE;
      } else if (statusCode == HttpStatus.SC_CREATED) {
        createOrUpdate = CreateOrUpdate.CREATE;
      }
      if (createOrUpdate == null) {
        // artifact was not created or updated
        return generateStatusResponse(templateProxyResponse);
      } else {

        if (createOrUpdate == CreateOrUpdate.UPDATE) {
          if (folderServerOldResource != null) {
            if (folderServerOldResource instanceof FolderServerSchemaArtifact) {
              FolderServerSchemaArtifact artifact = (FolderServerSchemaArtifact) folderServerOldResource;
              if (artifact.getPublicationStatus() == BiboStatus.PUBLISHED) {
                return CedarResponse.badRequest()
                    .errorKey(CedarErrorKey.PUBLISHED_RESOURCES_CAN_NOT_BE_CHANGED)
                    .errorMessage("The artifact can not be changed since it is published!")
                    .parameter("name", folderServerOldResource.getName())
                    .build();
              }
            }
          }
        }
        // artifact was updated
        HttpEntity templateEntity = templateProxyResponse.getEntity();
        if (templateEntity != null) {
          String templateEntityContent = EntityUtils.toString(templateEntity);
          JsonNode templateJsonNode = JsonMapper.MAPPER.readTree(templateEntityContent);

          String newName = ModelUtil.extractNameFromResource(resourceType, templateJsonNode).getValue();
          String newDescription = ModelUtil.extractDescriptionFromResource(resourceType, templateJsonNode).getValue();
          String newIdentifier = ModelUtil.extractIdentifierFromResource(resourceType, templateJsonNode).getValue();

          FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
          FolderServerArtifact resource = folderSession.findArtifactById(id);

          if (resource == null) {
            return CedarResponse.notFound()
                .id(id)
                .errorKey(CedarErrorKey.ARTIFACT_NOT_FOUND)
                .errorMessage("The artifact can not be found by id")
                .build();
          } else {

            if (statusCode == HttpStatus.SC_OK) {
              Map<NodeProperty, String> updateFields = new HashMap<>();
              updateFields.put(NodeProperty.DESCRIPTION, newDescription);
              updateFields.put(NodeProperty.NAME, newName);
              updateFields.put(NodeProperty.IDENTIFIER, newIdentifier);
              FolderServerArtifact updatedResource =
                  folderSession.updateResourceById(id, resource.getType(), updateFields);
              if (updatedResource == null) {
                return CedarResponse.internalServerError().build();
              } else {
                updateIndexResource(updatedResource, context);
                updateValuerecommenderResource(updatedResource);
                return Response.ok().entity(updatedResource).build();
              }
            } else if (statusCode == HttpStatus.SC_CREATED) {
              //TODO : Handle creation via PUT
              /*FolderServerResource updatedResource =
                  folderSession.updateResourceById(id, artifact.getType(), updateFields);
              if (updatedResource == null) {
                return CedarResponse.internalServerError().build();
              } else {
                updateIndexResource(updatedResource, context);
                updateValuerecommenderResource(updatedResource);
                return Response.ok().entity(updatedResource).build();
              }*/
              return CedarResponse.internalServerError().build();
            }
            return CedarResponse.internalServerError().build();
          }
        } else {
          return Response.ok().build();
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response executeResourceDelete(CedarRequestContext c, CedarResourceType resourceType, String id)
      throws CedarException {

    // Check delete preconditions

    FolderServerArtifactCurrentUserReport resourceReport = userMustHaveWriteAccessToArtifact(c, id);

    if (resourceReport instanceof FolderServerSchemaArtifactCurrentUserReport) {
      FolderServerSchemaArtifactCurrentUserReport schemaArtifact =
          (FolderServerSchemaArtifactCurrentUserReport) resourceReport;
      if (schemaArtifact.getPublicationStatus() == BiboStatus.PUBLISHED) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.PUBLISHED_RESOURCES_CAN_NOT_BE_DELETED)
            .errorMessage("Published resources can not be deleted!")
            .parameter("id", id)
            .parameter("name", resourceReport.getName())
            .parameter(BIBO_STATUS, schemaArtifact.getPublicationStatus())
            .build();
      }
    }

    // Delete from artifact server

    try {
      String url = microserviceUrlUtil.getArtifact().getResourceTypeWithId(resourceType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_NO_CONTENT && statusCode != HttpStatus.SC_NOT_FOUND) {
        // artifact was not deleted
        return generateStatusResponse(proxyResponse);
      } else {
        if (statusCode == HttpStatus.SC_NOT_FOUND) {
          log.warn("Resource not found on artifact server, but still trying to delete from Neo4j. Id:" + id);
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

    ResourceUri previousVersion = null;
    if (resourceReport instanceof FolderServerSchemaArtifactCurrentUserReport) {
      FolderServerSchemaArtifactCurrentUserReport schemaArtifact =
          (FolderServerSchemaArtifactCurrentUserReport) resourceReport;
      if (schemaArtifact.isLatestVersion() != null && schemaArtifact.isLatestVersion()) {
        previousVersion = schemaArtifact.getPreviousVersion();
      }
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
          .errorMessage("The artifact can not be delete by id")
          .build();
    }

    removeIndexDocument(id);
    removeValuerecommenderResource(FolderServerArtifact.fromFolderServerResourceCurrentUserReport(resourceReport));
    // reindex the previous version, since that just became the latest
    if (resourceReport instanceof FolderServerSchemaArtifactCurrentUserReport) {
      FolderServerSchemaArtifactCurrentUserReport schemaArtifact =
          (FolderServerSchemaArtifactCurrentUserReport) resourceReport;
      String previousId = schemaArtifact.getPreviousVersion().getValue();
      if (previousId != null) {
        FolderServerArtifactCurrentUserReport folderServerPreviousResource =
            userMustHaveReadAccessToArtifact(c, previousId);
        String getResponse = getResourceFromArtifactServer(resourceType, previousId, c);
        if (getResponse != null) {
          JsonNode getJsonNode = null;
          try {
            getJsonNode = JsonMapper.MAPPER.readTree(getResponse);
            if (getJsonNode != null) {
              FolderServerArtifact folderServerPreviousR =
                  FolderServerArtifact.fromFolderServerResourceCurrentUserReport(folderServerPreviousResource);
              updateIndexResource(folderServerPreviousR, c);
            }
          } catch (Exception e) {
            log.error("There was an error while reindexing the new latest version", e);
          }
        }
      }
    }

    return Response.noContent().build();
  }

  protected FolderServerFolderCurrentUserReport userMustHaveReadAccessToFolder(CedarRequestContext context,
                                                                               String folderId) throws CedarException {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(context);
    FolderServerFolderCurrentUserReport fsFolder =
        GraphDbPermissionReader.getFolderCurrentUserReport(context, folderSession, permissionSession, folderId);
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
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(context);
    FolderServerFolderCurrentUserReport fsFolder =
        GraphDbPermissionReader.getFolderCurrentUserReport(context, folderSession, permissionSession, folderId);
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

  protected FolderServerArtifactCurrentUserReport userMustHaveReadAccessToArtifact(CedarRequestContext context,
                                                                                   String artifactId)
      throws CedarException {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(context);
    FolderServerArtifactCurrentUserReport
        fsArtifact = GraphDbPermissionReader
        .getArtifactCurrentUserReport(context, folderSession, permissionSession, cedarConfig, artifactId);
    if (fsArtifact == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.ARTIFACT_NOT_FOUND)
          .parameter("resourceId", artifactId);
    }
    if (context.getCedarUser().has(CedarPermission.READ_NOT_READABLE_NODE) ||
        fsArtifact.getCurrentUserPermissions().isCanRead()) {
      return fsArtifact;
    } else {
      throw new CedarPermissionException("You do not have read access to the artifact")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_RESOURCE)
          .parameter("resourceId", artifactId);
    }
  }

  protected FolderServerArtifactCurrentUserReport userMustHaveWriteAccessToArtifact(CedarRequestContext context,
                                                                                    String artifactId)
      throws CedarException {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(context);
    FolderServerArtifactCurrentUserReport
        fsResource = GraphDbPermissionReader
        .getArtifactCurrentUserReport(context, folderSession, permissionSession, cedarConfig, artifactId);
    if (fsResource == null) {
      throw new CedarObjectNotFoundException("Resource not found by id")
          .errorKey(CedarErrorKey.ARTIFACT_NOT_FOUND)
          .parameter("resourceId", artifactId);
    }
    if (context.getCedarUser().has(CedarPermission.WRITE_NOT_WRITABLE_NODE) ||
        fsResource.getCurrentUserPermissions().isCanWrite()) {
      return fsResource;
    } else {
      throw new CedarPermissionException("You do not have write access to the artifact")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_RESOURCE)
          .parameter("resourceId", artifactId);
    }
  }

  protected FileSystemResource userMustHaveReadAccessToResource(CedarRequestContext context, String resourceId) throws
      CedarException {
    Exception genericException;
    try {
      return userMustHaveReadAccessToArtifact(context, resourceId);
    } catch (CedarPermissionException e) {
      throw e;
    } catch (Exception e) {
      genericException = e;
    }
    try {
      return userMustHaveReadAccessToFolder(context, resourceId);
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

  protected FileSystemResource userMustHaveWriteAccessToResource(CedarRequestContext context, String resourceId) throws
      CedarException {
    Exception genericException;
    try {
      return userMustHaveWriteAccessToArtifact(context, resourceId);
    } catch (CedarPermissionException e) {
      throw e;
    } catch (Exception e) {
      genericException = e;
    }
    try {
      return userMustHaveWriteAccessToFolder(context, resourceId);
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

  protected Response generateResourcePermissionsResponse(CedarRequestContext c, String id) throws
      CedarException {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FileSystemResource node = folderSession.findResourceById(id);
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

  protected Response updateResourcePermissions(CedarRequestContext c, String id) throws CedarException {

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

    FileSystemResource node = folderSession.findResourceById(id);
    if (node == null) {
      return CedarResponse.notFound()
          .id(id)
          .errorKey(CedarErrorKey.NODE_NOT_FOUND)
          .errorMessage("The resource can not be found by id")
          .build();
    } else {
      BackendCallResult backendCallResult = permissionSession.updateNodePermissions(id, permissionsRequest);
      if (backendCallResult.isError()) {
        throw new CedarBackendException(backendCallResult);
      }

      if (node.getType() == CedarResourceType.FOLDER) {
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

  private ValuerecommenderReindexMessage buildValuerecommenderEvent(FolderServerArtifact folderServerResource,
                                                                    ValuerecommenderReindexMessageActionType actionType) {
    ValuerecommenderReindexMessage event = null;
    if (folderServerResource.getType() == CedarResourceType.TEMPLATE) {
      event = new ValuerecommenderReindexMessage(folderServerResource.getId(), null,
          ValuerecommenderReindexMessageResourceType.TEMPLATE, actionType);
    } else if (folderServerResource.getType() == CedarResourceType.INSTANCE) {
      FolderServerInstance instance = (FolderServerInstance) folderServerResource;
      event = new ValuerecommenderReindexMessage(instance.getIsBasedOn().getValue(), instance.getId(),
          ValuerecommenderReindexMessageResourceType.INSTANCE, actionType);
    }
    return event;
  }

  protected void createIndexArtifact(FolderServerArtifact folderServerArtifact, CedarRequestContext c)
      throws CedarProcessingException {
    nodeIndexingService.indexDocument(folderServerArtifact, c);
  }

  protected void createIndexFolder(FolderServerFolder folderServerFolder, CedarRequestContext c) throws
      CedarProcessingException {
    nodeIndexingService.indexDocument(folderServerFolder, c);
  }

  protected void createValuerecommenderResource(FolderServerArtifact folderServerArtifact) {
    ValuerecommenderReindexMessage event =
        buildValuerecommenderEvent(folderServerArtifact, ValuerecommenderReindexMessageActionType.CREATED);
    if (event != null) {
      valuerecommenderReindexQueueService.enqueueEvent(event);
    }
  }

  protected void updateIndexResource(FolderServerArtifact folderServerArtifact, CedarRequestContext c)
      throws CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(folderServerArtifact.getId());
    nodeIndexingService.indexDocument(folderServerArtifact, c);
  }

  protected void updateIndexFolder(FolderServerFolder folderServerFolder, CedarRequestContext c) throws
      CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(folderServerFolder.getId());
    nodeIndexingService.indexDocument(folderServerFolder, c);
  }

  protected void updateValuerecommenderResource(FolderServerArtifact folderServerArtifact) {
    ValuerecommenderReindexMessage event =
        buildValuerecommenderEvent(folderServerArtifact, ValuerecommenderReindexMessageActionType.UPDATED);
    if (event != null) {
      valuerecommenderReindexQueueService.enqueueEvent(event);
    }
  }

  protected void removeIndexDocument(String id) throws CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(id);
  }

  protected void removeValuerecommenderResource(FolderServerArtifact folderServerArtifact) {
    ValuerecommenderReindexMessage event =
        buildValuerecommenderEvent(folderServerArtifact, ValuerecommenderReindexMessageActionType.DELETED);
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

    FolderServerArtifact artifact = folderSession.findArtifactById(id);
    if (artifact == null) {
      return CedarResponse.notFound()
          .errorKey(CedarErrorKey.ARTIFACT_NOT_FOUND)
          .errorMessage("Artifact not found")
          .parameter("id", id)
          .build();
    }

    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(c);

    userMustHaveReadAccess(permissionSession, id);

    if (!artifact.getType().isVersioned()) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .errorMessage("Invalid artifact type")
          .parameter("artifactType", artifact.getType().getValue())
          .build();
    }

    FolderServerNodeListResponse r = new FolderServerNodeListResponse();
    NodeListRequest req = new NodeListRequest();
    req.setId(id);
    r.setRequest(req);

    NodeListQueryType nlqt = NodeListQueryType.ALL_VERSIONS;
    r.setNodeListQueryType(nlqt);

    List<FolderServerArtifactExtract> resources = folderSession.getVersionHistory(id);
    r.setResources(resources);

    r.setCurrentOffset(0);
    r.setTotalCount(resources.size());

    for (FolderServerResourceExtract node : r.getResources()) {
      addProvenanceDisplayName(node);
    }

    return Response.ok().entity(r).build();
  }

  protected void userMustHaveReadAccess(PermissionServiceSession permissionServiceSession, String id)
      throws CedarException {
    boolean b = permissionServiceSession.userHasReadAccessToNode(id);
    if (!b) {
      throw new CedarPermissionException("You do not have read access to the resource")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_NODE)
          .parameter("nodeId", id);
    }
  }

  protected void decorateResourceWithNumberOfInstances(CedarRequestContext c, FolderServiceSession folderSession,
                                                       FolderServerTemplateReport templateReport) {
    templateReport.setNumberOfInstances(folderSession.getNumberOfInstances(templateReport.getId()));
  }

  protected void decorateResourceWithVersionHistory(CedarRequestContext c, FolderServiceSession folderSession,
                                                    FolderServerArtifactReport resourceReport) {
    List<FolderServerArtifactExtract> allVersions = folderSession.getVersionHistory(resourceReport.getId());
    List<FolderServerArtifactExtract> allVersionsWithPermission =
        folderSession.getVersionHistoryWithPermission(resourceReport.getId());
    Map<String, FolderServerArtifactExtract> accessibleMap = new HashMap<>();
    for (FolderServerArtifactExtract e : allVersionsWithPermission) {
      accessibleMap.put(e.getId(), e);
    }

    List<FolderServerArtifactExtract> visibleVersions = new ArrayList<>();
    for (FolderServerArtifactExtract v : allVersions) {
      if (accessibleMap.containsKey(v.getId())) {
        visibleVersions.add(v);
      } else {
        visibleVersions.add(FolderServerResourceExtract.anonymous(v));
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
          instanceReport.setIsBasedOnExtract(FolderServerResourceExtract.anonymous(resourceExtract));
        }
      }
    }
  }

  protected void decorateResourceWithDerivedFrom(FolderServiceSession folderSession,
                                                 PermissionServiceSession permissionServiceSession,
                                                 FolderServerArtifactReport resourceReport) {
    if (resourceReport.getDerivedFrom() != null && resourceReport.getDerivedFrom().getValue() != null) {
      FolderServerArtifactExtract resourceExtract =
          folderSession.findResourceExtractById(resourceReport.getDerivedFrom());
      if (resourceExtract != null) {
        boolean hasReadAccess = permissionServiceSession.userHasReadAccessToNode(resourceExtract.getId());
        if (hasReadAccess) {
          resourceReport.setDerivedFromExtract(resourceExtract);
        } else {
          resourceReport.setDerivedFromExtract(FolderServerResourceExtract.anonymous(resourceExtract));
        }
      }
    }
  }

  protected Response generateArtifactReportResponse(CedarRequestContext c, String id) throws CedarException {

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerArtifact artifact = folderSession.findArtifactById(id);
    if (artifact == null) {
      return CedarResponse.notFound()
          .errorKey(CedarErrorKey.ARTIFACT_NOT_FOUND)
          .errorMessage("Resource not found")
          .parameter("id", id)
          .build();
    }

    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(c);

    userMustHaveReadAccess(permissionSession, artifact.getId());

    folderSession.addPathAndParentId(artifact);

    artifact.setPathInfo(PathInfoBuilder.getResourcePathExtract(c, folderSession, permissionSession, artifact));

    FolderServerArtifactReport resourceReport = FolderServerArtifactReport.fromResource(artifact);

    decorateResourceWithDerivedFrom(folderSession, permissionSession, resourceReport);
    GraphDbPermissionReader
        .decorateResourceWithCurrentUserPermissions(c, permissionSession, cedarConfig, resourceReport);

    if (artifact.getType() == CedarResourceType.INSTANCE) {
      decorateResourceWithIsBasedOn(folderSession, permissionSession,
          (FolderServerInstanceReport) resourceReport);
    } else if (artifact.getType() == CedarResourceType.FIELD) {
      decorateResourceWithVersionHistory(c, folderSession, resourceReport);
    } else if (artifact.getType() == CedarResourceType.ELEMENT) {
      decorateResourceWithVersionHistory(c, folderSession, resourceReport);
    } else if (artifact.getType() == CedarResourceType.TEMPLATE) {
      decorateResourceWithNumberOfInstances(c, folderSession, (FolderServerTemplateReport) resourceReport);
      decorateResourceWithVersionHistory(c, folderSession, resourceReport);
    }

    addProvenanceDisplayName(resourceReport);
    addProvenanceDisplayNames(resourceReport);
    return Response.ok(resourceReport).build();
  }

  protected FolderServerCategory userMustHaveWriteAccessToCategory(CedarRequestContext context,
                                                                   CedarCategoryId categoryId) throws CedarException {
    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(context);
    PermissionServiceSession permissionSession = CedarDataServices.getPermissionServiceSession(context);

    FolderServerCategoryCurrentUserReport fsCategory =
        GraphDbPermissionReader.getCategoryCurrentUserReport(context, categorySession, permissionSession, categoryId);
    if (fsCategory == null) {
      throw new CedarObjectNotFoundException("Category not found by id")
          .errorKey(CedarErrorKey.CATEGORY_NOT_FOUND)
          .parameter("categoryId", categoryId);
    }
    if (context.getCedarUser().has(CedarPermission.WRITE_NOT_WRITABLE_CATEGORY) ||
        fsCategory.getCurrentUserPermissions().isCanWrite()) {
      return fsCategory;
    } else {
      throw new CedarPermissionException("You do not have write access to the category")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_CATEGORY)
          .parameter("categoryId", categoryId);
    }
  }

}
