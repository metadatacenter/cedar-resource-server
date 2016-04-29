package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;

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

  public ElasticsearchService(String esCluster, String esHost, String esIndex, String esType, int esTransportPort) {
    this.esCluster = esCluster;
    this.esHost = esHost;
    this.esIndex = esIndex;
    this.esType = esType;
    this.esTransportPort = esTransportPort;

    settings = Settings.settingsBuilder()
        .put("cluster.name", esCluster).build();
  }

  public void addToIndex(JsonNode json) throws UnknownHostException {
    Client client = null;
    try {
      client = TransportClient.builder().settings(settings).build().addTransportAddress(new
          InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));
      IndexResponse response = client.prepareIndex(esIndex, esType).setSource(json.toString()).get();
      System.out.println(response.toString());
    } catch (UnknownHostException e) {
      throw e;
    } finally {
      // Close client
      client.close();
    }
  }

  public void removeFromIndex(String id) {
  }

  public SearchResponse search(String query, List<String> resourceTypes) throws UnknownHostException {
    Client client = null;

    try {
      client = TransportClient.builder().settings(settings).build().addTransportAddress(new
          InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));

      SearchRequestBuilder searchRequest = client.prepareSearch(esIndex)
          .setTypes(esType);

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
}
