package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.UnknownHostException;

public interface IElasticsearchService {

  void addToIndex(JsonNode json) throws UnknownHostException;
  void removeFromIndex(String id);
  JsonNode search(String query);

}
