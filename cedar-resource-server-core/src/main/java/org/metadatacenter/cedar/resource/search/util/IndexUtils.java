package org.metadatacenter.cedar.resource.search.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.folderserver.FolderServerNode;
import org.metadatacenter.model.index.CedarIndexFieldSchema;
import org.metadatacenter.model.index.CedarIndexFieldValue;
import org.metadatacenter.rest.exception.CedarProcessingException;
import org.metadatacenter.server.security.model.auth.CedarPermission;
import org.metadatacenter.util.http.CedarEntityUtil;
import org.metadatacenter.util.http.CedarUrlUtil;
import org.metadatacenter.util.http.ProxyUtil;
import org.metadatacenter.util.json.JsonMapper;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.constant.ResourceConstants.FOLDER_ALL_NODES;

public class IndexUtils {

  private final String FIELD_SUFFIX = "_field";

  private String folderBase;
  private String templateBase;
  private int limit;
  private int maxAttemps;
  private int delayAttemps;

  private enum ESType {
    STRING, LONG, INTEGER, SHORT, DOUBLE, FLOAT, DATE, BOOLEAN;

    public String toString() {
      return name().toLowerCase();
    }
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
  public List<FolderServerNode> findAllResources(HttpServletRequest request) throws CedarProcessingException {
    play.Logger.info("Retrieving all resources:");
    List<FolderServerNode> resources = new ArrayList<>();
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
        response = ProxyUtil.proxyGet(url, request);
        statusCode = response.getStatusLine().getStatusCode();
        if ((statusCode != HttpStatus.SC_BAD_GATEWAY) || (attemp > maxAttemps)) {
          break;
        } else {
          play.Logger.info("Failed to retrieve resource. The Folder Server might have not been started yet. " +
              "Retrying... (attemp " + attemp + "/" + maxAttemps + ")");
          attemp++;
          try {
            Thread.sleep(delayAttemps);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }
      // The resources were successfully retrieved
      if (statusCode == HttpStatus.SC_OK) {
        JsonNode resultJson = null;
        try {
          resultJson = JsonMapper.MAPPER.readTree(CedarEntityUtil.toString(response.getEntity()));
        } catch (Exception e) {
          throw new CedarProcessingException(e);
        }
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
            resources.add(JsonMapper.MAPPER.convertValue(resource, FolderServerNode.class));
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
        throw new CedarProcessingException("Error retrieving resources from the folder server. HTTP status code: " +
            statusCode + " (" + response.getStatusLine().getReasonPhrase() + ")");
      }
    }
    return resources;
  }

  /**
   * Returns the full content of a particular resource
   */
  public JsonNode findResourceContent(String resourceId, CedarNodeType nodeType, HttpServletRequest request) throws
      CedarProcessingException {
    try {
      CedarPermission permission = null;
      String resourceUrl = templateBase + nodeType.getPrefix();
      resourceUrl += "/" + CedarUrlUtil.urlEncode(resourceId);
      // Retrieve resource by id
      JsonNode resource = null;
      HttpResponse response = ProxyUtil.proxyGet(resourceUrl, request);
      int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode == HttpStatus.SC_OK) {
        String resourceString = EntityUtils.toString(response.getEntity());
        resource = JsonMapper.MAPPER.readTree(resourceString);
      } else {
        throw new CedarProcessingException("Error while retrieving resource content");
      }
      return resource;
    } catch (IOException e) {
      throw new CedarProcessingException(e);
    }
  }

  // Returns summary of resourceContent. There is no need to index the full JSON for each resource. Only the
  // information necessary to satisfy search and value recommendation use cases is kept.
  public JsonNode extractSummarizedContent(CedarNodeType nodeType, JsonNode resourceContent, HttpServletRequest request)
      throws CedarProcessingException {
    try {
      // Templates and Elements
      if (nodeType.equals(CedarNodeType.TEMPLATE) || (nodeType.equals(CedarNodeType.ELEMENT))) {
        JsonNode schemaSummary = extractSchemaSummary(nodeType, resourceContent, JsonNodeFactory.instance.objectNode
            (), null);
        return schemaSummary;
      }
      // Instances
      else if (nodeType.equals(CedarNodeType.INSTANCE)) {
        // TODO: avoid calling this method multiple times when posting multiple instances for the same template
        JsonNode schemaSummary = extractSchemaSummary(nodeType, resourceContent, JsonNodeFactory.instance.objectNode(),
            request);

        JsonNode valuesSummary = extractValuesSummary(nodeType, schemaSummary, resourceContent, JsonNodeFactory
            .instance.objectNode());

        return valuesSummary;
      } else {
        throw new InternalError("Invalid node type: " + nodeType);
      }
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }
  }

