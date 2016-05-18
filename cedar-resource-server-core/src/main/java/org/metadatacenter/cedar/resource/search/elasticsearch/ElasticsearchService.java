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
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

  public void createIndex(String indexName, String documentType, XContentBuilder mapping) throws IOException {
    Client client = null;
    try {
      client = getClient();
      CreateIndexRequestBuilder createIndexRequestBuilder = client.admin().indices().prepareCreate(indexName);
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
    createIndex(indexName, null, null);
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
          .setTypes(documentType).setQuery(QueryBuilders.matchQuery("info.@id", resourceId))
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

  public SearchResponse search(String query, List<String> resourceTypes, String indexName, String documentType) throws UnknownHostException {
    Client client = null;
    try {
      client = getClient();
      SearchRequestBuilder searchRequest = client.prepareSearch(indexName).setTypes(documentType).setSize(esSize);
      if (query != null && query.length() > 0) {
        searchRequest.setQuery(
            QueryBuilders.queryStringQuery(query).field("info.name").field("info.description"));
      }
      // Retrieve all
      else {
        searchRequest.setQuery(QueryBuilders.matchAllQuery());
      }
      if (resourceTypes != null && resourceTypes.size() > 0) {
        // Filter by resource type
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        for (String rt : resourceTypes) {
          boolQueryBuilder.should(QueryBuilders.termQuery("info.resourceType", rt));
        }
        searchRequest.setPostFilter(boolQueryBuilder);
      }
      //System.out.println("Search query in Query DSL: " + searchRequest.internalBuilder());
      SearchResponse response = searchRequest.execute().actionGet();
      return response;
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
  public List<String> findAllValuesForField(String fieldName, String indexName, String documentType) throws UnknownHostException {
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
          for (int i = 0; i < pathFragments.length-1; i++) {
            f = (Map<String, Object>)f.get(pathFragments[0]);
          }
          String fieldValue = (String)f.get(pathFragments[pathFragments.length-1]);
          fieldValues.add(fieldValue);
        }
        response = client.prepareSearchScroll(response.getScrollId()).setScroll(new TimeValue(scrollKeepAlive)).execute().actionGet();
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

  /*** Private methods ***/

  private Client getClient() throws UnknownHostException {
    return TransportClient.builder().settings(settings).build().addTransportAddress(new
        InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));
  }

}
