package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class ElasticsearchService implements IElasticsearchService {

  private Settings settings;
  private String esCluster;
  private String esHost;
  private String esIndex;
  private String esType;
  private int esTransportPort;
  private int esSize;

  public ElasticsearchService(String esCluster, String esHost, String esIndex, String esType, int esTransportPort,
                              int esSize) {
    this.esCluster = esCluster;
    this.esHost = esHost;
    this.esIndex = esIndex;
    this.esType = esType;
    this.esTransportPort = esTransportPort;
    this.esSize = esSize;

    settings = Settings.settingsBuilder()
        .put("cluster.name", esCluster).build();
  }

  public void addToIndex(JsonNode json) throws Exception {
    Client client = null;
    try {
      client = TransportClient.builder().settings(settings).build().addTransportAddress(new
          InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));
      IndexResponse response = client.prepareIndex(esIndex, esType).setSource(json.toString()).get();
      if (!response.isCreated()) {
        throw new Exception("Failed to index resource");
      }
      System.out.println("The resource has been indexed");
    } catch (UnknownHostException e) {
      throw e;
    } finally {
      // Close client
      client.close();
    }
  }

  public void removeFromIndex(String resourceId) throws Exception {
    Client client = null;

    try {
      client = TransportClient.builder().settings(settings).build().addTransportAddress(new
          InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));

      // Get resources by resource id
      SearchResponse responseSearch = client.prepareSearch(esIndex)
          .setTypes(esType).setQuery(QueryBuilders.matchQuery("info.@id", resourceId))
          .execute().actionGet();

      // Delete by Elasticsearch id
      for (SearchHit hit : responseSearch.getHits()) {
        DeleteResponse responseDelete = client.prepareDelete(esIndex, esType, hit.id())
            .execute()
            .actionGet();
        if (!responseDelete.isFound()) {
          throw new Exception("Failed to remove resource from the index");
        }
        System.out.println("The resource has been removed from the index");
      }
    } catch (UnknownHostException e) {
      throw e;
    } finally {
      // Close client
      client.close();
    }
  }

  public SearchResponse search(String query, List<String> resourceTypes) throws Exception {
    // Create search index if it does not exist in order to avoid an Elasticsearch exception. The index will be
    // empty, so no results will be returned
    if (!indexExists(esIndex)) {
      System.out.println("The index '" + esIndex + "' does not exist. Creating it...");
      createIndex(esIndex);
    }

    Client client = null;

    try {
      client = TransportClient.builder().settings(settings).build().addTransportAddress(new
          InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));

      SearchRequestBuilder searchRequest = client.prepareSearch(esIndex)
          .setTypes(esType).setSize(esSize);

      if (query != null && query.length() > 0) {
        searchRequest.setQuery(
            QueryBuilders.boolQuery()
                .should(QueryBuilders.fuzzyQuery("info.name", query))
                .should(QueryBuilders.fuzzyQuery("info.description", query)));
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
      SearchResponse response = searchRequest
          .execute()
          .actionGet();
      return response;
    } catch (UnknownHostException e) {
      throw e;
    } finally {
      // Close client
      client.close();
    }
  }

  private boolean indexExists(String indexName) throws UnknownHostException {
    Client client = null;
    try {
      client = TransportClient.builder().settings(settings).build().addTransportAddress(new
          InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));
      boolean exists = client.admin().indices().prepareExists(indexName)
          .execute().actionGet().isExists();
      return exists;
    } catch (UnknownHostException e) {
      throw e;
    } finally {
      // Close client
      client.close();
    }
  }

  private void createIndex(String indexName) throws Exception {
    Client client = null;
    try {
      client = TransportClient.builder().settings(settings).build().addTransportAddress(new
          InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));

      CreateIndexResponse createIndexResponse =
          client.admin().indices().create(new CreateIndexRequest(indexName)).actionGet();

      if (!createIndexResponse.isAcknowledged()) {
        throw new Exception("Failed to create index '" + esIndex + "'");
      }
      System.out.println("The index '" + esIndex + "' has been created");

    } catch (UnknownHostException e) {
      throw e;
    } finally {
      // Close client
      client.close();
    }
  }

}
