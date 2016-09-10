package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_PREFIX;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_ID_FIELD;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_NAME_FIELD;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_DESCRIPTION_FIELD;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_RESOURCETYPE_FIELD;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_RESOURCE_SORTABLE_NAME_FIELD;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_SORT_DESC_PREFIX;
import static org.metadatacenter.constant.ElasticsearchConstants.ES_TEMPLATEID_FIELD;

public class ElasticsearchService implements IElasticsearchService {

  private Settings settings;
  private String esCluster;
  private String esHost;
  private int esTransportPort;
  private int esSize;
  private int scrollKeepAlive;

  public ElasticsearchService(String esCluster, String esHost, int esTransportPort, int esSize, int scrollKeepAlive) {
    this.esCluster = esCluster;
    this.esHost = esHost;
    this.esTransportPort = esTransportPort;
    this.esSize = esSize;
    this.scrollKeepAlive = scrollKeepAlive;

    settings = Settings.settingsBuilder()
        .put("cluster.name", esCluster).build();
  }

  public void createIndex(String indexName, String documentType, XContentBuilder settings, XContentBuilder mapping)
      throws IOException {
    Client client = null;
    try {
      client = getClient();
      CreateIndexRequestBuilder createIndexRequestBuilder = client.admin().indices().prepareCreate(indexName);
      // Set settings
      if (settings != null) {
        createIndexRequestBuilder.setSettings(settings);
      }
      // Put mapping
      if (mapping != null) {
        createIndexRequestBuilder.addMapping(documentType, mapping);
      }
      // Create index
      CreateIndexResponse response = createIndexRequestBuilder.execute().actionGet();
      if (!response.isAcknowledged()) {
        throw new IOException("Failed to create the index " + indexName);
      }
      System.out.println("The index " + indexName + " has been created");
    } finally {
      client.close();
    }
  }

  public void createIndex(String indexName) throws IOException {
    createIndex(indexName, null, null, null);
  }

  public void addToIndex(JsonNode json, String indexName, String documentType) throws IOException {
    Client client = null;
    try {
      client = getClient();
      IndexResponse response = client.prepareIndex(indexName, documentType).setSource(json.toString()).get();
      if (!response.isCreated()) {
        throw new IOException("Failed to index resource");
      }
      System.out.println("The resource has been indexed");
    } finally {
      // Close client
      client.close();
    }
  }

  public void removeFromIndex(String resourceId, String indexName, String documentType) throws IOException {
    Client client = null;
    System.out.println("Removing resource @id=" + resourceId + "from the index");
    try {
      client = getClient();

      // Get resources by resource id
      SearchResponse responseSearch = client.prepareSearch(indexName)
          .setTypes(documentType).setQuery(QueryBuilders.matchQuery(ES_RESOURCE_PREFIX + ES_RESOURCE_ID_FIELD,
              resourceId))
          .execute().actionGet();

      // Delete by Elasticsearch id
      for (SearchHit hit : responseSearch.getHits()) {
        DeleteResponse responseDelete = client.prepareDelete(indexName, documentType, hit.id())
            .execute()
            .actionGet();
        if (!responseDelete.isFound()) {
          throw new IOException("Failed to remove resource " + resourceId + " from the index");
        }
        System.out.println("The resource " + resourceId + " has been removed from the index");
      }
    } finally {
      // Close client
      client.close();
    }
  }

