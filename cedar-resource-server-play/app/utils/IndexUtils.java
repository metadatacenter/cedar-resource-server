package utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import play.core.j.JavaResultExtractor;
import play.mvc.Result;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class IndexUtils {

  public static void indexResource(CedarRSNode resource, JsonNode resourceContent) throws Exception {
    List<String> fieldNames = new ArrayList<>();
    List<String> fieldValues = new ArrayList<>();
    if (resourceContent !=null) {
      fieldNames = extractFieldNames(resource.getType(), resourceContent, new ArrayList<>());
      fieldValues = extractFieldValues(resource.getType(), resourceContent, new ArrayList<>());
    }
    play.Logger.info("Indexing resource (id = " + resource.getId());
    CedarIndexResource ir = new CedarIndexResource(resource, fieldNames, fieldValues);
    DataServices.getInstance().getSearchService().addToIndex(ir);
  }

  public static void unindexResource(String resourceId) throws Exception {
    play.Logger.info("Removing resource from index (id = " + resourceId);
    DataServices.getInstance().getSearchService().removeFromIndex(resourceId);
  }

  public static void updateIndexedResource(CedarRSNode newResource, JsonNode resourceContent) throws Exception {
    List<String> fieldNames = new ArrayList<>();
    List<String> fieldValues = new ArrayList<>();
    if (resourceContent !=null) {
      fieldNames = extractFieldNames(newResource.getType(), resourceContent, new ArrayList<>());
      fieldValues = extractFieldValues(newResource.getType(), resourceContent, new ArrayList<>());
    }
    play.Logger.info("Updating resource (id = " + newResource.getId());
    DataServices.getInstance().getSearchService().removeFromIndex(newResource.getId());
    DataServices.getInstance().getSearchService().addToIndex(new CedarIndexResource(newResource, fieldNames, fieldValues));
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
