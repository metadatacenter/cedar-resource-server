package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.action.search.SearchResponse;

import java.util.List;

public interface IElasticsearchService {

  void addToIndex(JsonNode json) throws Exception;
  void removeFromIndex(String id) throws Exception;
  SearchResponse search(String query, List<String> resourceTypes) throws Exception;

}
