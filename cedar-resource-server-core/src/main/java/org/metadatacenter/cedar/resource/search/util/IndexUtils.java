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
import org.metadatacenter.model.index.CedarIndexField;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.server.security.exception.CedarAccessException;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.server.security.model.auth.CedarPermission;

import java.io.IOException;
import java.util.*;

import static org.metadatacenter.constant.ResourceConstants.FOLDER_ALL_NODES;

public class IndexUtils {

  private String folderBase;
  private String templateBase;
  private int limit;
  private int maxAttemps;
  private int delayAttemps;
  private static CedarConfig cedarConfig;

  static {
    cedarConfig = CedarConfig.getInstance();
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

  /***
   * Fields Extraction
   ***/

  // Recursively extract all field names and values
  public List<CedarIndexField> extractFields(CedarNodeType nodeType, JsonNode resourceContent, IAuthRequest authRequest)
      throws JsonProcessingException, CedarAccessException, EncoderException {
    List<CedarIndexField> fields = null;
    // Templates and Elements
    if (nodeType.equals(CedarNodeType.TEMPLATE) || (nodeType.equals(CedarNodeType.ELEMENT))) {
      HashMap<String, CedarIndexField> fieldsInfo = extractFieldsInfo(nodeType, null, resourceContent, new HashMap(),
          null);
      fields = new ArrayList<>(fieldsInfo.values());
    }
    // Instances
    else if (nodeType.equals(CedarNodeType.INSTANCE)) {
      HashMap<String, CedarIndexField> fieldsInfo = extractFieldsInfo(nodeType, null, resourceContent, new HashMap(),
          authRequest);

      System.out.println("*************** FIELDS INFO ***************");
      for (String k : fieldsInfo.keySet()) {
        System.out.println(k);
        System.out.println(fieldsInfo.get(k));
        System.out.println("----");
      }

      HashMap<String, CedarIndexField> valuesInfo = extractFieldValuesInfo(nodeType, null, resourceContent, new
          HashMap());


      System.out.println("*************** VALUES INFO ***************");
      for (String k : valuesInfo.keySet()) {
        System.out.println(k);
        System.out.println(valuesInfo.get(k));
        System.out.println("----");
      }

      fields = new ArrayList<>();
      // Combine fields information with values information
      for (CedarIndexField v : new ArrayList<>(valuesInfo.values())) {
        CedarIndexField f = fieldsInfo.get(v.getFieldPath());
        if (f == null) {
          throw new RuntimeException("Field path not found: " + v.getValuePath());
        }
        fields.add(new CedarIndexField(v.getFieldPath(), f.getFieldName(), f.getFieldType(), v.getValuePath(), v.getValueLabel(), v
            .getValueType(), f.getUseForValueRecommendation()));
      }
    }
    return fields;
  }

  // Recursively extract all field names.
  // - currentPath: field path using Json Pointer syntax
  private HashMap<String, CedarIndexField> extractFieldsInfo(CedarNodeType nodeType, String currentPath, JsonNode
      resourceContent, HashMap<String, CedarIndexField> results, IAuthRequest authRequest) throws EncoderException,
      CedarAccessException {
    currentPath = currentPath == null ? "/" : currentPath;
    if (nodeType.compareTo(CedarNodeType.TEMPLATE) == 0 || nodeType.compareTo(CedarNodeType.ELEMENT) == 0) {
      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();
        if (field.getValue().isContainerNode()) {
          String fieldKey = field.getKey();
          JsonNode fieldNode = null;
          // Single-instance fields
          if (field.getValue().get("items") == null) {
            fieldNode = field.getValue();
          }
          // Multi-instance fields
          else {
            fieldNode = field.getValue().get("items");
          }
          // Field
          if (fieldNode.get("@type") != null
              && fieldNode.get("@type").asText().equals(CedarNodeType.FIELD.getAtType())
              && fieldNode.get("_ui") != null
              && fieldNode.get("_ui").get("title") != null) {
            String fieldName = fieldNode.get("_ui").get("title").asText();
            // Update current path
            String fieldPath = currentPath.equals("/") ? currentPath + fieldKey : currentPath + "/" + fieldKey;
            // Get field type (if it has been defined)
            String fieldType = null;
            if (fieldNode.get("properties").get("@type").get("oneOf").get(0).get("enum") != null) {
              fieldType = fieldNode.get("properties").get("@type").get("oneOf").get(0).get("enum").get(0).asText();
            }
            CedarIndexField f = new CedarIndexField(fieldPath, fieldName, fieldType, null, null, null, true);
            results.put(fieldPath, f);

          } else {
            // Element
            if (fieldNode.get("@type") != null && fieldNode.get("@type").asText().equals(CedarNodeType.ELEMENT.getAtType())) {
              // Update current path
              String elementPath = currentPath.equals("/") ? currentPath + fieldKey : currentPath + "/" + fieldKey;
              extractFieldsInfo(nodeType, elementPath, fieldNode, results, authRequest);
            }
            // Other nodes
            else {
              extractFieldsInfo(nodeType, currentPath, fieldNode, results, authRequest);
            }
          }
        }
      }
    // If the resource is an instance, the field names must be extracted from the template
    } else if (nodeType.compareTo(CedarNodeType.INSTANCE) == 0) {
      if (resourceContent.get("schema:isBasedOn") != null) {
        String templateId = resourceContent.get("schema:isBasedOn").asText();
        JsonNode templateJson = null;
        try {
          templateJson = findResourceContent(templateId, CedarNodeType.TEMPLATE, authRequest);
          results = extractFieldsInfo(CedarNodeType.TEMPLATE, currentPath, templateJson, results, authRequest);
        } catch (IOException e) {
          System.out.println("Error while accessing the reference template for the instance. It may have been " +
              "removed");
        }
      }
    }
    return results;
  }