  public SearchResponse search(String query, List<String> resourceTypes, List<String> sortList, String templateId,
                               String indexName, String documentType, int limit, int offset, String userId) throws
      UnknownHostException {
    Client client = null;
    try {
      client = getClient();
      SearchRequestBuilder searchRequest = client.prepareSearch(indexName).setTypes(documentType);

      // See: https://www.elastic.co/blog/better-query-execution-coming-elasticsearch-2-0
      BoolQueryBuilder mainQuery = QueryBuilders.boolQuery();

      // Search query
      if (query != null && query.length() > 0) {
        mainQuery.must(QueryBuilders.queryStringQuery(query).field(ES_RESOURCE_PREFIX + ES_RESOURCE_NAME_FIELD));
      }
      else {
        mainQuery.must(QueryBuilders.matchAllQuery());
      }

      // Filter by resource type
      BoolQueryBuilder resourceTypesQuery = QueryBuilders.boolQuery();
      if (resourceTypes != null && resourceTypes.size() > 0) {
        for (String rt : resourceTypes) {
          resourceTypesQuery.should(QueryBuilders.termQuery(ES_RESOURCE_PREFIX + ES_RESOURCE_RESOURCETYPE_FIELD, rt));
        }
      }
      mainQuery.must(resourceTypesQuery);

      // Filter by template id
      if (templateId != null) {
        mainQuery.must(QueryBuilders.matchQuery(ES_TEMPLATEID_FIELD, templateId));
      }

      // Set main query
      searchRequest.setQuery(mainQuery);

      // Sort by field
      if (sortList != null && sortList.size() > 0) {
        for (String s : sortList) {
          SortOrder sortOrder = SortOrder.ASC;
          if (s.startsWith(ES_SORT_DESC_PREFIX)) {
            sortOrder = SortOrder.DESC;
            s = s.substring(1);
          }
          String sortField = ES_RESOURCE_PREFIX + (s.compareTo(ES_RESOURCE_NAME_FIELD) == 0 ?
              ES_RESOURCE_SORTABLE_NAME_FIELD : s);
          searchRequest.addSort(sortField, sortOrder);
        }
      }
      // Set offset (from) and limit (size)
      searchRequest.setFrom(offset);
      searchRequest.setSize(limit);

      //System.out.println("Search query in Query DSL: " + searchRequest.internalBuilder());

      // Execute request
      SearchResponse response = searchRequest.execute().actionGet();
      return response;
    } finally {
      // Close client
      client.close();
    }
  }

  // It uses the scroll API. It retrieves all results. No pagination and therefore no offset. Scrolling is not
  // intended for real time user requests, but rather for processing large amounts of data.
  // More info: https://www.elastic.co/guide/en/elasticsearch/reference/2.3/search-request-scroll.html
  public List<SearchHit> searchDeep(String query, List<String> resourceTypes, List<String> sortList,
                                    String indexName, String documentType, int limit, String userId) throws
      UnknownHostException {
    Client client = null;
    try {
      client = getClient();
      SearchRequestBuilder searchRequest = client.prepareSearch(indexName).setTypes(documentType);
      if (query != null && query.length() > 0) {
        searchRequest.setQuery(
            QueryBuilders.queryStringQuery(query)
                .field(ES_RESOURCE_PREFIX + ES_RESOURCE_NAME_FIELD)
                .field(ES_RESOURCE_PREFIX + ES_RESOURCE_DESCRIPTION_FIELD));
      }
      // Retrieve all
      else {
        searchRequest.setQuery(QueryBuilders.matchAllQuery());
      }

      // Filter by resource type
      BoolQueryBuilder boolQueryBuilder2 = QueryBuilders.boolQuery();
      if (resourceTypes != null && resourceTypes.size() > 0) {
        for (String rt : resourceTypes) {
          boolQueryBuilder2.should(QueryBuilders.termQuery(ES_RESOURCE_PREFIX + ES_RESOURCE_RESOURCETYPE_FIELD, rt));
        }
      }

      // Combine previous two filters using a bool query builder
      BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
      boolQueryBuilder.must(boolQueryBuilder2);
      searchRequest.setPostFilter(boolQueryBuilder);

      // Sort by field
      if (sortList != null && sortList.size() > 0) {
        for (String s : sortList) {
          SortOrder sortOrder = SortOrder.ASC;
          if (s.startsWith(ES_SORT_DESC_PREFIX)) {
            sortOrder = SortOrder.DESC;
            s = s.substring(1);
          }
          String sortField = ES_RESOURCE_PREFIX + (s.compareTo(ES_RESOURCE_NAME_FIELD) == 0 ?
              ES_RESOURCE_SORTABLE_NAME_FIELD : s);
          searchRequest.addSort(sortField, sortOrder);
        }
      }

      // Set scroll and scroll size
      searchRequest.setScroll(TimeValue.timeValueMinutes(2));
      searchRequest.setSize(50000);

      //System.out.println("Search query in Query DSL: " + searchRequest.internalBuilder());

      // Execute request
      SearchResponse response = searchRequest.execute().actionGet();

      List<SearchHit> allHits = new ArrayList<>();

      while (response.getHits().hits().length != 0 || response.getHits().hits().length >= limit) {
        allHits.addAll(Arrays.asList(response.getHits().hits()));
        //next scroll
        response = client.prepareSearchScroll(response.getScrollId()).setScroll(TimeValue.timeValueMinutes(2))
            .execute().actionGet();
      }

      return allHits;

    } finally {
      // Close client
      client.close();
    }
  }

