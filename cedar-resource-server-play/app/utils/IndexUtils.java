package utils;

import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.resourceserver.CedarRSResource;

import java.net.UnknownHostException;

public class IndexUtils {

  public static void indexResource(CedarRSResource rs) throws UnknownHostException {
    play.Logger.info("Indexing resource (id = " + rs.getId());
    CedarIndexResource ir = new CedarIndexResource(rs);
    DataServices.getInstance().getSearchService().addToIndex(ir);
  }

  public static void unindexResource(String resourceId) throws UnknownHostException {
    play.Logger.info("Removing resource from index (id = " + resourceId);
    DataServices.getInstance().getSearchService().removeFromIndex(resourceId);
  }

  public static void updateIndexedResource(CedarRSResource newResource) throws UnknownHostException {
    play.Logger.info("Updating resource (id = " + newResource.getId());
    DataServices.getInstance().getSearchService().removeFromIndex(newResource.getId());
    DataServices.getInstance().getSearchService().addToIndex(new CedarIndexResource(newResource));
  }

}
