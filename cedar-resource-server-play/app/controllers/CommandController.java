package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.*;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.ElasticsearchException;
import org.metadatacenter.cedar.resource.util.FolderServerProxy;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.CedarFSFolder;
import org.metadatacenter.model.folderserver.CedarFSResource;
import org.metadatacenter.model.resourceserver.CedarRSFolder;
import org.metadatacenter.model.resourceserver.CedarRSResource;
import org.metadatacenter.server.result.BackendCallErrorType;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.Authorization;
import org.metadatacenter.server.security.CedarAuthFromRequestFactory;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.json.JsonMapper;
import play.mvc.Result;
import play.mvc.Results;
import utils.DataServices;

import java.net.UnknownHostException;

public class CommandController extends AbstractResourceServerController {

  protected static final String MOVE_COMMAND = "command/move-node-to-folder";

  protected static CedarConfig cedarConfig;
  protected final static String folderBase;

  static {
    cedarConfig = CedarConfig.getInstance();
    folderBase = cedarConfig.getServers().getFolder().getBase();
  }

  public static Result copyResourceToFolder() {

    JsonNode jsonBody = request().body().asJson();
    String id = jsonBody.get("@id").asText();
    String nodeTypeString = jsonBody.get("nodeType").asText();
    String folderId = jsonBody.get("folderId").asText();
    String titleTemplate = jsonBody.get("titleTemplate").asText();

    CedarNodeType nodeType = CedarNodeType.forValue(nodeTypeString);
    if (nodeType == null) {
      play.Logger.error("Unknown nodeType:" + nodeTypeString + ":");
      return badRequest();
    }

    CedarPermission permission = null;
    switch (nodeType) {
      case FIELD:
        permission = CedarPermission.TEMPLATE_FIELD_CREATE;
        break;
      case ELEMENT:
        permission = CedarPermission.TEMPLATE_ELEMENT_CREATE;
        break;
      case TEMPLATE:
        permission = CedarPermission.TEMPLATE_CREATE;
        break;
      case INSTANCE:
        permission = CedarPermission.TEMPLATE_INSTANCE_CREATE;
        break;
    }

    if (permission == null) {
      play.Logger.error("Unknown nodeType:" + nodeTypeString + ":");
      return badRequest();
    }

    IAuthRequest authRequest = null;
    try {
      authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(authRequest, permission);
    } catch (CedarAccessException e) {
      play.Logger.error("Access error while copying " + nodeType.getValue(), e);
      return forbiddenWithError(e);
    }


    String originalDocument = null;
    try {
      String url = templateBase + nodeType.getPrefix() + "/" + new URLCodec().encode(id);
      System.out.println(url);
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());
      ProxyUtil.proxyResponseHeaders(proxyResponse, response());
      HttpEntity entity = proxyResponse.getEntity();
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      if (entity != null) {
        originalDocument = EntityUtils.toString(entity);
        JsonNode jsonNode = JsonMapper.MAPPER.readTree(originalDocument);
        ((ObjectNode) jsonNode).remove("@id");
        JsonNode titleNode = ((ObjectNode) jsonNode).at("/_ui/title");
        if (!titleNode.isMissingNode()) {
          String newTitle = titleTemplate.replace("{{title}}", titleNode.asText());
          JsonNode ui = jsonNode.get("_ui");
          if (ui != null) {
            ((ObjectNode) ui).put("title", newTitle);
          }
        }
        originalDocument = jsonNode.toString();
      }
    } catch (Exception e) {
      play.Logger.error("Error while reading " + nodeType.getValue(), e);
      return internalServerErrorWithError(e);
    }

    /*if (originalDocument != null) {
      System.out.println("Original document:");
      System.out.println(originalDocument);
    }*/

