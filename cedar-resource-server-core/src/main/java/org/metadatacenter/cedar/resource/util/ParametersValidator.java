package org.metadatacenter.cedar.resource.util;

import org.metadatacenter.model.CedarNodeType;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.metadatacenter.constant.ElasticsearchConstants.ES_SORT_DESC_PREFIX;

public class ParametersValidator {

  final static List<String> knownSortKeys;
  public static final String DEFAULT_SORT;

  static {
    DEFAULT_SORT = "name";
    knownSortKeys = new ArrayList<>();
    knownSortKeys.add("name");
    knownSortKeys.add("createdOnTS");
    knownSortKeys.add("lastUpdatedOnTS");
  }

  public static String validateQuery(Optional<String> query) {
    String queryString = null;
    if (query.isPresent()) {
      if (query.get() != null && !query.get().trim().isEmpty()) {
        queryString = query.get().trim();
      } else {
        throw new IllegalArgumentException("You must pass a search query");
      }
    }
    return queryString;
  }

  public static List<String> validateResourceTypes(Optional<String> resourceTypes) {
    List<String> resourceTypeStringList = null;
    if (resourceTypes.isPresent()) {
      if (resourceTypes.get() != null && !resourceTypes.get().isEmpty()) {
        String resourceTypesString = resourceTypes.get().trim();
        resourceTypeStringList = Arrays.asList(resourceTypesString.split("\\s*,\\s*"));
        for (String rt : resourceTypeStringList) {
          CedarNodeType crt = CedarNodeType.forValue(rt);
          if (crt == null) {
            throw new IllegalArgumentException("Illegal resource type: '" + rt + "'. The allowed values " +
                "are: " + CedarNodeType.Types.TEMPLATE + ", " + CedarNodeType.Types.ELEMENT + ", " + CedarNodeType
                .Types.FIELD + ", " + CedarNodeType.Types.INSTANCE + ", " + CedarNodeType.Types.FOLDER);
          }
        }
      } else {
        throw new IllegalArgumentException("You must pass in 'resource_types' as a comma separated list!");
      }
    }
    return resourceTypeStringList;
  }

  public static String validateTemplateId(Optional<String> templateId) {
    String id = null;
    if (templateId.isPresent()) {
      if (templateId.get() != null && !templateId.get().isEmpty() && isValidURL(templateId.get())) {
        id = templateId.get();
      } else {
        throw new IllegalArgumentException("You must pass in 'template_id' as a valid template identifier");
      }
    }
    return id;
  }

  public static List<String> validateSort(Optional<String> sort) {
    List<String> sortList = new ArrayList<>();
    if (sort.isPresent() && !sort.get().isEmpty()) {
      sortList = Arrays.asList(sort.get().split("\\s*,\\s*"));
      for (String s : sortList) {
        String tmp = s.startsWith(ES_SORT_DESC_PREFIX) ? s.substring(1) : s;
        if (!knownSortKeys.contains(tmp)) {
          throw new IllegalArgumentException("Illegal sort type: '" + s + "'. The allowed values are:" + knownSortKeys);
        }
      }
    } else {
      sortList.add(DEFAULT_SORT);
    }
    return sortList;
  }

  public static int validateLimit(Optional<Integer> limit, int defaultLimit, int maxAllowedLimit) {
    if (limit.isPresent()) {
      if (limit.get() <= 0) {
        throw new IllegalArgumentException("You should specify a positive limit!");
      } else if (limit.get() > maxAllowedLimit) {
        throw new IllegalArgumentException("You should specify a limit smaller than " + maxAllowedLimit + "!");
      }
      return limit.get();
    } else {
      return defaultLimit;
    }
  }

  public static int validateOffset(Optional<Integer> offset) {
    int defaultOffset = 0;
    if (offset.isPresent()) {
      if (offset.get() < 0) {
        throw new IllegalArgumentException("You should specify a positive or zero offset!");
      }
      return offset.get();
    } else {
      return defaultOffset;
    }
  }

  private static boolean isValidURL(String urlStr) {
    try {
      URL url = new URL(urlStr);
      return true;
    } catch (MalformedURLException e) {
      return false;
    }
  }

}
