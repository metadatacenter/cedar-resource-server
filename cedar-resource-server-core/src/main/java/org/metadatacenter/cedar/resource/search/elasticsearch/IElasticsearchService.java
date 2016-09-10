package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.SearchHit;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;

public interface IElasticsearchService {

  void createIndex(String indexName, String documentType, XContentBuilder settings, XContentBuilder mapping) throws IOException;
  void createIndex(String indexName) throws IOException;
  void addToIndex(JsonNode json, String indexName, String documentType) throws IOException;
  void removeFromIndex(String id, String indexName, String documentType) throws IOException;
  SearchResponse search(String query, List<String> resourceTypes, List<String> sortList, String templateId,
                        String indexName, String documentType, int limit, int offset, String userId) throws UnknownHostException;
  List<SearchHit> searchDeep(String query, List<String> resourceTypes, List<String> sortList,
                        String indexName, String documentType, int limit, String userId) throws UnknownHostException;
  boolean indexExists(String indexName) throws UnknownHostException;
  void deleteIndex(String indexName) throws IOException;
  void addAlias(String indexName, String aliasName) throws IOException;
  void deleteAlias(String indexName, String aliasName) throws IOException;
  List<String> getIndexesByAlias(String aliasName) throws UnknownHostException;
  List<String> findAllValuesForField(String fieldName, String indexName, String documentType) throws UnknownHostException;

}
