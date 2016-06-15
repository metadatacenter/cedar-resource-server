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
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.PathComponent;
import org.metadatacenter.model.resourceserver.CedarRSFolder;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.model.resourceserver.CedarRSResource;
import org.metadatacenter.server.play.AbstractCedarController;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.server.security.model.user.CedarUserSummary;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import utils.DataServices;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;

public abstract class AbstractResourceServerController extends AbstractCedarController {

  protected static final String PREFIX_RESOURCES = "resources";

  protected static CedarConfig cedarConfig;
  protected final static String folderBase;
  protected final static String templateBase;
  protected final static String usersBase;
  protected final static ObjectMapper MAPPER = new ObjectMapper();

  static {
    cedarConfig = CedarConfig.getInstance();
    folderBase = cedarConfig.getServers().getFolder().getBase();
    templateBase = cedarConfig.getServers().getTemplate().getBase();
    usersBase = cedarConfig.getServers().getUser().getUsersBase();
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
        resource.setOwnedByUserName(updater.getScreenName());
      }
    }
    return resource;
  }

  protected static CedarRSNode setUserHomeFolderDisplayName(CedarRSNode resource, Http.Request request) {
    if (resource != null) {
      if (resource instanceof CedarRSFolder) {
        CedarRSFolder f = (CedarRSFolder) resource;
        if (f.isUserHome()) {
          CedarUserSummary owner = getUserSummary(request, f.getName());
          if (owner != null) {
            resource.setDisplayName(owner.getScreenName());
          }
        }
      }
      if (resource.getDisplayName() == null) {
        resource.setDisplayName(resource.getName());
      }
    }
    return resource;
  }

  protected static void setDisplayPaths(CedarRSNode resource, Http.Request request) {
    StringBuilder sb = new StringBuilder();
    List<PathComponent> pathComponents = resource.getPathComponents();
    if (pathComponents != null) {
      for (int i = 0; i < pathComponents.size(); i++) {
        PathComponent pc = pathComponents.get(i);
        if (i > 1) {
          sb.append("/");
        }
        if (pc.isUserHome()) {
          CedarUserSummary user = getUserSummary(request, pc.getName());
          if (user != null) {
            sb.append(user.getScreenName());
          } else {
            sb.append(pc.getName());
          }
        } else {
          sb.append(pc.getName());
        }
        if (i == pathComponents.size() - 2) {
          resource.setDisplayParentPath(sb.toString());
        } else if (i == pathComponents.size() - 1) {
          resource.setDisplayPath(sb.toString());
        }
      }
    }
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
          JsonNode jsonNode = MAPPER.readTree(userSummaryString);
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
    CedarRSNode resource = deserializeResource(proxyResponse);
    resource.setDisplayName(resource.getName());
    addProvenanceDisplayName(resource, request);
    setUserHomeFolderDisplayName(resource, request);
    setDisplayPaths(resource, request);
    return MAPPER.valueToTree(resource);
  }


  protected static String responseAsJsonString(HttpResponse proxyResponse) throws IOException {
    return EntityUtils.toString(proxyResponse.getEntity());
  }

  // Proxy methods for resource types
  protected static Result executeResourcePostByProxy(CedarNodeType nodeType, CedarPermission permission) {
    IAuthRequest authRequest = null;
    try {
      authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(authRequest, permission);
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
      //System.out.println(url);

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
          //System.out.println(resourceUrl);
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", targetFolder.getId());
          resourceRequestBody.put("id", id);
          resourceRequestBody.put("nodeType", nodeType.getValue());
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
                DataServices.getInstance().getSearchService().indexResource(MAPPER.readValue(resourceCreateResponse
                        .getEntity().getContent(),
                    CedarRSResource.class), jsonNode, authRequest);
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
    if (nodeType == CedarNodeType.FIELD || nodeType == CedarNodeType.ELEMENT || nodeType == CedarNodeType.TEMPLATE ||
        nodeType == CedarNodeType.INSTANCE) {
      JsonNode titleNode = jsonNode.at("/_ui/title");
      if (titleNode != null && !titleNode.isMissingNode()) {
        title = titleNode.textValue();
      }
    }
    return title;
  }

  protected static String extractDescriptionFromResponseObject(CedarNodeType nodeType, JsonNode jsonNode) {
    String description = "";
    if (nodeType == CedarNodeType.FIELD || nodeType == CedarNodeType.ELEMENT || nodeType == CedarNodeType.TEMPLATE ||
        nodeType == CedarNodeType.INSTANCE) {
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
      Authorization.getUserAndEnsurePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while reading " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

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

  protected static Result executeResourceGetDetailsByProxy(CedarNodeType nodeType, CedarPermission permission, String
      id) {
    try {
      IAuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while reading details of " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

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

  protected static Result executeResourcePutByProxy(CedarNodeType nodeType, CedarPermission permission, String id) {
    IAuthRequest authRequest = null;
    try {
      authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while updating " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

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
          JsonNode jsonNode = MAPPER.readTree(entityContent);

          String resourceUrl = folderBase + PREFIX_RESOURCES + "/" + new URLCodec().encode(id);
          //System.out.println(resourceUrl);

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
                DataServices.getInstance().getSearchService().updateIndexedResource(MAPPER.readValue
                    (resourceUpdateResponse.getEntity().getContent(),
                        CedarRSResource.class), jsonNode, authRequest);
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


  protected static Result executeResourceDeleteByProxy(CedarNodeType nodeType, CedarPermission permission, String id) {
    try {
      IAuthRequest authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while deleting " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }

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

}
