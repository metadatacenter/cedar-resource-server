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
import org.metadatacenter.constant.CedarHeaderParameters;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.*;
import org.metadatacenter.id.*;
import org.metadatacenter.model.*;
import org.metadatacenter.model.folderserver.basic.*;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerArtifactCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerCategoryCurrentUserReport;
import org.metadatacenter.model.folderserver.currentuserpermissions.FolderServerFolderCurrentUserReport;
import org.metadatacenter.model.folderserver.datagroup.ResourceWithUsersAndUserNamesData;
import org.metadatacenter.model.folderserver.extract.FolderServerArtifactExtract;
import org.metadatacenter.model.folderserver.extract.FolderServerCategoryExtract;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.model.folderserver.extract.FolderServerTemplateExtract;
import org.metadatacenter.model.folderserver.report.FolderServerArtifactReport;
import org.metadatacenter.model.folderserver.report.FolderServerInstanceReport;
import org.metadatacenter.model.folderserver.report.FolderServerSchemaArtifactReport;
import org.metadatacenter.model.folderserver.report.FolderServerTemplateReport;
import org.metadatacenter.model.request.NodeListQueryType;
import org.metadatacenter.model.request.NodeListRequest;
import org.metadatacenter.model.response.FolderServerCategoryListResponse;
import org.metadatacenter.model.response.FolderServerNodeListResponse;
import org.metadatacenter.rest.assertion.noun.CedarInPlaceParameter;
import org.metadatacenter.rest.assertion.noun.CedarParameter;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.CategoryPermissionServiceSession;
import org.metadatacenter.server.CategoryServiceSession;
import org.metadatacenter.server.FolderServiceSession;
import org.metadatacenter.server.ResourcePermissionServiceSession;
import org.metadatacenter.server.cache.user.UserSummaryCache;
import org.metadatacenter.server.neo4j.cypher.NodeProperty;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;
import org.metadatacenter.server.security.model.auth.CedarNodePermissionsWithExtract;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.permission.resource.ResourcePermissionsRequest;
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

  public static void injectServices(NodeIndexingService nodeIndexingService, NodeSearchingService nodeSearchingService,
                                    SearchPermissionEnqueueService searchPermissionEnqueueService,
                                    ValuerecommenderReindexQueueService valuerecommenderReindexQueueService) {
    AbstractResourceServerResource.nodeIndexingService = nodeIndexingService;
    AbstractResourceServerResource.nodeSearchingService = nodeSearchingService;
    AbstractResourceServerResource.searchPermissionEnqueueService = searchPermissionEnqueueService;
    AbstractResourceServerResource.valuerecommenderReindexQueueService = valuerecommenderReindexQueueService;
  }

  protected static <T extends FileSystemResource> T deserializeResource(HttpResponse proxyResponse, Class<T> klazz) throws CedarProcessingException {
    T resource = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      resource = JsonMapper.MAPPER.readValue(responseString, klazz);
    } catch (IOException e) {
      throw new CedarProcessingException(e);
    }
    return resource;
  }

  protected static void updateNameInObject(JsonNode jsonNode, String name) {
    ((ObjectNode) jsonNode).put(ModelNodeNames.SCHEMA_ORG_NAME, name);
  }

  protected static void updateDescriptionInObject(JsonNode jsonNode, String description) {
    ((ObjectNode) jsonNode).put(ModelNodeNames.SCHEMA_ORG_DESCRIPTION, description);
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

  protected Response executeResourcePostToArtifactServer(CedarRequestContext context, CedarResourceType resourceType, String content) throws CedarProcessingException {
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
  protected Response executeResourceCreationOnArtifactServerAndGraphDb(CedarRequestContext context, CedarResourceType resourceType,
                                                                       Optional<String> folderId) throws CedarException {

    String folderIdS;

    CedarParameter folderIdP = context.request().wrapQueryParam(QP_FOLDER_ID, folderId);
    if (folderIdP.isEmpty()) {
      folderIdS = context.getCedarUser().getHomeFolderId();
    } else {
      folderIdS = folderIdP.stringValue();
    }

    CedarFolderId fid = CedarFolderId.build(folderIdS);

    userMustHaveWriteAccessToFolder(context, fid);

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
          CedarArtifactId aid = CedarArtifactId.build(id, resourceType);

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
          CedarTemplateId ibo = CedarTemplateId.build(isBasedOnString);

          CedarParameter description = new CedarInPlaceParameter("description", descriptionPair.getValue());
          CedarParameter identifier = new CedarInPlaceParameter("identifier", identifierPair.getValue());

          // Later we will guarantee some kind of uniqueness for the artifact names
          // Currently we allow duplicate names, the id is the PK
          FolderServerArtifact brandNewResource = GraphDbObjectBuilder.forResourceType(resourceType, aid, name.stringValue(),
              description.stringValue(), identifier.stringValue(), version, publicationStatus);
          if (brandNewResource instanceof FolderServerSchemaArtifact) {
            FolderServerSchemaArtifact schemaArtifact = (FolderServerSchemaArtifact) brandNewResource;
            schemaArtifact.setLatestVersion(true);
            schemaArtifact.setLatestDraftVersion(publicationStatus == BiboStatus.DRAFT);
            schemaArtifact.setLatestPublishedVersion(publicationStatus == BiboStatus.PUBLISHED);
          }
          if (brandNewResource instanceof FolderServerInstanceArtifact) {
            FolderServerInstanceArtifact brandNewInstance = (FolderServerInstanceArtifact) brandNewResource;
            brandNewInstance.setIsBasedOn(ibo);
          }
          String sourceHash = context.getSourceHashHeader();
          if (sourceHash != null) {
            brandNewResource.setSourceHash(sourceHash);
          }
          FolderServerArtifact newResource = folderSession.createResourceAsChildOfId(brandNewResource, fid);

          if (newResource == null) {
            return CedarResponse.badRequest()
                .parameter("id", id)
                .parameter("parentId", fid.getId())
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

  protected Response executeResourceGetByProxyFromArtifactServer(CedarResourceType resourceType, String id, CedarRequestContext context) throws CedarProcessingException {
    return executeResourceGetByProxyFromArtifactServer(resourceType, id, Optional.empty(), context);
  }

  protected Response executeResourceGetByProxyFromArtifactServer(CedarResourceType resourceType, String id, Optional<String> format,
                                                                 CedarRequestContext context) throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id, format);
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

  protected String getSchemaArtifactFromArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id, CedarRequestContext context) throws CedarProcessingException {
    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, context);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      HttpEntity entity = proxyResponse.getEntity();
      return EntityUtils.toString(entity);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  protected Response putSchemaArtifactToArtifactServer(CedarResourceType resourceType, CedarSchemaArtifactId id, CedarRequestContext context,
                                                       String content) throws CedarProcessingException {
    String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id);
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

  protected Response getDetails(CedarRequestContext context, CedarArtifactId id) throws CedarException {

    userMustHaveReadAccessToArtifact(context, id);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    FolderServerArtifact resource = folderSession.findArtifactById(id);

    addProvenanceDisplayName(resource);
    return CedarResponse.ok().entity(resource).build();
  }

  protected Response executeResourceCreateOrUpdateViaPut(CedarRequestContext context, CedarResourceType resourceType, CedarArtifactId id) throws CedarException {
    return executeResourceCreateOrUpdateViaPut(context, resourceType, id, context.request().getRequestBody().asJsonString());
  }

  protected Response executeResourceCreateOrUpdateViaPut(CedarRequestContext context, CedarResourceType resourceType, CedarArtifactId id,
                                                         String content) throws CedarException {

    userMustHaveWriteAccessToArtifact(context, id);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    FolderServerArtifact folderServerOldResource = folderSession.findArtifactById(id);

    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id);

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
                    .errorKey(CedarErrorKey.PUBLISHED_ARTIFACT_CAN_NOT_BE_CHANGED)
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
              String sourceHash = context.getSourceHashHeader();
              if (sourceHash != null) {
                updateFields.put(NodeProperty.SOURCE_HASH, sourceHash);
              }
              FolderServerArtifact updatedResource = folderSession.updateArtifactById(id, resource.getType(), updateFields);
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

  protected Response executeArtifactDelete(CedarRequestContext c, CedarResourceType resourceType, CedarArtifactId id) throws CedarException {

    // Check delete preconditions
    userMustHaveWriteAccessToArtifact(c, id);
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    FolderServerArtifact artifact = folderSession.findArtifactById(id);

    FolderServerSchemaArtifact schemaArtifact = null;
    boolean isSchemaArtifact = false;
    if (artifact instanceof FolderServerSchemaArtifact) {
      schemaArtifact = (FolderServerSchemaArtifact) artifact;
      isSchemaArtifact = true;
    }

    // Check whether it is published
    if (isSchemaArtifact) {
      if (schemaArtifact.getPublicationStatus() == BiboStatus.PUBLISHED) {
        return CedarResponse.badRequest()
            .errorKey(CedarErrorKey.PUBLISHED_ARTIFACT_CAN_NOT_BE_DELETED)
            .errorMessage("Published artifacts can not be deleted!")
            .parameter("id", id)
            .parameter("name", schemaArtifact.getName())
            .parameter(BIBO_STATUS, schemaArtifact.getPublicationStatus())
            .build();
      }
    }

    // Delete from artifact server
    try {
      String url = microserviceUrlUtil.getArtifact().getArtifactTypeWithId(resourceType, id);
      HttpResponse proxyResponse = ProxyUtil.proxyDelete(url, c);
      ProxyUtil.proxyResponseHeaders(proxyResponse, response);
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (statusCode != HttpStatus.SC_NO_CONTENT && statusCode != HttpStatus.SC_NOT_FOUND) {
        // artifact was not deleted
        return generateStatusResponse(proxyResponse);
      } else {
        if (statusCode == HttpStatus.SC_NOT_FOUND) {
          log.warn("Artifact not found on artifact server, but still trying to delete from Neo4j. Id:" + id);
        }
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

    // Check whether it is latest version
    CedarSchemaArtifactId previousVersion = null;
    if (isSchemaArtifact) {
      if (schemaArtifact.isLatestVersion() != null && schemaArtifact.isLatestVersion()) {
        previousVersion = schemaArtifact.getPreviousVersion();
      }
    }

    boolean deleted = folderSession.deleteResourceById(id);
    if (deleted) {
      if (previousVersion != null) {
        folderSession.setLatestVersion(previousVersion);
        folderSession.setLatestPublishedVersion(previousVersion);
      }
    } else {
      return CedarResponse.internalServerError()
          .id(id)
          .errorKey(CedarErrorKey.ARTIFACT_NOT_DELETED)
          .errorMessage("The artifact can not be delete by id")
          .parameter("id", id)
          .build();
    }

    removeIndexDocument(id);
    removeValuerecommenderResource(artifact);
    // reindex the previous version, since that just became the latest
    if (isSchemaArtifact) {
      CedarSchemaArtifactId previousId = schemaArtifact.getPreviousVersion();
      // Doublechekc if it is present on the artifact server as well
      if (previousId != null) {
        String getResponse = getSchemaArtifactFromArtifactServer(resourceType, previousId, c);
        if (getResponse != null) {
          JsonNode getJsonNode = null;
          try {
            getJsonNode = JsonMapper.MAPPER.readTree(getResponse);
            if (getJsonNode != null) {
              FolderServerArtifact folderServerPreviousR = folderSession.findArtifactById(previousId);
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

  protected void userMustHaveReadAccessToFolder(CedarRequestContext context, CedarFolderId folderId) throws CedarException {
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(context);
    boolean hasReadAccess = permissionSession.userHasReadAccessToResource(folderId);
    if (!hasReadAccess) {
      throw new CedarPermissionException("You do not have read access to the folder")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_FOLDER)
          .parameter("folderId", folderId);
    }
  }

  protected void userMustHaveWriteAccessToFolder(CedarRequestContext context, CedarFolderId folderId) throws CedarException {
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(context);
    boolean hasWriteAccess = permissionSession.userHasWriteAccessToResource(folderId);
    if (!hasWriteAccess) {
      throw new CedarPermissionException("You do not have write access to the folder")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_FOLDER)
          .parameter("folderId", folderId);
    }
  }

  protected void userMustHaveReadAccessToArtifact(CedarRequestContext context, CedarArtifactId artifactId) throws CedarException {
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(context);
    boolean hasReadAccess = permissionSession.userHasReadAccessToResource(artifactId);
    if (!hasReadAccess) {
      throw new CedarPermissionException("You do not have read access to the artifact")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_ARTIFACT)
          .parameter("resourceId", artifactId);
    }
  }

  protected void userMustHaveWriteAccessToArtifact(CedarRequestContext context, CedarArtifactId artifactId) throws CedarException {
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(context);
    boolean hasWriteAccess = permissionSession.userHasWriteAccessToResource(artifactId);
    if (!hasWriteAccess) {
      throw new CedarPermissionException("You do not have write access to the artifact")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_ARTIFACT)
          .parameter("resourceId", artifactId);
    }
  }

  protected FileSystemResource userMustHaveReadAccessToResource(CedarRequestContext context, CedarFilesystemResourceId resourceId) throws CedarException {
    Exception genericException = null;
    FileSystemResource resource = null;
    if (resourceId instanceof CedarFolderId) {
      try {
        userMustHaveReadAccessToFolder(context, resourceId.asFolderId());
      } catch (CedarPermissionException e) {
        throw e;
      } catch (Exception e) {
        if (genericException == null) {
          genericException = e;
        }
      }
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
      resource = folderSession.findFolderById(resourceId.asFolderId());
    } else {
      try {
        userMustHaveReadAccessToArtifact(context, resourceId.asArtifactId());
      } catch (CedarPermissionException e) {
        throw e;
      } catch (Exception e) {
        genericException = e;
      }
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
      resource = folderSession.findArtifactById(resourceId.asArtifactId());
    }
    if (genericException != null) {
      throw new CedarProcessingException(genericException);
    }
    return resource;
  }

  protected FileSystemResource userMustHaveWriteAccessToFilesystemResource(CedarRequestContext context, CedarFilesystemResourceId resourceId) throws CedarException {
    Exception genericException = null;
    FileSystemResource resource = null;

    if (resourceId instanceof CedarFolderId) {
      try {
        userMustHaveWriteAccessToFolder(context, resourceId.asFolderId());
      } catch (CedarPermissionException e) {
        throw e;
      } catch (Exception e) {
        if (genericException == null) {
          genericException = e;
        }
        FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
        resource = folderSession.findFolderById(resourceId.asFolderId());
      }
    } else {
      try {
        userMustHaveWriteAccessToArtifact(context, resourceId.asArtifactId());
      } catch (CedarPermissionException e) {
        throw e;
      } catch (Exception e) {
        genericException = e;
      }
      FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
      resource = folderSession.findArtifactById(resourceId.asArtifactId());
    }
    if (genericException != null) {
      throw new CedarProcessingException(genericException);
    }
    return resource;
  }

  protected Response generateResourcePermissionsResponse(CedarRequestContext c, CedarFilesystemResourceId resourceId) throws CedarException {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FileSystemResource node = folderSession.findResourceById(resourceId);
    if (node == null) {
      return CedarResponse.notFound()
          .errorKey(CedarErrorKey.NODE_NOT_FOUND)
          .errorMessage("Node not found")
          .parameter("id", resourceId)
          .build();
    }

    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

    userMustHaveReadAccess(permissionSession, resourceId);

    CedarNodePermissionsWithExtract permissions = permissionSession.getResourcePermissions(resourceId);
    return Response.ok().entity(permissions).build();
  }

  protected Response updateResourcePermissions(CedarRequestContext c, CedarFilesystemResourceId resourceId) throws CedarException {

    c.must(c.request().getRequestBody()).be(NonEmpty);
    JsonNode permissionUpdateRequest = c.request().getRequestBody().asJson();

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

    ResourcePermissionsRequest permissionsRequest = null;
    try {
      permissionsRequest = JsonMapper.MAPPER.treeToValue(permissionUpdateRequest, ResourcePermissionsRequest.class);
    } catch (JsonProcessingException e) {
      log.error("Error while reading permission update request", e);
    }

    FileSystemResource node = folderSession.findResourceById(resourceId);
    if (node == null) {
      return CedarResponse.notFound()
          .id(resourceId)
          .errorKey(CedarErrorKey.NODE_NOT_FOUND)
          .errorMessage("The resource can not be found by id")
          .build();
    } else {
      BackendCallResult backendCallResult = permissionSession.updateResourcePermissions(resourceId, permissionsRequest);
      if (backendCallResult.isError()) {
        throw new CedarBackendException(backendCallResult);
      }

      if (node.getType() == CedarResourceType.FOLDER) {
        searchPermissionEnqueueService.folderPermissionsChanged(resourceId);
      } else {
        searchPermissionEnqueueService.resourcePermissionsChanged(resourceId);
      }

      CedarNodePermissionsWithExtract permissions = permissionSession.getResourcePermissions(resourceId);
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
      CedarTemplateId templateId = CedarTemplateId.build(folderServerResource.getId());
      event = new ValuerecommenderReindexMessage(templateId, null, ValuerecommenderReindexMessageResourceType.TEMPLATE, actionType);
    } else if (folderServerResource.getType() == CedarResourceType.INSTANCE) {
      FolderServerInstance instance = (FolderServerInstance) folderServerResource;
      CedarInstanceArtifactId instanceId = CedarTemplateInstanceId.build(instance.getId());
      event = new ValuerecommenderReindexMessage(instance.getIsBasedOn(), instanceId, ValuerecommenderReindexMessageResourceType.INSTANCE,
          actionType);
    }
    return event;
  }

  protected void createIndexArtifact(FolderServerArtifact folderServerArtifact, CedarRequestContext c) throws CedarProcessingException {
    nodeIndexingService.indexDocument(folderServerArtifact, c);
  }

  protected void createIndexFolder(FolderServerFolder folderServerFolder, CedarRequestContext c) throws CedarProcessingException {
    nodeIndexingService.indexDocument(folderServerFolder, c);
  }

  protected void createValuerecommenderResource(FolderServerArtifact folderServerArtifact) {
    ValuerecommenderReindexMessage event = buildValuerecommenderEvent(folderServerArtifact, ValuerecommenderReindexMessageActionType.CREATED);
    if (event != null) {
      valuerecommenderReindexQueueService.enqueueEvent(event);
    }
  }

  protected void updateIndexResource(FolderServerArtifact folderServerArtifact, CedarRequestContext c) throws CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(folderServerArtifact.getResourceId());
    nodeIndexingService.indexDocument(folderServerArtifact, c);
  }

  protected void updateIndexFolder(FolderServerFolder folderServerFolder, CedarRequestContext c) throws CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(folderServerFolder.getResourceId());
    nodeIndexingService.indexDocument(folderServerFolder, c);
  }

  protected void updateValuerecommenderResource(FolderServerArtifact folderServerArtifact) {
    ValuerecommenderReindexMessage event = buildValuerecommenderEvent(folderServerArtifact, ValuerecommenderReindexMessageActionType.UPDATED);
    if (event != null) {
      valuerecommenderReindexQueueService.enqueueEvent(event);
    }
  }

  protected void removeIndexDocument(CedarFilesystemResourceId resourceId) throws CedarProcessingException {
    nodeIndexingService.removeDocumentFromIndex(resourceId);
  }

  protected void removeValuerecommenderResource(FolderServerArtifact folderServerArtifact) {
    ValuerecommenderReindexMessage event = buildValuerecommenderEvent(folderServerArtifact, ValuerecommenderReindexMessageActionType.DELETED);
    if (event != null) {
      valuerecommenderReindexQueueService.enqueueEvent(event);
    }
  }

  protected Response updateFolderNameAndDescriptionInGraphDb(CedarRequestContext c, CedarFolderId folderId) throws CedarException {
    userMustHaveWriteAccessToFolder(c, folderId);

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);
    FolderServerFolder folderServerFolder = folderSession.findFolderById(folderId);

    String oldName = folderServerFolder.getName();

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

    FolderServerFolder folder = folderSession.findFolderById(folderId);
    if (folder == null) {
      return CedarResponse.notFound()
          .id(folderId)
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
      FolderServerFolder folderServerFolderUpdated = folderSession.updateFolderById(folderId, updateFields);

      String newName = folderServerFolderUpdated.getName();
      if (oldName == null || !oldName.equals(newName)) {
        removeIndexDocument(folderId);
        createIndexFolder(folderServerFolderUpdated, c);
      } else {
        updateIndexFolder(folderServerFolderUpdated, c);
      }


      addProvenanceDisplayName(folderServerFolderUpdated);
      return Response.ok().entity(folderServerFolderUpdated).build();
    }
  }

  protected Response generateNodeVersionsResponse(CedarRequestContext c, CedarSchemaArtifactId artifactId) throws CedarException {

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerArtifact artifact = folderSession.findArtifactById(artifactId);
    if (artifact == null) {
      return CedarResponse.notFound()
          .errorKey(CedarErrorKey.ARTIFACT_NOT_FOUND)
          .errorMessage("Artifact not found")
          .parameter("id", artifactId)
          .build();
    }

    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

    userMustHaveReadAccess(permissionSession, artifactId);

    if (!artifact.getType().isVersioned()) {
      return CedarResponse.badRequest()
          .errorKey(CedarErrorKey.INVALID_DATA)
          .errorMessage("Invalid artifact type")
          .parameter("artifactType", artifact.getType().getValue())
          .build();
    }

    FolderServerNodeListResponse r = new FolderServerNodeListResponse();
    NodeListRequest req = new NodeListRequest();
    req.setId(artifactId.getId());
    r.setRequest(req);

    NodeListQueryType nlqt = NodeListQueryType.ALL_VERSIONS;
    r.setNodeListQueryType(nlqt);

    List<FolderServerArtifactExtract> resources = folderSession.getVersionHistory(artifactId);
    r.setResources(resources);

    r.setCurrentOffset(0);
    r.setTotalCount(resources.size());

    for (FolderServerResourceExtract node : r.getResources()) {
      addProvenanceDisplayName(node);
    }

    return Response.ok().entity(r).build();
  }

  protected void userMustHaveReadAccess(ResourcePermissionServiceSession permissionServiceSession, CedarFilesystemResourceId resourceId) throws CedarException {
    boolean b = permissionServiceSession.userHasReadAccessToResource(resourceId);
    if (!b) {
      throw new CedarPermissionException("You do not have read access to the resource")
          .errorKey(CedarErrorKey.NO_READ_ACCESS_TO_RESOURCE)
          .parameter("resourceId", resourceId.getId());
    }
  }

  protected void decorateResourceWithNumberOfInstances(FolderServiceSession folderSession, FolderServerTemplateReport templateReport) {
    templateReport.setNumberOfInstances(folderSession.getNumberOfInstances((CedarTemplateId) templateReport.getResourceId()));
  }

  protected void decorateResourceWithVersionHistory(FolderServiceSession folderSession, FolderServerSchemaArtifactReport resourceReport) {
    List<FolderServerArtifactExtract> allVersions = folderSession.getVersionHistory(resourceReport.getResourceId());
    List<FolderServerArtifactExtract> allVersionsWithPermission = folderSession.getVersionHistoryWithPermission(resourceReport.getResourceId());
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

  protected void decorateResourceWithIsBasedOn(FolderServiceSession folderSession, ResourcePermissionServiceSession permissionServiceSession,
                                               FolderServerInstanceReport instanceReport) {
    if (instanceReport.getIsBasedOn() != null) {
      FolderServerTemplateExtract resourceExtract =
          (FolderServerTemplateExtract) folderSession.findResourceExtractById(instanceReport.getIsBasedOn());
      if (resourceExtract != null) {
        boolean hasReadAccess = permissionServiceSession.userHasReadAccessToResource(resourceExtract.getResourceId());
        if (hasReadAccess) {
          instanceReport.setIsBasedOnExtract(resourceExtract);
        } else {
          instanceReport.setIsBasedOnExtract(FolderServerResourceExtract.anonymous(resourceExtract));
        }
      }
    }
  }

  protected void decorateResourceWithDerivedFrom(FolderServiceSession folderSession, ResourcePermissionServiceSession permissionServiceSession,
                                                 FolderServerArtifactReport artifactReport) {
    if (artifactReport.getDerivedFrom() != null && artifactReport.getDerivedFrom().getId() != null) {
      FolderServerArtifactExtract resourceExtract = folderSession.findResourceExtractById(artifactReport.getDerivedFrom());
      if (resourceExtract != null) {
        boolean hasReadAccess = permissionServiceSession.userHasReadAccessToResource(resourceExtract.getResourceId());
        if (hasReadAccess) {
          artifactReport.setDerivedFromExtract(resourceExtract);
        } else {
          artifactReport.setDerivedFromExtract(FolderServerResourceExtract.anonymous(resourceExtract));
        }
      }
    }
  }

  protected void decorateResourceWithCategories(CategoryServiceSession serviceSession, FolderServerArtifactReport artifactReport) {
    List<List<FolderServerCategoryExtract>> categories = serviceSession.getAttachedCategoryPaths(artifactReport.getResourceId());
    artifactReport.setCategories(categories);
  }

  protected Response generateArtifactReportResponse(CedarRequestContext c, CedarArtifactId artifactId) throws CedarException {

    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(c);

    FolderServerArtifact artifact = folderSession.findArtifactById(artifactId);
    if (artifact == null) {
      return CedarResponse.notFound()
          .errorKey(CedarErrorKey.ARTIFACT_NOT_FOUND)
          .errorMessage("Resource not found")
          .parameter("id", artifactId)
          .build();
    }

    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(c);

    userMustHaveReadAccess(permissionSession, artifactId);

    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(c);
    folderSession.addPathAndParentId(artifact);

    artifact.setPathInfo(PathInfoBuilder.getResourcePathExtract(c, folderSession, permissionSession, artifact));

    FolderServerArtifactReport resourceReport = null;

    if (artifact.getType() == CedarResourceType.INSTANCE) {
      resourceReport = FolderServerInstanceReport.fromResource(artifact);
      decorateResourceWithIsBasedOn(folderSession, permissionSession, (FolderServerInstanceReport) resourceReport);
    } else if (artifact.getType() == CedarResourceType.FIELD) {
      resourceReport = FolderServerSchemaArtifactReport.fromResource(artifact);
      decorateResourceWithVersionHistory(folderSession, (FolderServerSchemaArtifactReport) resourceReport);
    } else if (artifact.getType() == CedarResourceType.ELEMENT) {
      resourceReport = FolderServerSchemaArtifactReport.fromResource(artifact);
      decorateResourceWithVersionHistory(folderSession, (FolderServerSchemaArtifactReport) resourceReport);
    } else if (artifact.getType() == CedarResourceType.TEMPLATE) {
      resourceReport = FolderServerSchemaArtifactReport.fromResource(artifact);
      decorateResourceWithVersionHistory(folderSession, (FolderServerSchemaArtifactReport) resourceReport);
    }

    decorateResourceWithDerivedFrom(folderSession, permissionSession, resourceReport);
    GraphDbPermissionReader.decorateResourceWithCurrentUserPermissions(c, permissionSession, cedarConfig, resourceReport);

    decorateResourceWithCategories(categorySession, resourceReport);

    addProvenanceDisplayName(resourceReport);
    addProvenanceDisplayNames(resourceReport);
    return Response.ok(resourceReport).build();
  }

  protected FolderServerCategory userMustHaveWriteAccessToCategory(CedarRequestContext context, CedarCategoryId categoryId) throws CedarException {
    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(context);
    CategoryPermissionServiceSession categoryPermissionSession =
        CedarDataServices.getCategoryPermissionServiceSession(context);

    FolderServerCategoryCurrentUserReport fsCategory = GraphDbPermissionReader.getCategoryCurrentUserReport(categorySession,
        categoryPermissionSession, categoryId);
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

  protected FolderServerCategory userMustHaveAttachAccessToCategory(CedarRequestContext context, CedarCategoryId categoryId) throws CedarException {
    CategoryServiceSession categorySession = CedarDataServices.getCategoryServiceSession(context);
    CategoryPermissionServiceSession categoryPermissionSession =
        CedarDataServices.getCategoryPermissionServiceSession(context);

    FolderServerCategoryCurrentUserReport fsCategory = GraphDbPermissionReader.getCategoryCurrentUserReport(categorySession,
        categoryPermissionSession, categoryId);
    if (fsCategory == null) {
      throw new CedarObjectNotFoundException("Category not found by id")
          .errorKey(CedarErrorKey.CATEGORY_NOT_FOUND)
          .parameter("categoryId", categoryId);
    }
    if (context.getCedarUser().has(CedarPermission.WRITE_NOT_WRITABLE_CATEGORY) ||
        fsCategory.getCurrentUserPermissions().isCanWrite() ||
        fsCategory.getCurrentUserPermissions().isCanAttach()) {
      return fsCategory;
    } else {
      throw new CedarPermissionException("You do not have write access to the category")
          .errorKey(CedarErrorKey.NO_WRITE_ACCESS_TO_CATEGORY)
          .parameter("categoryId", categoryId);
    }
  }

  protected FolderServerArtifactCurrentUserReport getArtifactReport(CedarRequestContext context, CedarArtifactId artifactId) throws CedarException {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(context);
    return GraphDbPermissionReader.getArtifactCurrentUserReport(context, folderSession, permissionSession, cedarConfig, artifactId);
  }

  protected FolderServerFolderCurrentUserReport getFolderReport(CedarRequestContext context, CedarFolderId folderId) throws CedarException {
    FolderServiceSession folderSession = CedarDataServices.getFolderServiceSession(context);
    ResourcePermissionServiceSession permissionSession = CedarDataServices.getResourcePermissionServiceSession(context);
    return GraphDbPermissionReader.getFolderCurrentUserReport(context, folderSession, permissionSession, folderId);
  }

}
