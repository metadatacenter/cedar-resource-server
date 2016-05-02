package utils;

import org.metadatacenter.model.index.CedarIndexResource;
import org.metadatacenter.model.resourceserver.CedarRSNode;

public class IndexUtils {

  public static void indexResource(CedarRSNode resource) throws Exception {
    play.Logger.info("Indexing resource (id = " + resource.getId());
    CedarIndexResource ir = new CedarIndexResource(resource);
    DataServices.getInstance().getSearchService().addToIndex(ir);
  }

  public static void unindexResource(String resourceId) throws Exception {
    play.Logger.info("Removing resource from index (id = " + resourceId);
    DataServices.getInstance().getSearchService().removeFromIndex(resourceId);
  }

  public static void updateIndexedResource(CedarRSNode newResource) throws Exception {
    play.Logger.info("Updating resource (id = " + newResource.getId());
    DataServices.getInstance().getSearchService().removeFromIndex(newResource.getId());
    DataServices.getInstance().getSearchService().addToIndex(new CedarIndexResource(newResource));
  }

}
