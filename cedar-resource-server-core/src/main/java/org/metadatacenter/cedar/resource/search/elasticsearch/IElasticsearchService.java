package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.action.search.SearchResponse;

import java.net.UnknownHostException;
import java.util.List;

public interface IElasticsearchService {

  void addToIndex(JsonNode json) throws UnknownHostException;
  void removeFromIndex(String id);
  SearchResponse search(String query, List<String> resourceTypes) throws UnknownHostException;

}
