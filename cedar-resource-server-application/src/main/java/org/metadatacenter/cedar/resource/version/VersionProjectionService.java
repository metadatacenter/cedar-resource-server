package org.metadatacenter.cedar.resource.version;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.neo4j.Neo4jConfig;
import org.metadatacenter.server.neo4j.VersionChainTransaction;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.http.ArtifactServiceClient;
import org.metadatacenter.util.json.JsonMapper;
import org.neo4j.driver.*;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

/** Retries the document-link and search projections recorded by each graph lifecycle transaction. */
public final class VersionProjectionService implements AutoCloseable {
  private final CedarConfig config;
  private final UserService users;
  private final NodeIndexingService index;
  private final Driver driver;
  private ScheduledExecutorService executor;
  private final LinkWriter links;

  @FunctionalInterface
  interface LinkWriter { void write(FolderServerArtifact artifact, String previous) throws Exception; }

  VersionProjectionService(Driver driver, NodeIndexingService index, LinkWriter links) {
    this.config=null; this.users=null; this.driver=driver; this.index=index; this.links=links;
    VersionChainTransaction.initialize(driver);
  }


  public VersionProjectionService(CedarConfig config, UserService users, NodeIndexingService index) {
    this.config=config; this.users=users; this.index=index; this.links=this::syncPrevious;
    var neo=Neo4jConfig.fromCedarConfig(config);
    var settings=Config.builder();
    org.metadatacenter.config.CedarTestRuntime.dependencyTimeoutMillis().ifPresent(timeout -> settings
        .withConnectionTimeout(timeout,TimeUnit.MILLISECONDS)
        .withConnectionAcquisitionTimeout(timeout,TimeUnit.MILLISECONDS)
        .withMaxTransactionRetryTime(timeout,TimeUnit.MILLISECONDS));
    driver=GraphDatabase.driver(neo.getUri(),AuthTokens.basic(neo.getUserName(),neo.getUserPassword()),settings.build());
    VersionChainTransaction.initialize(driver);
  }

  /** Failed projections remain durable; an HTTP retry must not repeat a committed lifecycle change. */
  public void completePending(NodeIndexingService indexing, CedarRequestContext context) {
    try (var session=driver.session()) {
      var ids=session.run("MATCH (j:CedarVersionProjection) RETURN j.resourceId AS id ORDER BY j.updatedAt, j.resourceId LIMIT 25")
          .list(r -> r.get("id").asString());
      for (String id:ids) {
        // An explicit transaction avoids automatically replaying network writes. Its common graph
        // lock prevents a delayed projection from overwriting a newer lifecycle transition.
        try (var tx=session.beginTransaction()) {
          VersionChainTransaction.lock(tx);
          var pending=tx.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN j.syncPrevious AS sync",Map.of("id",id));
          if (!pending.hasNext()) { tx.commit(); continue; }
          boolean sync=pending.next().get("sync").asBoolean(false);
          var found=tx.run("MATCH (a:Artifact {_id:$id}) RETURN properties(a) AS a",Map.of("id",id));
          if (found.hasNext()) {
            var properties=found.next().get("a").asMap();
            var artifact=JsonMapper.TOLERANT_MAPPER.convertValue(org.metadatacenter.server.neo4j.util.Neo4JUtil.unescapeTopLevelPropertyNames(JsonMapper.STRICT_MAPPER.valueToTree(properties)),FolderServerArtifact.class);
            if (sync) links.write(artifact,(String)properties.get("pav_previousVersion"));
            indexing.indexDocument(artifact,context);
          }
          tx.run("MATCH (j:CedarVersionProjection {resourceId:$id}) DELETE j",Map.of("id",id)).consume();
          tx.commit();
        } catch (Exception e) {
          LoggerFactory.getLogger(getClass()).warn("Version projection for {} remains pending: {}",id,e.toString());
          session.run("MATCH (j:CedarVersionProjection {resourceId:$id}) SET j.updatedAt=timestamp(), "
              + "j.attempts=coalesce(j.attempts,0)+1",Map.of("id",id)).consume();
          break; // An unavailable downstream must not cost one network timeout per queued artifact.
        }
      }
    } catch (Exception e) {
      LoggerFactory.getLogger(getClass()).warn("Version projections remain pending: {}",e.toString());
    }
  }

  private void syncPrevious(FolderServerArtifact artifact,String previous) throws Exception {
    var admin=CedarRequestContextFactory.fromAdminUser(config,users);
    String url=config.getMicroserviceUrlUtil().getArtifact().getArtifactTypeWithId(artifact.getType(),org.metadatacenter.id.CedarArtifactId.build(artifact.getId(),artifact.getType()));
    var client=new ArtifactServiceClient(config);
    ObjectNode body; String etag;
    try (var response=client.get(url,admin)) {
      if (response.getCode()!=200) throw new IllegalStateException("Version link GET answered " + response.getCode());
      etag=response.getFirstHeader("ETag").getValue();
      body=(ObjectNode)JsonMapper.STRICT_MAPPER.readTree(EntityUtils.toString(response.getEntity()));
    }
    if (previous==null ? !body.has("pav:previousVersion") : previous.equals(body.path("pav:previousVersion").asText())) return;
    var patch=JsonMapper.STRICT_MAPPER.createObjectNode();
    if (previous==null) patch.putNull("previousVersion"); else patch.put("previousVersion",previous);
    try (var response=client.put(url+"/version-predecessor",admin,patch.toString(),etag)) {
      EntityUtils.consume(response.getEntity());
      if (response.getCode()!=200) throw new IllegalStateException("Version link PUT answered " + response.getCode());
    }
  }

  public synchronized void start() {
    if (executor!=null) return;
    executor=Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"version-projection-relay"); t.setDaemon(true); return t; });
    executor.scheduleWithFixedDelay(() -> {
      try { completePending(index,CedarRequestContextFactory.fromAdminUser(config,users)); }
      catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Cannot resume version projections",e); }
    },5,5,TimeUnit.SECONDS);
  }

  public synchronized void close() {
    if (executor!=null) executor.shutdownNow();
    driver.close();
  }
}
