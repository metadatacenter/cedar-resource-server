package org.metadatacenter.server.service.mongodb;

import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.metadatacenter.model.CedarResource;
import org.metadatacenter.server.IResourceService;
import org.metadatacenter.server.dao.mongodb.ResourceDaoMongoDB;

import java.io.IOException;

public class ResourceServiceMongoDB implements IResourceService {

  @NonNull
  private final ResourceDaoMongoDB resourceDao;

  public ResourceServiceMongoDB(@NonNull String db, @NonNull String resourcesCollection) {
    this.resourceDao = new ResourceDaoMongoDB(db, resourcesCollection);
  }

  @Override
  public CedarResource createResource(@NonNull CedarResource resource) throws IOException, ProcessingException {
    return resourceDao.create(resource);
  }

  @Override
  public CedarResource findResource(@NonNull String resourceId) throws IOException, ProcessingException {
    return resourceDao.find(resourceId);
  }

  @Override
  public CedarResource findFolder(@NonNull String path) {
    return null;
  }

}
