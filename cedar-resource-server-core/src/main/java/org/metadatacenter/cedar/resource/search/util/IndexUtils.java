package org.metadatacenter.cedar.resource.search.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.cedar.resource.constants.SearchConstants;
import org.metadatacenter.constant.ConfigConstants;
import org.metadatacenter.constant.HttpConnectionConstants;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import javax.xml.ws.http.HTTPException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class IndexUtils {

  private static final String folderBase;
  private static final String templateBase;
  private static final String FOLDER_ALL_NODES = "nodes";

  static {
    folderBase = "https://folder." + System.getenv("CEDAR_HOST") + "/";
    templateBase = "https://template." + System.getenv("CEDAR_HOST") + "/";
  }

  /**
   * This method retrieves all the resources from the Folder Server that are expected to be in the search index. Those
   * resources that don't have to be in the index, such as the "/" folder and the "Lost+Found" folder are ignored.
   */
  public static List<CedarRSNode> findAllResources(String apiKey) throws IOException, InterruptedException {
    play.Logger.info("Retrieving all resources:");
    List<CedarRSNode> resources = new ArrayList<>();
    boolean finished = false;
    String baseUrl = folderBase + FOLDER_ALL_NODES;
    int offset = 0;
    int limit = 50;
    int countSoFar = 0;
    while (!finished) {
      String url = baseUrl + "?offset=" + offset + "&limit=" + limit;
      play.Logger.info("Retrieving resources from Folder Server. Url: " + url);
      // Build request
      Request request = Request.Get(url)
          .connectTimeout(HttpConnectionConstants.CONNECTION_TIMEOUT)
          .socketTimeout(HttpConnectionConstants.SOCKET_TIMEOUT);
      String authHeaderValue = "apiKey " + apiKey;
      request.addHeader(HttpHeaders.AUTHORIZATION, authHeaderValue);
      // Execute request
      HttpResponse response = null;
      int statusCode = -1;
      int attemp = 1;
      int maxAttemps = 3;
      while (true) {
        response = request.execute().returnResponse();
        statusCode = response.getStatusLine().getStatusCode();
        if ((statusCode != 502) || (attemp > maxAttemps)) {
          break;
        } else {
          play.Logger.info("Failed to retrieve resource. The Folder Server might have not been started yet. " +
              "Retrying... (attemp " + attemp + "/" + maxAttemps + ")");
          attemp++;
          Thread.sleep(10000);
        }
      }
      // The resources were successfully retrieved
      if (statusCode == 200) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode resultJson = mapper.readTree(EntityUtils.toString(response.getEntity()));
        int count = resultJson.get("resources").size();
        int totalCount = resultJson.get("totalCount").asInt();
        countSoFar += count;
        play.Logger.info("Retrieved " + countSoFar + "/" + totalCount + " resources");
        int currentOffset = resultJson.get("currentOffset").asInt();
        for (JsonNode resource : resultJson.get("resources")) {
          // Check if the resource is meant to be indexed. Otherwise it will be ignored
          if (!SearchConstants.RESOURCES_NOT_IN_INDEX.contains(resource.get("name").asText())) {
            resources.add(mapper.convertValue(resource, CedarRSNode.class));
          }
          else {
            play.Logger.info("The resource '" + resource.get("name").asText() + "' has been ignored");
          }
        }
        if (currentOffset + count >= totalCount) {
          finished = true;
        } else {
          offset = offset + count;
        }
      } else {
        throw new IOException("Error retrieving resources from the folder server. HTTP status code: " +
            statusCode + " (" + response.getStatusLine().getReasonPhrase() + ")");
      }
    }
    return resources;
  }

  /**
   * Returns the full content of a particular resource
   */
  public static JsonNode findResourceContent(String resourceId, CedarNodeType resourceType, String apiKey) throws
      CedarAccessException, IOException, EncoderException {
    CedarPermission permission = null;
    String resourceUrl = templateBase;
    if (resourceType == CedarNodeType.TEMPLATE) {
      permission = CedarPermission.TEMPLATE_READ;
      resourceUrl += "templates";
    } else if (resourceType == CedarNodeType.ELEMENT) {
      permission = CedarPermission.TEMPLATE_ELEMENT_READ;
      resourceUrl += "template-elements";
    } else if (resourceType == CedarNodeType.FIELD) {
      permission = CedarPermission.TEMPLATE_FIELD_READ;
      resourceUrl += "template-fields";
    } else if (resourceType == CedarNodeType.INSTANCE) {
      permission = CedarPermission.TEMPLATE_INSTANCE_READ;
      resourceUrl += "template-instances";
    } else {
      return null;
    }
    resourceUrl += "/" + new URLCodec().encode(resourceId);
    // Retrieve resource by id
    JsonNode resource = null;
    // Build request
    Request request = Request.Get(resourceUrl)
        .connectTimeout(HttpConnectionConstants.CONNECTION_TIMEOUT)
        .socketTimeout(HttpConnectionConstants.SOCKET_TIMEOUT);
    String authHeaderValue = "apiKey " + apiKey;
    request.addHeader(HttpHeaders.AUTHORIZATION, authHeaderValue);
    // Execute request
    HttpResponse response = request.execute().returnResponse();
    int statusCode = response.getStatusLine().getStatusCode();
    if (statusCode == 200) {
      String resourceString = EntityUtils.toString(response.getEntity());
      resource = new ObjectMapper().readTree(resourceString);
    } else {
      throw new IOException("Error while retrieving resource content");
    }
    return resource;
  }

  // Recursively extract all field names
  public static List<String> extractFieldNames(CedarNodeType resourceType, JsonNode resourceContent, List<String>
      results) {
    if (resourceType.compareTo(CedarNodeType.TEMPLATE) == 0
        || resourceType.compareTo(CedarNodeType.ELEMENT) == 0) {
      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();
        if (field.getValue().isContainerNode()) {
          if (field.getValue().get("@type") != null
              && field.getValue().get("@type").asText()
              .compareTo("https://schema.metadatacenter.org/core/TemplateField") == 0
              && field.getValue().get("_ui") != null
              && field.getValue().get("_ui").get("title") != null) {
            String fieldName = field.getValue().get("_ui").get("title").asText();
            results.add(fieldName);
          } else {
            extractFieldNames(resourceType, field.getValue(), results);
          }
        }
      }
    } else if (resourceType.compareTo(CedarNodeType.INSTANCE) == 0) {
//        if (resourceContent.get("_templateId") != null) {
//          String templateId = resourceContent.get("_templateId").asText();
//          Result result = controllers.TemplateServerController.findTemplate(templateId);
//          byte[] body = JavaResultExtractor.getBody(result, 10000);
//          String header = JavaResultExtractor.getHeaders(result).get("Content-Type");
//          String charset = "utf-8";
//          if(header != null && header.contains("; charset=")){
//            charset = header.substring(header.indexOf("; charset=") + 10, header.length()).trim();
//          }
//          String bodyStr = null;
//          try {
//            bodyStr = new String(body, charset);
//            JsonNode templateJson = new ObjectMapper().readTree(bodyStr);
//            results = extractFieldNames(CedarNodeType.TEMPLATE, templateJson, results);
//          } catch (UnsupportedEncodingException e) {
//            e.printStackTrace();
//          } catch (JsonProcessingException e) {
//            e.printStackTrace();
//          } catch (IOException e) {
//            e.printStackTrace();
//          }
//        }
    }
    return results;
  }

  // Recursively extract all field values (only for instances)
  public static List<String> extractFieldValues(CedarNodeType resourceType, JsonNode resourceContent, List<String>
      results) {
    if (resourceType.compareTo(CedarNodeType.INSTANCE) == 0) {
      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();
        if (field.getValue().isContainerNode()) {
          if (field.getKey().compareTo("@context") != 0 && field.getValue().get("_value") != null) {
            String fieldValue = field.getValue().get("_value").asText();
            results.add(fieldValue);
          } else {
            extractFieldValues(resourceType, field.getValue(), results);
          }
        }
      }
    }
    return results;
  }


}