  private JsonNode extractSchemaSummary(CedarNodeType nodeType, JsonNode resourceContent, JsonNode results,
                                        HttpServletRequest request) throws CedarProcessingException {
    if (nodeType.compareTo(CedarNodeType.TEMPLATE) == 0 || nodeType.compareTo(CedarNodeType.ELEMENT) == 0) {

      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();
        final String fieldKey = field.getKey();
        if (field.getValue().isContainerNode()) {
          JsonNode fieldNode;
          // Single-instance fields
          if (field.getValue().has("items") == false) {
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
            String fieldType = getFieldType(fieldNode.get("_ui").get("inputType").asText());
            // Get field semantic type (if it has been defined)
            String fieldSemanticType = null;
            if (fieldNode.get("properties").get("@type").get("oneOf").get(0).get("enum") != null) {
              fieldSemanticType = fieldNode.get("properties").get("@type").get("oneOf").get(0).get("enum").get(0)
                  .asText();
            }
            CedarIndexFieldSchema f = new CedarIndexFieldSchema();
            f.setFieldName(fieldName);
            f.setFieldSemanticType(fieldSemanticType);
            f.setFieldValueType(fieldType);
            String outputFieldKey = fieldKey + FIELD_SUFFIX;
            // Add object to the results
            ((ObjectNode) results).set(outputFieldKey, JsonMapper.MAPPER.valueToTree(f));
          } else {
            // Element
            if (fieldNode.get("@type") != null && fieldNode.get("@type").asText().equals(CedarNodeType.ELEMENT
                .getAtType())) {
              // Add empty object to the results
              ((ObjectNode) results).set(fieldKey, JsonNodeFactory.instance.objectNode());
              extractSchemaSummary(nodeType, fieldNode, results.get(fieldKey), request);
            }
            // Other nodes
            else {
              extractSchemaSummary(nodeType, fieldNode, results, request);
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
          templateJson = findResourceContent(templateId, CedarNodeType.TEMPLATE, request);
          results = extractSchemaSummary(CedarNodeType.TEMPLATE, templateJson, results, request);
        } catch (CedarProcessingException e) {
          System.out.println("Error while accessing the reference template for the instance. It may have been " +
              "removed");
        }
      }
    }
    return results;
  }

  // TODO: add remaining field types
  private String getFieldType(String inputType) {
    if (inputType.equals("textfield")) {
      return ESType.STRING.toString();
    } else {
      return ESType.STRING.toString();
    }
  }

  private JsonNode extractValuesSummary(CedarNodeType nodeType, JsonNode schemaSummary, JsonNode resourceContent,
                                        JsonNode results) throws JsonProcessingException {
    if (nodeType.compareTo(CedarNodeType.INSTANCE) == 0) {
      Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();

        if (field.getValue().isContainerNode()) {
          if (!field.getKey().equals("@context")) {
            // Single value
            if (field.getValue().isObject()) {
              // Field (regular)
              if (field.getValue().has("@value")) {
                JsonNode valueNode = field.getValue().get("@value");
                JsonNode fieldSchema = schemaSummary.get(field.getKey() + FIELD_SUFFIX);
                CedarIndexFieldValue fv = null;
                // Free text value
                if (!field.getValue().has("_valueLabel")) {
                  fv = valueToIndexValue(valueNode, fieldSchema);
                }
                // Controlled term
                else {
                  JsonNode valueLabelNode = field.getValue().get("_valueLabel");
                  CedarIndexFieldSchema fs = JsonMapper.MAPPER.treeToValue(fieldSchema, CedarIndexFieldSchema.class);
                  fv = fs.toFieldValue();
                  // Controlled term URI
                  fv.setFieldValueSemanticType(valueNode.asText());
                  // Controlled term preferred name
                  fv.setFieldValue_string(valueLabelNode.asText());
                  fv.generateFieldValueAndSemanticType();
                }
                String outputFieldKey = field.getKey() + FIELD_SUFFIX;
                ((ObjectNode) results).set(outputFieldKey, JsonMapper.MAPPER.valueToTree(fv));
                // Element
              } else {
                ((ObjectNode) results).set(field.getKey(), JsonNodeFactory.instance.objectNode());
                extractValuesSummary(nodeType, schemaSummary.get(field.getKey()), field.getValue(), results.get(field
                    .getKey()));
              }
            }
            // it is an Array (Multi-instance value)
            else if (field.getValue().isArray()) {
              ((ObjectNode) results).set(field.getKey(), JsonNodeFactory.instance.arrayNode());
              for (int i = 0; i < field.getValue().size(); i++) {
                JsonNode arrayItem = field.getValue().get(i);
                // If the array items contain @value fields with values (not objects)
                if (arrayItem.has("@value") && (arrayItem.get("@value").isValueNode())) {
                  JsonNode fieldSchema = schemaSummary.get(field.getKey() + FIELD_SUFFIX);
                  CedarIndexFieldValue fv = valueToIndexValue(arrayItem.get("@value"), fieldSchema);
                  ((ArrayNode) results.get(field.getKey())).add((ObjectNode) JsonMapper.MAPPER.valueToTree(fv));
                } else {
                  ((ArrayNode) results.get(field.getKey())).add(JsonNodeFactory.instance.objectNode());
                  extractValuesSummary(nodeType, schemaSummary.get(field.getKey()), arrayItem, results.get(field
                      .getKey()).get(i));
                }
              }
            }
          }
        }
      }
    }
    return results;
  }

  private CedarIndexFieldValue valueToIndexValue(JsonNode valueNode, JsonNode fieldSchema) throws
      JsonProcessingException {
    CedarIndexFieldSchema fs = JsonMapper.MAPPER.treeToValue(fieldSchema, CedarIndexFieldSchema.class);
    CedarIndexFieldValue fv = fs.toFieldValue();
    if (!valueNode.isNull()) {
      // Set appropriate value field according to the value type
      if (fs.getFieldValueType().equals(ESType.STRING)) {
        // Avoid indexing the empty string
        if (valueNode.asText().trim().length() > 0) {
          fv.setFieldValue_string(valueNode.asText());
        }
      }
      // TODO: add all remaining field types
      else {
        // Avoid indexing the empty string
        if (valueNode.asText().trim().length() > 0) {
          fv.setFieldValue_string(valueNode.asText());
        }
      }
    } else {
      // Do nothing. Null values will not be indexed
    }
    return fv;
  }


}