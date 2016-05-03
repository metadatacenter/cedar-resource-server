package utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.resourceserver.CedarRSNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class IndexUtils {

  public static void indexResource(CedarRSNode resource, JsonNode resourceContent) throws Exception {
    if (resourceContent !=null) {
      List<String> fieldNames = extractFieldNames(resourceContent);
    }
    play.Logger.info("Indexing resource (id = " + resource.getId());
    CedarIndexResource ir = new CedarIndexResource(resource);
    DataServices.getInstance().getSearchService().addToIndex(ir);
  }

  public static void unindexResource(String resourceId) throws Exception {
    play.Logger.info("Removing resource from index (id = " + resourceId);
    DataServices.getInstance().getSearchService().removeFromIndex(resourceId);
  }

  public static void updateIndexedResource(CedarRSNode newResource) throws Exception {
    play.Logger.info("Updating resource (id = " + newResource.getId());
    DataServices.getInstance().getSearchService().removeFromIndex(newResource.getId());
    DataServices.getInstance().getSearchService().addToIndex(new CedarIndexResource(newResource));
  }

  public static List<String> extractFieldNames(JsonNode resourceContent) {
    List<String> fieldNames = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> fieldsIterator = resourceContent.fields();
      while (fieldsIterator.hasNext()) {
        Map.Entry<String, JsonNode> field = fieldsIterator.next();

        if (field.getValue().isContainerNode()) {

          if (field.getValue().get("@type") != null
              && field.getValue().get("@type").asText().compareTo("https://schema.metadatacenter.org/core/TemplateField")==0
              && field.getValue().get("_ui") != null
              && field.getValue().get("_ui").get("title") != null) {

            String fieldName = field.getValue().get("_ui").get("title").asText();
            fieldNames.add(fieldName);
            System.out.println(">>>>> Field name: " + fieldName);
          }
          else {
            extractFieldNames(field.getValue());
          }
        }
    }
    return fieldNames;
  }
}
