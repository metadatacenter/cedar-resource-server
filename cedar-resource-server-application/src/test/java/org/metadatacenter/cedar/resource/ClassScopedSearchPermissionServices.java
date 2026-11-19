package org.metadatacenter.cedar.resource;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.server.search.permission.SearchPermissionEnqueueService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The search-permission enqueue services a test class injects, closed when the class ends.
 *
 * <p>Each service opens a Neo4j driver for its outbox, and a driver that is never closed keeps its
 * event-loop threads for the rest of the test JVM, which runs many server classes in turn. A class
 * obtains its services from {@link #open}, and this extension, which JUnit loads for every class
 * through {@code META-INF/services}, closes them after the class's own {@code @AfterAll} methods have
 * stopped its server.
 */
public final class ClassScopedSearchPermissionServices implements AfterAllCallback {

  private static final List<SearchPermissionEnqueueService> OPEN = new CopyOnWriteArrayList<>();

  /** A new, unstarted service, closed when the test class that asked for it ends. */
  public static SearchPermissionEnqueueService open(CedarConfig cedarConfig) {
    SearchPermissionEnqueueService service = new SearchPermissionEnqueueService(cedarConfig);
    OPEN.add(service);
    return service;
  }

  @Override
  public void afterAll(ExtensionContext context) {
    for (SearchPermissionEnqueueService service : OPEN) {
      service.close();
    }
    OPEN.clear();
  }
}
