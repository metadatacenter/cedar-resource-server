package utils;

import org.metadatacenter.model.CedarNodeType;
import play.libs.F;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    List<String> sortList = null;
    if (sort.isDefined() && !sort.get().isEmpty()) {
      sortList = Arrays.asList(sort.get().split("\\s*,\\s*"));
      for (String s : sortList) {
        if (!knownSortKeys.contains(s) && !knownSortKeys.contains("-" + s)) {
          throw new IllegalArgumentException("Illegal sort type: '" + s + "'. The allowed values are:" + knownSortKeys);
        }
      }
    } else {
      sortList.add(DEFAULT_SORT);
    }
    return sortList;
  }

}