  public boolean indexExists(String indexName) throws UnknownHostException {
    Client client = null;
    try {
      client = getClient();
      boolean exists = client.admin().indices().prepareExists(indexName)
          .execute().actionGet().isExists();
      return exists;
    } finally {
      // Close client
      client.close();
    }
  }

  public void deleteIndex(String indexName) throws IOException {
    Client client = null;
    try {
      client = getClient();
      DeleteIndexResponse deleteIndexResponse =
          client.admin().indices().delete(new DeleteIndexRequest(indexName)).actionGet();
      if (!deleteIndexResponse.isAcknowledged()) {
        throw new IOException("Failed to delete index '" + indexName + "'");
      }
      System.out.println("The index '" + indexName + "' has been deleted");
    } finally {
      // Close client
      client.close();
    }
  }

  public void addAlias(String indexName, String aliasName) throws IOException {
    Client client = null;
    try {
      client = getClient();
      IndicesAliasesResponse response = client.admin().indices().prepareAliases()
          .addAlias(indexName, aliasName)
          .execute().actionGet();
      if (!response.isAcknowledged()) {
        throw new IOException("Failed to add alias '" + aliasName + "' to index '" + indexName + "'");
      }
      System.out.println("The alias '" + aliasName + "' has been added to index '" + indexName + "'");
    } finally {
      client.close();
    }
  }

  public void deleteAlias(String indexName, String aliasName) throws IOException {
    Client client = null;
    try {
      IndicesAliasesResponse response = getClient().admin().indices().prepareAliases()
          .removeAlias(indexName, aliasName)
          .execute().actionGet();
      if (!response.isAcknowledged()) {
        throw new IOException("Failed to remove alias '" + aliasName + "' from index '" + indexName + "'");
      }
      System.out.println("The alias '" + aliasName + "' has been removed from the index '" + indexName + "'");
    } finally {
      client.close();
    }
  }

  public List<String> getIndexesByAlias(String aliasName) throws UnknownHostException {
    Client client = null;
    List<String> indexNames = new ArrayList<>();
    try {
      client = getClient();
      AliasOrIndex alias = client.admin().cluster()
          .prepareState().execute()
          .actionGet().getState()
          .getMetaData().getAliasAndIndexLookup().get(aliasName);
      for (IndexMetaData indexInfo : alias.getIndices()) {
        indexNames.add(indexInfo.getIndex());
      }
    } finally {
      client.close();
    }
    return indexNames;
  }

  // Retrieve all values for a fieldName. Dot notation is allowed (e.g. info.@id)
  public List<String> findAllValuesForField(String fieldName, String indexName, String documentType) throws
      UnknownHostException {
    Client client = null;
    List<String> fieldValues = new ArrayList<>();
    try {
      client = getClient();
      QueryBuilder qb = QueryBuilders.matchAllQuery();
      SearchRequestBuilder searchRequest = client.prepareSearch(indexName).setTypes(documentType)
          .setFetchSource(new String[]{fieldName}, null)
          .setScroll(new TimeValue(scrollKeepAlive)).setQuery(qb).setSize(esSize);
      //System.out.println("Search query in Query DSL: " + searchRequest.internalBuilder());
      SearchResponse response = searchRequest.execute().actionGet();
      // Scroll until no hits are returned
      while (true) {
        for (SearchHit hit : response.getHits().getHits()) {
          Map<String, Object> f = hit.getSource();
          String[] pathFragments = fieldName.split("\\.");
          for (int i = 0; i < pathFragments.length - 1; i++) {
            f = (Map<String, Object>) f.get(pathFragments[0]);
          }
          String fieldValue = (String) f.get(pathFragments[pathFragments.length - 1]);
          fieldValues.add(fieldValue);
        }
        response = client.prepareSearchScroll(response.getScrollId()).setScroll(new TimeValue(scrollKeepAlive))
            .execute().actionGet();
        // Break condition: No hits are returned
        if (response.getHits().getHits().length == 0) {
          break;
        }
      }
      return fieldValues;
    } finally {
      // Close client
      client.close();
    }
  }

  /***
   * Private methods
   ***/

  private Client getClient() throws UnknownHostException {
    return TransportClient.builder().settings(settings).build().addTransportAddress(new
        InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));
  }

}
