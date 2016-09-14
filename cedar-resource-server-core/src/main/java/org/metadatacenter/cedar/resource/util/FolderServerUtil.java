package org.metadatacenter.cedar.resource.util;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.server.security.model.IAuthRequest;
import org.metadatacenter.util.json.JsonMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class FolderServerUtil {

  public static Map<String, String> getAccessibleNodeIds(IAuthRequest authRequest) {
    String folderBase = CedarConfig.getInstance().getServers().getFolder().getBase();
    String url = folderBase + "/" + "accessible-node-ids";
    Map<String, String> accessibleNodeIds = null;
    try {
      HttpResponse proxyResponse = ProxyUtil.proxyGet(url, authRequest);
      int statusCode = proxyResponse.getStatusLine().getStatusCode();
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null) {
        if (HttpStatus.SC_OK == statusCode) {
          String responseString = EntityUtils.toString(proxyResponse.getEntity());
          Map<String, Map<String, String>> accessibleNodeIdsObject =
              JsonMapper.MAPPER.readValue(responseString, new TypeReference<Map<String, HashMap<String, String>>>() {});
          accessibleNodeIds = accessibleNodeIdsObject.get("accessibleNodes");
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return accessibleNodeIds;
  }
}
