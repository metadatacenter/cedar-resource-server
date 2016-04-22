package controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.cedar.resource.util.ProxyUtil;
import org.metadatacenter.constant.ConfigConstants;
import org.metadatacenter.model.folderserver.CedarFSNode;
import org.metadatacenter.model.resourceserver.CedarRSFolder;
import org.metadatacenter.model.resourceserver.CedarRSNode;
import org.metadatacenter.server.play.AbstractCedarController;
import play.Configuration;
import play.Play;

import java.io.IOException;

public abstract class AbstractResourceServerController extends AbstractCedarController {

  protected static Configuration config;
  protected final static String folderBase;
  protected final static String templateBase;

  static {
    config = Play.application().configuration();
    folderBase = config.getString(ConfigConstants.FOLDER_SERVER_BASE);
    templateBase = config.getString(ConfigConstants.TEMPLATE_SERVER_BASE);
  }

  protected static CedarRSFolder getCedarFolderById(String id) throws IOException, EncoderException {
    String url = folderBase + "folders/" + new URLCodec().encode(id);

    HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request());

    int statusCode = proxyResponse.getStatusLine().getStatusCode();

    HttpEntity entity = proxyResponse.getEntity();
    if (entity != null) {
      if (HttpStatus.SC_OK == statusCode) {
        return (CedarRSFolder)deserializeAndAddProvenanceInfoToResource(proxyResponse);
      }
    }
    return null;
  }

  protected static CedarFSNode deserializeResource(HttpResponse proxyResponse) throws
      IOException {
    ObjectMapper mapper = new ObjectMapper();
    CedarFSNode resource = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      resource = mapper.readValue(responseString, CedarFSNode.class);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    return resource;
  }


  protected static CedarRSNode deserializeAndAddProvenanceInfoToResource(HttpResponse proxyResponse) throws
      IOException {
    ObjectMapper mapper = new ObjectMapper();
    CedarRSNode resource = null;
    try {
      String responseString = EntityUtils.toString(proxyResponse.getEntity());
      resource = mapper.readValue(responseString, CedarRSNode.class);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    // TODO: get real user names here
    if (resource != null) {
      resource.setCreatedByUserName("Foo Bar");
      resource.setLastUpdatedByUserName("Foo Bar");
    }
    return resource;
  }

  protected static JsonNode resourceWithExpandedProvenanceInfo(HttpResponse proxyResponse) throws IOException {
    CedarRSNode foundResource = deserializeAndAddProvenanceInfoToResource(proxyResponse);
    ObjectMapper mapper = new ObjectMapper();
    return mapper.valueToTree(foundResource);
  }

}
