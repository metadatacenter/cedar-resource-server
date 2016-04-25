package org.metadatacenter.cedar.resource.search.elasticsearch.util;

import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;

public class ElasticsearchUtil {

  /**
   * Index a specific json content in Elastic Search
   */
  public static void indexJson(Client client, String indexName, String typeName, String json) {
    IndexResponse response = client.prepareIndex(indexName, typeName)
        .setSource(json)
        .get();
    System.out.println(response.toString());
  }

}
