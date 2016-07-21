package utils;

import org.metadatacenter.model.CedarNodeType;
import play.libs.F;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.metadatacenter.constant.ElasticsearchConstants.ES_SORT_DESC_PREFIX;

public class ParametersValidator {

  final static List<String> knownSortKeys;
  public static final String DEFAULT_SORT;

  static {
    DEFAULT_SORT = "";
    knownSortKeys = new ArrayList<>();
    knownSortKeys.add("name");
    knownSortKeys.add("createdOnTS");
    knownSortKeys.add("lastUpdatedOnTS");
  }

  public static String validateQuery(F.Option<String> query) {
    String queryString = null;
    if (query.isDefined()) {
      if (query.get() != null && !query.get().trim().isEmpty()) {
        queryString = query.get().trim();
      } else {
        throw new IllegalArgumentException("You must pass a search query");
      }
    }
    return queryString;
  }

  public static List<String> validateResourceTypes(F.Option<String> resourceTypes) {
    List<String> resourceTypeStringList = null;
    if (resourceTypes.isDefined()) {
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

  public static List<String> validateSort(F.Option<String> sort) {
    List<String> sortList = new ArrayList<>();
    if (sort.isDefined() && !sort.get().isEmpty()) {
      sortList = Arrays.asList(sort.get().split("\\s*,\\s*"));
      for (String s : sortList) {
        String tmp = s.startsWith(ES_SORT_DESC_PREFIX)? s.substring(1) : s;
        if (!knownSortKeys.contains(tmp)) {
          throw new IllegalArgumentException("Illegal sort type: '" + s + "'. The allowed values are:" + knownSortKeys);
        }
      }
    } else {
      sortList.add(DEFAULT_SORT);
    }
    return sortList;
  }

  public static int validateLimit(F.Option<Integer> limit, int defaultLimit, int maxAllowedLimit) {
    if (limit.isDefined()) {
      if (limit.get() <= 0) {
        throw new IllegalArgumentException("You should specify a positive limit!");
      } else if (limit.get() > maxAllowedLimit) {
        throw new IllegalArgumentException("You should specify a limit smaller than " + maxAllowedLimit + "!");
      }
      return limit.get();
    }
    else {
      return defaultLimit;
    }
  }

  public static int validateOffset(F.Option<Integer> offset) {
    int defaultOffset = 0;
    if (offset.isDefined()) {
      if (offset.get() < 0) {
        throw new IllegalArgumentException("You should specify a positive or zero offset!");
      }
      return offset.get();
    }
    else {
      return defaultOffset;
    }
  }

}
