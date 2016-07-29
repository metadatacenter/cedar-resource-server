package org.metadatacenter.cedar.resource.search.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.constant.ResourceConstants.FOLDER_ALL_NODES;

public class IndexUtils {

  private String folderBase;
  private String templateBase;
  private int limit;
  private int maxAttemps;
  private int delayAttemps;
  private static CedarConfig cedarConfig;
  private static String SCHEMA_FIELD;

  static {
    cedarConfig = CedarConfig.getInstance();
    SCHEMA_FIELD = cedarConfig.getLinkedDataAtType(CedarNodeType.FIELD);
  }

  public IndexUtils(String folderBase, String templateBase, int limit, int maxAttemps, int delayAttemps) {
    this.folderBase = folderBase;
    this.templateBase = templateBase;
    this.limit = limit;
    this.maxAttemps = maxAttemps;
    this.delayAttemps = delayAttemps;
  }

  /**
   * This method retrieves all the resources from the Folder Server that are expected to be in the search index. Those
   * resources that don't have to be in the index, such as the "/" folder and the "Lost+Found" folder are ignored.
   */
  public List<CedarRSNode> findAllResources(IAuthRequest authRequest) throws IOException, InterruptedException {
    play.Logger.info("Retrieving all resources:");
    List<CedarRSNode> resources = new ArrayList<>();
    boolean finished = false;
    String baseUrl = folderBase + FOLDER_ALL_NODES;
    int offset = 0;
    int countSoFar = 0;
    while (!finished) {
      // TODO: read this limit from config file
      limit = 10000;
      String url = baseUrl + "?offset=" + offset + "&limit=" + limit;
      play.Logger.info("Retrieving resources from Folder Server. Url: " + url);
      int statusCode = -1;
      int attemp = 1;
      HttpResponse response = null;
      while (true) {
        response = ProxyUtil.proxyGet(url, authRequest);
        statusCode = response.getStatusLine().getStatusCode();
        if ((statusCode != HttpStatus.SC_BAD_GATEWAY) || (attemp > maxAttemps)) {
          break;
        } else {
          play.Logger.info("Failed to retrieve resource. The Folder Server might have not been started yet. " +
              "Retrying... (attemp " + attemp + "/" + maxAttemps + ")");
          attemp++;
          Thread.sleep(delayAttemps);
        }
      }
      // The resources were successfully retrieved
      if (statusCode == HttpStatus.SC_OK) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode resultJson = mapper.readTree(EntityUtils.toString(response.getEntity()));
        int count = resultJson.get("resources").size();
        int totalCount = resultJson.get("totalCount").asInt();
        countSoFar += count;
        play.Logger.info("Retrieved " + countSoFar + "/" + totalCount + " resources");
        int currentOffset = resultJson.get("currentOffset").asInt();
        for (JsonNode resource : resultJson.get("resources")) {
          boolean indexResource = true;
          // Check if the resource has to be indexed. System and user home folders are ignored
          String nodeType = resource.get("nodeType").asText();
          if (nodeType.equals(CedarNodeType.FOLDER.getValue())) {
            if (resource.get("isSystem").asBoolean() || resource.get("isUserHome").asBoolean()) {
              indexResource = false;
            }
          }
          if (nodeType.equals(CedarNodeType.USER.getValue())) {
            indexResource = false;
          }
          if (indexResource) {
            resources.add(mapper.convertValue(resource, CedarRSNode.class));
          } else {
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
  public JsonNode findResourceContent(String resourceId, CedarNodeType nodeType, IAuthRequest authRequest) throws
      CedarAccessException, IOException, EncoderException {
    CedarPermission permission = null;
    String resourceUrl = templateBase + nodeType.getPrefix();
    resourceUrl += "/" + new URLCodec().encode(resourceId);
    // Retrieve resource by id
    JsonNode resource = null;
    HttpResponse response = ProxyUtil.proxyGet(resourceUrl, authRequest);
    int statusCode = response.getStatusLine().getStatusCode();
    if (statusCode == HttpStatus.SC_OK) {
      String resourceString = EntityUtils.toString(response.getEntity());
      resource = new ObjectMapper().readTree(resourceString);
    } else {
      throw new IOException("Error while retrieving resource content");
    }
    return resource;
  }

  // Recursively extract all field names
  public List<String> extractFieldNames(CedarNodeType nodeType, JsonNode resourceContent, List<String>
      results, IAuthRequest authRequest) throws EncoderException, CedarAccessException {
    if (nodeType.compareTo(CedarNodeType.TEMPLATE) == 0
        || nodeType.compareTo(CedarNodeType.ELEMENT) == 0) {
      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();
        if (field.getValue().isContainerNode()) {
          if (field.getValue().get("@type") != null
              && field.getValue().get("@type").asText().compareTo(SCHEMA_FIELD) == 0
              && field.getValue().get("_ui") != null
              && field.getValue().get("_ui").get("title") != null) {
            String fieldName = field.getValue().get("_ui").get("title").asText();
            results.add(fieldName);
          } else {
            extractFieldNames(nodeType, field.getValue(), results, authRequest);
          }
        }
      }
      // If the resource is an instance, the field names must be extracted from the template
    } else if (nodeType.compareTo(CedarNodeType.INSTANCE) == 0) {
      if (resourceContent.get("_templateId") != null) {
        String templateId = resourceContent.get("_templateId").asText();
        JsonNode templateJson = null;
        try {
          templateJson = findResourceContent(templateId, CedarNodeType.TEMPLATE, authRequest);
          results = extractFieldNames(CedarNodeType.TEMPLATE, templateJson, results, authRequest);
        } catch (IOException e) {
          System.out.println("Error while accessing the reference template for the instance. It may have been removed");
        }
      }
    }
    return results;
  }

  // Recursively extract all field values (only for instances)
  public List<String> extractFieldValues(CedarNodeType nodeType, JsonNode resourceContent, List<String>
      results) throws JsonProcessingException {
    if (nodeType.compareTo(CedarNodeType.INSTANCE) == 0) {
      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();
        if (field.getValue().isContainerNode()) {
          JsonNode valueNode = field.getValue().get("_value");
          if (field.getKey().compareTo("@context") != 0 && valueNode != null) {
            String fieldValue = "";
            if (valueNode.isTextual()) {
              fieldValue = valueNode.asText();
            }
            // Multiple choice field
            else if (valueNode.isArray()) {
              for (JsonNode n : valueNode) {
                fieldValue += n.asText() + " ";
              }
            }
            // Checkbox field
            else if (valueNode.isContainerNode()) {
              Iterator<Map.Entry<String, JsonNode>> it = valueNode.fields();
              while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                if (f.getValue().asBoolean() == true) {
                  fieldValue += f.getKey() + " ";
                }
              }
            }
            // Numeric field
            else if (valueNode.isNumber()) {
              fieldValue = new ObjectMapper().writeValueAsString(valueNode);
            }
            if (fieldValue != null) {
              results.add(fieldValue.trim());
            }
          } else {
            extractFieldValues(nodeType, field.getValue(), results);
          }
        }
      }
    }
    return results;
  }

}