  // Recursively extract all field values (used only for instances). currentPath is the field path using Json
  // Pointer syntax
  private HashMap<String, CedarIndexField> extractFieldValuesInfo(CedarNodeType nodeType, String
      currentPath, JsonNode resourceContent, HashMap<String, CedarIndexField> results) throws JsonProcessingException {
    currentPath = currentPath == null ? "/" : currentPath;
    if (nodeType.compareTo(CedarNodeType.INSTANCE) == 0) {
      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();
        if (field.getValue().isContainerNode() && !field.getKey().equals("@context")) {
          // Single value
          if (!field.getValue().isArray()) {
            JsonNode valueNode = field.getValue().get("@value");
            if (valueNode != null) {
              String fieldValue = "";
              if (valueNode.isTextual()) {
                fieldValue = valueNode.asText();
              }
              // Numeric field
              else if (valueNode.isNumber()) {
                fieldValue = new ObjectMapper().writeValueAsString(valueNode);
              }
              if (fieldValue != null) {
                fieldValue = fieldValue.trim();
                String valueLabel = null;
                String valueType = null;
                // Set value label and value type
                if (field.getValue().get("_valueLabel") != null) {
                  valueLabel = field.getValue().get("_valueLabel").asText();
                  valueType = fieldValue;
                } else {
                  valueLabel = fieldValue;
                }
                // Update current path
                String valuePath = currentPath.equals("/") ? currentPath + field.getKey() : currentPath + "/" +
                    field.getKey();
                String fieldPath = valuePath.replaceAll("/[0-9]+/", "/");
                CedarIndexField f = new CedarIndexField(fieldPath, null, null, valuePath, valueLabel, valueType, true);
                results.put(valuePath, f);
              }
            } else {
              // Update current path
              String fieldPath = currentPath.equals("/") ? currentPath + field.getKey() : currentPath + "/" + field
                  .getKey();
              extractFieldValuesInfo(nodeType, fieldPath, field.getValue(), results);
            }
          }
          // Multi-instance value
          else {
            int count = 1;
            for (JsonNode instance : field.getValue()) {
              // Update current path
              String fieldPath = currentPath.equals("/") ? currentPath + field.getKey() : currentPath + "/" + field
                  .getKey();
              fieldPath = fieldPath + "/" + count;
              extractFieldValuesInfo(nodeType, fieldPath, instance, results);
              count++;
            }
          }
        }
      }
    }
    return results;
  }

}

