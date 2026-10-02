package org.metadatacenter.server.search.elasticsearch.service;

import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.id.CedarFilesystemResourceId;
import org.metadatacenter.model.folderserver.basic.FileSystemResource;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.search.IndexedDocumentId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A NodeIndexingService that indexes nothing, for integration tests that run without OpenSearch.
 * Lives in the service's package because the parent constructor is package-private. Only the
 * operations the filesystem resources invoke are overridden; anything else would reach the null
 * client and fail loudly, which is the right behavior for an unexpectedly exercised path.
 */
public class NoOpNodeIndexingService extends NodeIndexingService {

  private final Map<String, FileSystemResource> lastIndexed = new ConcurrentHashMap<>();

  public NoOpNodeIndexingService(CedarConfig cedarConfig) {
    super(cedarConfig, "no-op-index", null);
  }

  @Override
  public IndexedDocumentId indexDocument(FileSystemResource resource, CedarRequestContext requestContext) {
    lastIndexed.put(resource.getId(), resource);
    return null;
  }

  @Override
  public IndexedDocumentId indexDocumentForProjection(FileSystemResource resource, CedarRequestContext context) {
    return indexDocument(resource, context);
  }

  public boolean wasIndexed(String resourceId) {
    return lastIndexed.containsKey(resourceId);
  }

  /** The resource as most recently handed to the index, or null if it never was. */
  public FileSystemResource lastIndexed(String resourceId) {
    return lastIndexed.get(resourceId);
  }

  @Override
  public long removeDocumentFromIndex(CedarFilesystemResourceId resourceId) {
    return 0;
  }

  @Override
  public long removeDocumentFromIndex(CedarFilesystemResourceId resourceId, boolean retry) {
    return 0;
  }

}