    // TODO : from this point, this block is repeated 90% in:
    // AbstractResourceServerController.executeResourcePostByProxy
    // refactor, if possible
    try {
      CedarRSFolder targetFolder = getCedarFolderById(folderId);
      if (targetFolder == null) {
        ObjectNode errorParams = JsonNodeFactory.instance.objectNode();
        errorParams.put("folderId", folderId);
        return badRequest(generateErrorDescription("folderNotFound",
            "The folder with the given id can not be found!", errorParams));
      }

      String url = templateBase + nodeType.getPrefix();

      HttpResponse proxyResponse = ProxyUtil.post(url, request(), originalDocument);
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
          String createdId = jsonNode.get("@id").asText();

          String resourceUrl = folderBase + PREFIX_RESOURCES;
          //System.out.println(resourceUrl);
          ObjectNode resourceRequestBody = JsonNodeFactory.instance.objectNode();
          resourceRequestBody.put("parentId", targetFolder.getId());
          resourceRequestBody.put("id", createdId);
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
                    (resourceCreateResponse.getEntity().getContent(), CedarRSResource.class), jsonNode, authRequest);
                return created(proxyResponse.getEntity().getContent());
              } else {
                return ok();
              }
            } else {
              System.out.println("Resource not copied #1, rollback resource and signal error");
              return Results.status(resourceCreateStatusCode, resourceEntity.getContent());
            }
          } else {
            System.out.println("Resource not copied #2, rollback resource and signal error");
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

  public static Result moveNodeToFolder() {

    try {
      IAuthRequest frontendRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(frontendRequest, CedarPermission.LOGGED_IN);
    } catch (Exception e) {
      return internalServerErrorWithError(e);
    }

    JsonNode jsonBody = request().body().asJson();
    String sourceId = jsonBody.get("sourceId").asText();
    String nodeTypeString = jsonBody.get("nodeType").asText();
    String folderId = jsonBody.get("folderId").asText();

    CedarNodeType nodeType = CedarNodeType.forValue(nodeTypeString);
    if (nodeType == null) {
      BackendCallResult backendCallResult = new BackendCallResult();
      backendCallResult.addError(BackendCallErrorType.INVALID_ARGUMENT)
          .subType("unknownNodeType")
          .message("Unknown node type:" + nodeTypeString)
          .param("nodeType", nodeTypeString);
      return backendCallError(backendCallResult);
    }

    CedarPermission permission1 = null;
    CedarPermission permission2 = null;
    switch (nodeType) {
      case FIELD:
        permission1 = CedarPermission.TEMPLATE_FIELD_CREATE;
        permission2 = CedarPermission.TEMPLATE_FIELD_DELETE;
        break;
      case ELEMENT:
        permission1 = CedarPermission.TEMPLATE_ELEMENT_CREATE;
        permission2 = CedarPermission.TEMPLATE_ELEMENT_DELETE;
        break;
      case TEMPLATE:
        permission1 = CedarPermission.TEMPLATE_CREATE;
        permission2 = CedarPermission.TEMPLATE_DELETE;
        break;
      case INSTANCE:
        permission1 = CedarPermission.TEMPLATE_INSTANCE_CREATE;
        permission2 = CedarPermission.TEMPLATE_INSTANCE_DELETE;
        break;
      case FOLDER:
        permission1 = CedarPermission.FOLDER_CREATE;
        permission2 = CedarPermission.FOLDER_DELETE;
        break;
    }

    if (permission1 == null || permission2 == null) {
      BackendCallResult backendCallResult = new BackendCallResult();
      backendCallResult.addError(BackendCallErrorType.INVALID_ARGUMENT)
          .subType("unknownNodeType")
          .message("Unknown node type:" + nodeTypeString)
          .param("nodeType", nodeTypeString);
      return backendCallError(backendCallResult);
    }

    // Check create permission
    IAuthRequest authRequest = null;
    try {
      authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(authRequest, permission1);
    } catch (CedarAccessException e) {
      BackendCallResult backendCallResult = new BackendCallResult();
      backendCallResult.addError(BackendCallErrorType.AUTHORIZATION)
          .subType("missingPermission")
          .message("Missing permission:" + permission1)
          .param("permission", permission1);
      return backendCallError(backendCallResult);
    }

    // Check delete permission
    try {
      authRequest = CedarAuthFromRequestFactory.fromRequest(request());
      Authorization.getUserAndEnsurePermission(authRequest, permission2);
    } catch (CedarAccessException e) {
      BackendCallResult backendCallResult = new BackendCallResult();
      backendCallResult.addError(BackendCallErrorType.AUTHORIZATION)
          .subType("missingPermission")
          .message("Missing permission:" + permission2)
          .param("permission", permission2);
      return backendCallError(backendCallResult);
    }

    try {
      String folderURL = folderBase + CedarNodeType.Prefix.FOLDERS;

      // Check if the source node exists
      if (nodeType == CedarNodeType.FOLDER) {
        CedarFSFolder sourceFolder = FolderServerProxy.getFolder(folderURL, sourceId, request());
        if (sourceFolder == null) {
          BackendCallResult backendCallResult = new BackendCallResult();
          backendCallResult.addError(BackendCallErrorType.NOT_FOUND)
              .subType("sourceFolderNotFound")
              .message("The source folder can not be found:" + sourceId)
              .param("sourceId", sourceId);
          return backendCallError(backendCallResult);
        }
      } else {
        String resourceURL = folderBase + "/" + PREFIX_RESOURCES;
        CedarFSResource sourceResource = FolderServerProxy.getResource(resourceURL, sourceId, request());
        if (sourceResource == null) {
          BackendCallResult backendCallResult = new BackendCallResult();
          backendCallResult.addError(BackendCallErrorType.NOT_FOUND)
              .subType("sourceResourceNotFound")
              .message("The source resource can not be found:" + sourceId)
              .param("sourceId", sourceId);
          return backendCallError(backendCallResult);
        }
      }

      // Check if the target folder exists
      CedarFSFolder targetFolder = FolderServerProxy.getFolder(folderURL, folderId, request());
      if (targetFolder == null) {
        BackendCallResult backendCallResult = new BackendCallResult();
        backendCallResult.addError(BackendCallErrorType.NOT_FOUND)
            .subType("targetFolderNotFound")
            .message("The target folder can not be found:" + folderId)
            .param("folderId", folderId);
        return backendCallError(backendCallResult);
      }

      // Check if the user has write/delete permission to the source node
      if (nodeType == CedarNodeType.FOLDER) {
        if (!userHasWriteAccessToFolder(folderBase, sourceId)) {
          BackendCallResult backendCallResult = new BackendCallResult();
          backendCallResult.addError(BackendCallErrorType.AUTHORIZATION)
              .subType("missingPermission")
              .message("The user has no write access to the source folder:" + sourceId)
              .param("sourceId", sourceId);
          return backendCallError(backendCallResult);
        }
      } else {
        if (!userHasWriteAccessToResource(folderBase, sourceId)) {
          BackendCallResult backendCallResult = new BackendCallResult();
          backendCallResult.addError(BackendCallErrorType.AUTHORIZATION)
              .subType("missingPermission")
              .message("The user has no write access to the source resource:" + sourceId)
              .param("sourceId", sourceId);
          return backendCallError(backendCallResult);
        }
      }
      // Check if the user has write permission to the target folder
      if (!userHasWriteAccessToFolder(folderBase, folderId)) {
        BackendCallResult backendCallResult = new BackendCallResult();
        backendCallResult.addError(BackendCallErrorType.AUTHORIZATION)
            .subType("missingPermission")
            .message("The user has no write access to the target folder:" + folderId)
            .param("folderId", folderId);
        return backendCallError(backendCallResult);
      }
    } catch (CedarAccessException e) {
      play.Logger.error("Access Error while deleting the template", e);
      return forbiddenWithError(e);
    }


    try {
      String resourceUrl = folderBase + MOVE_COMMAND;
      ObjectNode folderRequestBody = JsonNodeFactory.instance.objectNode();

      folderRequestBody.put("sourceId", sourceId);
      folderRequestBody.put("nodeType", nodeTypeString);
      folderRequestBody.put("folderId", folderId);

      String folderRequestBodyAsString = JsonMapper.MAPPER.writeValueAsString(folderRequestBody);

      HttpResponse nodeMoveResponse = ProxyUtil.proxyPost(resourceUrl, request(), folderRequestBodyAsString);
      int nodeMoveStatusCode = nodeMoveResponse.getStatusLine().getStatusCode();
      HttpEntity folderEntity = nodeMoveResponse.getEntity();
      if (folderEntity != null) {
        if (HttpStatus.SC_CREATED == nodeMoveStatusCode) {
          if (folderEntity.getContent() != null) {
            // index the resource that has been created
            return created(folderEntity.getContent());
          } else {
            return created();
          }
        } else {
          System.out.println("Resource not moved");
          return Results.status(nodeMoveStatusCode, folderEntity.getContent());
        }
      } else {
        System.out.println("Resource not moved");
        return Results.status(nodeMoveStatusCode);
      }
    } catch (Exception e) {
      play.Logger.error("Error while moving the node", e);
      return internalServerErrorWithError(e);
    }
  }

}
