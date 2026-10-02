package org.metadatacenter.server.search.permission;

import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.server.queue.util.PermissionQueueService;
import org.metadatacenter.server.search.SearchPermissionQueueEvent;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.metadatacenter.server.search.elasticsearch.service.NodeSearchingService;
import org.metadatacenter.server.search.util.IndexUtils;

/**
 * The search-permission pipeline with its asynchronous half run on the caller's thread.
 *
 * <p>In production a command appends an event to the durable outbox, a relay moves it to Redis, and
 * the worker projects it into the index. A test has neither Redis nor the worker, so this keeps the
 * real outbox and replaces the queue with the projection itself: {@link #drain()} relays every
 * pending event and returns once each has been written to the index. A projection failure is thrown
 * from {@code drain}, not retried. Lives in this package because the relay and the outbox are
 * package-private.
 */
public final class InlineSearchPermissionRelay implements AutoCloseable {

  private final SearchPermissionEnqueueService enqueueService;
  private final SearchPermissionExecutorService executor;

  /** Projects as the worker does, with the graph read through the given context. */
  public static InlineSearchPermissionRelay projectingAs(CedarConfig cedarConfig, CedarRequestContext context,
                                                         IndexUtils indexUtils, NodeSearchingService searching,
                                                         NodeIndexingService indexing) {
    CedarDataServices services = CedarDataServices.getInstance();
    return new InlineSearchPermissionRelay(cedarConfig, new SearchPermissionExecutorService(indexUtils, searching,
        indexing, services.getFolderServiceSession(context), services.getResourcePermissionServiceSession(context),
        services.getCategoryServiceSession(context), context));
  }

  private InlineSearchPermissionRelay(CedarConfig cedarConfig, SearchPermissionExecutorService executor) {
    this.executor = executor;
    PermissionQueueService projectInline = new PermissionQueueService(cedarConfig.getCacheConfig().getPersistent()) {
      @Override
      public boolean enqueueEvent(SearchPermissionQueueEvent event) {
        try {
          executor.handleEvent(event);
          return true;
        } catch (CedarProcessingException e) {
          throw new IllegalStateException("The search projection of " + event.getId() + " failed", e);
        }
      }
    };
    enqueueService = new SearchPermissionEnqueueService(projectInline, new Neo4jSearchPermissionOutbox(cedarConfig));
  }

  /** The service to inject where the server expects one; it records events and relays nothing itself. */
  public SearchPermissionEnqueueService enqueueService() {
    return enqueueService;
  }

  /** Projects every event recorded so far into the index. */
  public void drain() {
    while (enqueueService.relayPending() == SearchPermissionEnqueueService.RelayResult.MORE) {
      // A full batch was delivered, so more may be waiting behind it.
    }
  }

  @Override
  public void close() throws InterruptedException {
    executor.close();
  }
}
