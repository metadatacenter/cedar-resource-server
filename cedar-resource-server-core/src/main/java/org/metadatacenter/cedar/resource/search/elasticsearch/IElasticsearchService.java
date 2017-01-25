package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.search.SearchHit;
import org.metadatacenter.exception.CedarProcessingException;

import java.util.List;

public interface IElasticsearchService {

  void createIndex(String indexName, String documentType) throws CedarProcessingException;

  void createIndex(String indexName) throws CedarProcessingException;

  void addToIndex(JsonNode json, String indexName, String documentType) throws CedarProcessingException;

  void removeFromIndex(String id, String indexName, String documentType) throws CedarProcessingException;

  SearchResponse search(String query, List<String> resourceTypes, List<String> sortList, String templateId,
                        String indexName, String documentType, int limit, int offset) throws CedarProcessingException;

  List<SearchHit> searchDeep(String query, List<String> resourceTypes, List<String> sortList, String templateId,
                             String indexName, String documentType, int limit) throws CedarProcessingException;

  boolean indexExists(String indexName) throws CedarProcessingException;

  void deleteIndex(String indexName) throws CedarProcessingException;

  void addAlias(String indexName, String aliasName) throws CedarProcessingException;

  void deleteAlias(String indexName, String aliasName) throws CedarProcessingException;

  List<String> getIndexesByAlias(String aliasName) throws CedarProcessingException;

  List<String> findAllValuesForField(String fieldName, String indexName, String documentType) throws
      CedarProcessingException;

  void closeClient();

}
