package org.metadatacenter.cedar.resource.search.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.metadatacenter.util.config.PropertiesManager;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class ElasticsearchService implements IElasticsearchService {

  private Settings settings;
  private String esCluster;
  private String esHost;
  private String esIndex;
  private String esType;
  private int esTransportPort;

  public ElasticsearchService() {
    esCluster = PropertiesManager.getProperty("es.cluster").get();
    esHost = PropertiesManager.getProperty("es.host").get();
    esIndex = PropertiesManager.getProperty("es.index").get();
    esType = PropertiesManager.getProperty("es.type").get();
    esTransportPort = PropertiesManager.getPropertyInt("es.transport-port").get();

    settings = Settings.settingsBuilder()
        .put("cluster.name", esCluster).build();
  }

  public void addToIndex(JsonNode json) throws UnknownHostException {
    Client client = null;
    try {
      client = TransportClient.builder().settings(settings).build().addTransportAddress(new
          InetSocketTransportAddress(InetAddress.getByName(esHost), esTransportPort));
      IndexResponse response = client.prepareIndex(esIndex, esType).setSource(json.asText()).get();
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

  public JsonNode search(String query) {
    return null;
  }
}
