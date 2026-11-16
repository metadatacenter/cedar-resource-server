package org.metadatacenter.server.search.permission;

import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.server.neo4j.Neo4jConfig;
import org.metadatacenter.server.queue.util.PermissionQueueService;
import org.metadatacenter.server.search.SearchPermissionQueueEventType;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;

/**
 * The search-permission enqueue service a test injects, together with a reader of the durable outbox
 * it appends to.
 *
 * <p>A command that changes who may find a resource appends an event to the outbox, and the worker
 * later rewrites the index's permission fields from it. A test has no worker, so it checks the
 * command's part: the event the command appended for the resource it changed. The service is never
 * started, and every class that uses it points Redis at a dead port, so no relay removes an event
 * before the test reads it. Lives in this package because the outbox is package-private.
 */
public final class SearchPermissionOutboxReader implements AutoCloseable {

  private static final String QUERY = "MATCH (e:CedarSearchPermissionOutbox {resourceId: $resourceId}) "
      + "RETURN e.eventType AS eventType ORDER BY e.createdAtTS, e.outboxId";

  private final Driver driver;
  private final SearchPermissionEnqueueService enqueueService;

  public SearchPermissionOutboxReader(CedarConfig cedarConfig) {
    Neo4jConfig neo4j = Neo4jConfig.fromCedarConfig(cedarConfig);
    driver = GraphDatabase.driver(neo4j.getUri(), AuthTokens.basic(neo4j.getUserName(), neo4j.getUserPassword()));
    enqueueService = new SearchPermissionEnqueueService(
        new PermissionQueueService(cedarConfig.getCacheConfig().getPersistent()),
        new Neo4jSearchPermissionOutbox(driver));
  }

  /** The service to inject where the server expects one; it appends to the outbox and relays nothing. */
  public SearchPermissionEnqueueService enqueueService() {
    return enqueueService;
  }

  /** The events the outbox holds for one resource, oldest first. */
  public List<SearchPermissionQueueEventType> eventsFor(String resourceId) {
    try (Session session = driver.session()) {
      return session.readTransaction(tx -> tx.run(QUERY, Map.of("resourceId", resourceId)).list(
          record -> SearchPermissionQueueEventType.valueOf(record.get("eventType").asString())));
    }
  }

  /** Closes the service, which closes the outbox and with it the driver this reader shares. */
  @Override
  public void close() {
    enqueueService.close();
  }
}
