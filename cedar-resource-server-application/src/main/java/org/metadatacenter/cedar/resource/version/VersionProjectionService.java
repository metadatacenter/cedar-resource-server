package org.metadatacenter.cedar.resource.version;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.model.CedarResourceType;
import org.metadatacenter.model.folderserver.extract.FolderServerResourceExtract;
import org.metadatacenter.server.search.util.InclusionSubgraphUtil;
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

/**
 * Recovers artifact inclusion, search and recommender work, version links and instance reindexes.
 * Every job is committed with the graph mutation; failures leave it available after restart.
 */
public final class VersionProjectionService implements AutoCloseable {
  private final CedarConfig config;
  private final UserService users;
  private final NodeIndexingService index;
  private final Driver driver;
  private ScheduledExecutorService executor;
  private final LinkWriter links;
  private final ContentWriter contents;

  @FunctionalInterface
  interface ContentWriter { void write(FolderServerArtifact artifact, JsonNode content, CedarRequestContext context) throws Exception; }

  @FunctionalInterface
  interface LinkWriter { void write(FolderServerArtifact artifact, String previous) throws Exception; }

  VersionProjectionService(Driver driver, NodeIndexingService index, LinkWriter links) {
    this(driver, index, links, (artifact, content, context) -> {});
  }

  VersionProjectionService(Driver driver, NodeIndexingService index, LinkWriter links,
                           ContentWriter contents) {
    this.config=null; this.users=null; this.driver=driver; this.index=index; this.links=links;
    this.contents=contents;
    VersionChainTransaction.initialize(driver);
  }


