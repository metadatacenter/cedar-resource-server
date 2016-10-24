package org.metadatacenter.cedar.resource.util;

import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.util.EntityUtils;
import org.metadatacenter.model.folderserver.FolderServerFolder;
import org.metadatacenter.model.folderserver.FolderServerResource;
import org.metadatacenter.util.json.JsonMapper;
import play.mvc.Http;

import java.io.IOException;

public class FolderServerProxy {

  private FolderServerProxy() {
  }

  public static FolderServerFolder getFolder(String folderBaseFolders, String folderId, Http.Request request) {
    if (folderId != null) {
      try {
        String url = folderBaseFolders + "/" + new URLCodec().encode(folderId);
        System.out.println("FolderServerProxy.getFolder:" + url);
        HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request);
        int statusCode = proxyResponse.getStatusLine().getStatusCode();
        HttpEntity entity = proxyResponse.getEntity();
        if (entity != null) {
          if (HttpStatus.SC_OK == statusCode) {
            FolderServerFolder folder = null;
            String responseString = EntityUtils.toString(proxyResponse.getEntity());
            folder = JsonMapper.MAPPER.readValue(responseString, FolderServerFolder.class);
            return folder;
          }
        }
      } catch (EncoderException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return null;
  }

  public static FolderServerResource getResource(String folderBaseResource, String resourceId, Http.Request request) {
    if (resourceId != null) {
      try {
        String url = folderBaseResource + "/" + new URLCodec().encode(resourceId);
        System.out.println("FolderServerProxy.getResource:" + url);
        HttpResponse proxyResponse = ProxyUtil.proxyGet(url, request);
        int statusCode = proxyResponse.getStatusLine().getStatusCode();
        HttpEntity entity = proxyResponse.getEntity();
        System.out.println(statusCode);
        System.out.println(entity);
        if (entity != null) {
          if (HttpStatus.SC_OK == statusCode) {
            FolderServerResource node = null;
            String responseString = EntityUtils.toString(proxyResponse.getEntity());
            node = JsonMapper.MAPPER.readValue(responseString, FolderServerResource.class);
            return node;
          }
        }
      } catch (EncoderException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return null;
  }
}