  public VersionProjectionService(CedarConfig config, UserService users, NodeIndexingService index) {
    this.config=config; this.users=users; this.index=index; this.links=this::syncPrevious;
    this.contents=(artifact, content, context) -> {
      if (artifact.getType() == CedarResourceType.TEMPLATE || artifact.getType() == CedarResourceType.ELEMENT) {
        InclusionSubgraphUtil.updateResourceInclusionInfo(FolderServerResourceExtract.fromNode(artifact),
            CedarDataServices.getInstance().getInclusionSubgraphServiceSession(context), content);
      }
    };
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
        if (!complete(id, indexing, context)) break; // One unavailable downstream costs one timeout per batch.
      }
    } catch (Exception e) {
      LoggerFactory.getLogger(getClass()).warn("Artifact projections remain pending: {}",e.toString());
    }
  }

  /** Capture the affected chain before a lifecycle mutation, including before deleting its anchor. */
  public List<String> relatedIds(String id) {
    try (var session=driver.session()) {
      return session.run("MATCH (a:Artifact {_id:$id}) MATCH (a)-[:PREVIOUSVERSION*0..]-(v:Artifact) "
          + "RETURN DISTINCT v._id AS id ORDER BY id", Map.of("id",id)).list(r -> r.get("id").asString());
    }
  }

  /** A request must refresh its own chain even when an unrelated, older relay job is blocked. */
  public void completeRelated(Collection<String> ids, NodeIndexingService indexing, CedarRequestContext context) {
    for (String id : ids) complete(id,indexing,context);
  }

  /** Reindex commands also use current graph state, never an object retained by a delayed caller. */
  public void refresh(String id, NodeIndexingService indexing, CedarRequestContext context) {
    try (var session=driver.session()) {
      session.writeTransaction(tx -> {
        VersionChainTransaction.lock(tx);
        VersionChainTransaction.enqueue(tx,id,false);
        return null;
      });
    }
    complete(id,indexing,context);
  }

  /**
   * Writes one artifact's projections out, and reports whether the caller may go on to the next job.
   *
   * <p>Lifecycle and ordinary graph mutations take the lifecycle lock before touching any artifact
   * node, so that lock is held here only for the two short transactions that read a job and remove
   * it. The version link, the inclusion edges and the index document are written between them with no
   * graph lock held. Writing them inside the lock made every graph mutation in the installation
   * wait for each index round trip.
   *
   * <p>Two things keep the result what it was when the lock covered the writes. A claim on the job
   * leaves one writer per artifact, across processes, so two writes of one artifact cannot land out
   * of order. And the job is removed only if its generation is the one that was read: a mutation
   * committed during the write moved it, and the artifact is written out again from its new state.
   */
  public boolean complete(String id, NodeIndexingService indexing, CedarRequestContext context) {
    return project(id, indexing, context) != Outcome.FAILED;
  }

  private enum Outcome { DONE, DEFERRED, FAILED }
  private enum Settled { REMOVED, MOVED_ON, ARTIFACT_DELETED, TAKEN_OVER }
  private record Claimed(Map<String,Object> job, Map<String,Object> artifact, boolean busy) {}

  /** Longer than any bounded downstream call, so a live writer is never taken for a dead one. */
  static final long CLAIM_MILLIS=TimeUnit.MINUTES.toMillis(2);
  /**
   * One write, and one more if a mutation was recorded during it. An artifact that many callers are
   * changing at once moves on during every write, and the caller holding the claim would otherwise
   * pay for all of them: measured with twenty writers on one artifact, five rounds took the slowest
   * percent of writes from 1.2 s to 1.9 s. The relay writes out what a request leaves.
   */
  private static final int ROUNDS=2;

  private Outcome project(String id, NodeIndexingService indexing, CedarRequestContext context) {
    String claim=UUID.randomUUID().toString();
    try (var session=driver.session()) {
      // Most members of a version chain have nothing pending, and finding that out needs no lock.
      if (session.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN j.resourceId AS id LIMIT 1",
          Map.of("id",id)).list().isEmpty()) return Outcome.DONE;
      FolderServerArtifact artifact=null;
      try {
        for (int round=0; round<ROUNDS; round++) {
          var claimed=claim(id,claim);
          if (claimed==null) return Outcome.DONE;
          // Its owner settles against the generation, so it writes this caller's change out as well.
          if (claimed.busy()) return Outcome.DEFERRED;
          artifact=null;
          if (claimed.artifact()!=null) {
            artifact=JsonMapper.TOLERANT_MAPPER.convertValue(org.metadatacenter.server.neo4j.util.Neo4JUtil.unescapeTopLevelPropertyNames(JsonMapper.STRICT_MAPPER.valueToTree(claimed.artifact())),FolderServerArtifact.class);
            if (Boolean.TRUE.equals(claimed.job().get("syncPrevious"))) links.write(artifact,(String)claimed.artifact().get("pav_previousVersion"));
            if (claimed.job().get("content") instanceof String content) contents.write(artifact,JsonMapper.STRICT_MAPPER.readTree(content),context);
            // Replace by stable ID, without the previous delete-then-index visibility gap.
            indexing.indexDocumentForProjection(artifact,context);
          }
          switch (settle(id,claim,generation(claimed.job()))) {
            case REMOVED: return Outcome.DONE;
            case MOVED_ON: continue;
            case ARTIFACT_DELETED:
              // The deletion removed its document before this write landed, so the write is undone.
              // Only a process that stops between that write and this line leaves the document.
              if (artifact!=null) indexing.removeDocumentFromIndex(artifact.getResourceId());
              return Outcome.DONE;
            case TAKEN_OVER:
              // This write outlived its claim and may have landed on top of its successor's.
              try (var tx=session.beginTransaction()) {
                VersionChainTransaction.lock(tx);
                VersionChainTransaction.enqueue(tx,id,false);
                tx.commit();
              }
              return Outcome.DEFERRED;
          }
        }
        release(session,id,claim,false);
        return Outcome.DEFERRED;
      } catch (Exception e) {
        LoggerFactory.getLogger(getClass()).warn("Artifact projection for {} remains pending: {}",id,e.toString());
        release(session,id,claim,true);
        // A write of an artifact deleted meanwhile can fail after its document reached the index.
        if (artifact!=null && session.run("MATCH (a:Artifact {_id:$id}) RETURN a._id AS id LIMIT 1",
            Map.of("id",id)).list().isEmpty()) indexing.removeDocumentFromIndex(artifact.getResourceId());
      }
    } catch (Exception e) {
      LoggerFactory.getLogger(getClass()).warn("Cannot complete artifact projection for {}: {}",id,e.toString());
    }
    return Outcome.FAILED;
  }

  private static long generation(Map<String,Object> job) {
    return job.get("generation") instanceof Number number ? number.longValue() : 0;
  }

  /** Reads the job and the artifact state it describes in one locked transaction, and claims the job. */
  private Claimed claim(String id, String claim) {
    return locked(tx -> {
      var pending=tx.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN properties(j) AS job, timestamp() AS now",Map.of("id",id));
      if (!pending.hasNext()) return null;
      var row=pending.next();
      var job=row.get("job").asMap();
      boolean live=job.get("claimedAt") instanceof Number claimedAt && row.get("now").asLong()-claimedAt.longValue()<CLAIM_MILLIS;
      if (live && job.get("claim")!=null && !claim.equals(job.get("claim"))) return new Claimed(job,null,true);
      tx.run("MATCH (j:CedarVersionProjection {resourceId:$id}) SET j.claim=$claim, j.claimedAt=timestamp()",
          Map.of("id",id,"claim",claim)).consume();
      var found=tx.run("MATCH (a:Artifact {_id:$id}) RETURN properties(a) AS a",Map.of("id",id));
      Map<String,Object> artifact=found.hasNext() ? found.next().get("a").asMap() : null;
      return new Claimed(job,artifact,false);
    });
  }

  /** Removes the job if it still describes what was written out, and says what happened if not. */
  private Settled settle(String id, String claim, long generation) {
    return locked(tx -> {
      var current=tx.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN j.claim AS claim, "
          + "coalesce(j.generation,0) AS generation",Map.of("id",id));
      Settled settled;
      if (!current.hasNext()) {
        // Deleting an artifact removes its job with it. Any other absence is a successor's work.
        settled=tx.run("MATCH (a:Artifact {_id:$id}) RETURN a._id AS id LIMIT 1",Map.of("id",id)).hasNext()
            ? Settled.TAKEN_OVER : Settled.ARTIFACT_DELETED;
      } else {
        var row=current.next();
        if (row.get("claim").isNull() || !claim.equals(row.get("claim").asString())) settled=Settled.TAKEN_OVER;
        else if (row.get("generation").asLong()!=generation) settled=Settled.MOVED_ON;
        else {
          tx.run("MATCH (j:CedarVersionProjection {resourceId:$id}) DELETE j",Map.of("id",id)).consume();
          settled=Settled.REMOVED;
        }
      }
      return settled;
    });
  }

  /** A caller's work at the lifecycle lock, and what came of it once its transaction committed. */
  private static final class Turn<T> {
    final java.util.function.Function<Transaction,T> work;
    T result;
    RuntimeException failure;
    volatile boolean done;
    Turn(java.util.function.Function<Transaction,T> work) { this.work=work; }
    void run(Transaction tx) { result=work.apply(tx); }
  }

  private final ConcurrentLinkedQueue<Turn<?>> waiting=new ConcurrentLinkedQueue<>();
  private final java.util.concurrent.locks.ReentrantLock leading=new java.util.concurrent.locks.ReentrantLock();
  private static final int TURN_BATCH=64;
  /** How long a caller waits for the caller ahead of it before taking a turn of its own. */
  long turnWaitMillis=2000;
  /** Runs as a caller starts its turn. Tests hold a caller here to stand in for one that is stuck. */
  Runnable enteringTurn=() -> {};

  /**
   * Runs short work under the lifecycle lock, sharing the turn with every caller waiting for one.
   *
   * Each turn at the lock is a transaction and a commit, and all of them queue on one node. Fifty
   * writers that each take one turn to claim a job and another to remove it wait for a hundred
   * commits. Here the caller at the front takes the lock once and runs everything queued by then in
   * one transaction, and the callers behind it find their work done. A result is handed out only
   * after that transaction commits. If it fails, each caller's work runs in a transaction of its
   * own, so one caller's failure stays its own.
   *
   * A caller does not wait on the one ahead of it without limit. A transaction has been seen to wait
   * on this lock indefinitely while others passed it, and a caller stuck that way must cost one
   * thread, as it did when every caller had its own transaction. After the wait, a caller takes a
   * turn of its own beside the one in front.
   */
  private <T> T locked(java.util.function.Function<Transaction,T> work) {
    Turn<T> mine=new Turn<>(work);
    waiting.add(mine);
    while (!mine.done) {
      boolean inFront=false;
      try {
        inFront=leading.tryLock(turnWaitMillis,TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // Work another caller has already taken still runs: a claim that lapses, or a removal that was due.
        waiting.remove(mine);
        throw new IllegalStateException("Interrupted while waiting for a turn at the lifecycle lock",e);
      }
      try {
        if (!mine.done) takeTurn();
      } catch (RuntimeException lockNotTaken) {
        // No work was taken from the queue. While this caller's is still there, the failure is its
        // own to report; if another caller has taken it, the outcome comes from that caller's turn.
        if (waiting.remove(mine)) throw lockNotTaken;
      } finally {
        if (inFront) leading.unlock();
      }
    }
    if (mine.failure!=null) throw mine.failure;
    return mine.result;
  }

  /** How many callers are waiting for a turn. */
  int waitingForATurn() {
    return waiting.size();
  }

  private void takeTurn() {
    enteringTurn.run();
    List<Turn<?>> batch=new ArrayList<>();
    int settled=0;
    try {
      // Explicit transactions: network writes must not be automatically replayed by the driver.
      try (var session=driver.session(); var tx=session.beginTransaction()) {
        VersionChainTransaction.lock(tx);
        // Taken from the queue only once the lock is held, so a caller stuck waiting for the lock
        // holds nobody's work but its own.
        for (Turn<?> next; batch.size()<TURN_BATCH && (next=waiting.poll())!=null; ) batch.add(next);
        for (Turn<?> turn : batch) turn.run(tx);
        tx.commit();
        settled=batch.size();
      } catch (RuntimeException shared) {
        if (batch.isEmpty()) throw shared;
        for (Turn<?> turn : batch) {
          try (var session=driver.session(); var tx=session.beginTransaction()) {
            VersionChainTransaction.lock(tx);
            turn.run(tx);
            tx.commit();
          } catch (RuntimeException own) {
            turn.result=null;
            turn.failure=own;
          }
          settled++;
        }
      }
    } finally {
      // Every caller whose work was taken hears of it, whatever ended the turn. A caller left
      // waiting would take the lock again and again for work that is no longer in the queue.
      for (int i=0; i<batch.size(); i++) {
        Turn<?> turn=batch.get(i);
        if (i>=settled) {
          turn.result=null;
          turn.failure=new IllegalStateException("The turn at the lifecycle lock ended before this work was committed");
        }
        turn.done=true;
      }
    }
  }

  /** Gives the job up for the relay, counting an attempt against it when the write failed. */
  private void release(Session session, String id, String claim, boolean failed) {
    session.run("MATCH (j:CedarVersionProjection {resourceId:$id}) WHERE j.claim=$claim "
        + "REMOVE j.claim, j.claimedAt SET j.updatedAt=timestamp(), "
        + "j.attempts=coalesce(j.attempts,0)+CASE WHEN $failed THEN 1 ELSE 0 END",
        Map.of("id",id,"claim",claim,"failed",failed)).consume();
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
