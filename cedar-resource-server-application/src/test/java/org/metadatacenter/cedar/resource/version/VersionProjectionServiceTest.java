package org.metadatacenter.cedar.resource.version;

import org.junit.jupiter.api.*;
import org.metadatacenter.cedar.resource.ScratchNeo4j;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.server.neo4j.VersionChainTransaction;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.neo4j.driver.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("integration")
class VersionProjectionServiceTest {
  @BeforeAll static void blank() { ScratchNeo4j.blank(); }
  @Test void failedDocumentAndIndexProjectionsSurviveRestartAndReadCurrentGraphState() throws Exception {
    try(var reader=GraphDatabase.driver(ScratchNeo4j.boltURI(),AuthTokens.none())) {
      var index=mock(NodeIndexingService.class);
      var calls=new AtomicInteger();
      String id="https://repo.metadatacenter.org/templates/11111111-1111-1111-1111-111111111111";
      try(var service=new VersionProjectionService(GraphDatabase.driver(ScratchNeo4j.boltURI(),AuthTokens.none()),index,
          (artifact,previous) -> { calls.incrementAndGet(); throw new IllegalStateException("content service unavailable"); });
          var s=reader.session()) {
        s.run("CREATE (:Artifact:Template {_id:$id, resourceType:'template', schema_name:'Example', pav_version:'1.0.0', bibo_status:'bibo:published', "
            + "isLatestVersion:true,isLatestDraftVersion:false,isLatestPublishedVersion:true})",Map.of("id",id)).consume();
        s.writeTransaction(tx -> { VersionChainTransaction.lock(tx); VersionChainTransaction.enqueue(tx,id,true); return null; });
        service.completePending(index,null);
        assertEquals(1,calls.get());
        verifyNoInteractions(index);
        assertEquals(1,s.run("MATCH (j:CedarVersionProjection) RETURN count(j) AS n").single().get("n").asInt());
      }
      try(var service=new VersionProjectionService(GraphDatabase.driver(ScratchNeo4j.boltURI(),AuthTokens.none()),index,
          (artifact,previous) -> { assertNull(previous); calls.incrementAndGet(); });
          var s=reader.session()) {
        when(index.indexDocumentForProjection(any(),isNull())).thenThrow(new org.metadatacenter.exception.CedarProcessingException("index unavailable"));
        service.completePending(index,null);
        assertEquals(1,s.run("MATCH (j:CedarVersionProjection) RETURN count(j) AS n").single().get("n").asInt());
        reset(index);
        // Recovery reads the current graph rather than replaying the stale object held by an old request.
        s.run("MATCH (a:Artifact {_id:$id}) SET a.isLatestVersion=false",Map.of("id",id)).consume();
        service.completePending(index,null);
        verify(index).indexDocumentForProjection(argThat(a -> !((FolderServerSchemaArtifact)a).isLatestVersion()),isNull());
        assertEquals(0,s.run("MATCH (j:CedarVersionProjection) RETURN count(j) AS n").single().get("n").asInt());
        assertEquals(3,calls.get());
      }
    }
  }

  private static String template(int number) {
    return "https://repo.metadatacenter.org/templates/00000000-0000-0000-0000-00000000000" + Integer.toHexString(number);
  }
  private static Driver driver() { return GraphDatabase.driver(ScratchNeo4j.boltURI(),AuthTokens.none()); }
  /** A latest template with one projection recorded against it, as a committed mutation leaves it. */
  private static void recorded(Session s, String id) {
    s.run("CREATE (:Artifact:Template {_id:$id, resourceType:'template', schema_name:'Example', pav_version:'0.0.1', bibo_status:'bibo:draft', "
        + "isLatestVersion:true,isLatestDraftVersion:true,isLatestPublishedVersion:false})",Map.of("id",id)).consume();
    s.writeTransaction(tx -> { VersionChainTransaction.lock(tx); VersionChainTransaction.enqueue(tx,id,false); return null; });
  }
  private static int jobs(Session s, String id) {
    return s.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN count(j) AS n",Map.of("id",id)).single().get("n").asInt();
  }
  private static long lockRevision(Session s) {
    return s.run("MATCH (n:CedarVersionLock {id:'lifecycle'}) RETURN coalesce(n.revision,0) AS r").single().get("r").asLong();
  }

  @Test void theLifecycleLockIsFreeWhileAnArtifactIsWrittenOut() throws Exception {
    String id=template(2);
    var index=mock(NodeIndexingService.class);
    var writing=new CountDownLatch(1);
    var finish=new CountDownLatch(1);
    when(index.indexDocumentForProjection(any(),isNull())).thenAnswer(call -> {
      writing.countDown();
      assertTrue(finish.await(20,TimeUnit.SECONDS));
      return null;
    });
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      recorded(s,id);
      var projection=CompletableFuture.supplyAsync(() -> service.complete(id,index,null));
      assertTrue(writing.await(20,TimeUnit.SECONDS));
      // Every graph mutation takes this lock first. Held across the index write, it made each of
      // them wait for that write, and this transaction would not return until the latch opened.
      CompletableFuture.runAsync(() -> {
        try(var other=reader.session()) { other.writeTransaction(tx -> { VersionChainTransaction.lock(tx); return null; }); }
      }).get(10,TimeUnit.SECONDS);
      // A second writer of the same artifact leaves it to the one that holds the claim.
      assertTrue(service.complete(id,index,null));
      verify(index,times(1)).indexDocumentForProjection(any(),isNull());
      assertEquals(1,jobs(s,id));
      finish.countDown();
      assertTrue(projection.get(20,TimeUnit.SECONDS));
      assertEquals(0,jobs(s,id));
      verify(index,times(1)).indexDocumentForProjection(any(),isNull());
    }
  }

  @Test void aMutationRecordedDuringAWriteIsWrittenOutInItsTurn() throws Exception {
    String id=template(3);
    var index=mock(NodeIndexingService.class);
    List<Boolean> written=new ArrayList<>();
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      recorded(s,id);
      when(index.indexDocumentForProjection(any(),isNull())).thenAnswer(call -> {
        written.add(((FolderServerSchemaArtifact)call.getArgument(0)).isLatestVersion());
        if (written.size()==1) {
          // A request commits a change to the same artifact while its earlier state is on the wire.
          try(var other=reader.session()) {
            other.writeTransaction(tx -> {
              VersionChainTransaction.lock(tx);
              tx.run("MATCH (a:Artifact {_id:$id}) SET a.isLatestVersion=false",Map.of("id",id)).consume();
              VersionChainTransaction.enqueue(tx,id,false);
              return null;
            });
          }
        }
        return null;
      });
      assertTrue(service.complete(id,index,null));
      // The stale write is followed by the current state, and only then is the job removed.
      assertEquals(List.of(true,false),written);
      assertEquals(0,jobs(s,id));
    }
  }

  @Test void anArtifactThatKeepsMovingIsLeftToTheRelayAfterASecondWrite() throws Exception {
    String id=template(7);
    var index=mock(NodeIndexingService.class);
    var writes=new AtomicInteger();
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      recorded(s,id);
      when(index.indexDocumentForProjection(any(),isNull())).thenAnswer(call -> {
        writes.incrementAndGet();
        // Another caller records a change during every write, as on an artifact many are editing.
        try(var other=reader.session()) {
          other.writeTransaction(tx -> { VersionChainTransaction.lock(tx); VersionChainTransaction.enqueue(tx,id,false); return null; });
        }
        return null;
      });
      // The request reports no failure and stops paying for other callers' changes.
      assertTrue(service.complete(id,index,null));
      assertEquals(2,writes.get());
      // The job stays, unclaimed, so the relay or the next caller writes the latest state out.
      assertEquals(1,jobs(s,id));
      assertTrue(s.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN j.claim IS NULL AS free",
          Map.of("id",id)).single().get("free").asBoolean());
      // The tests of this class share one graph, and a relay in another test would take this job.
      s.run("MATCH (j:CedarVersionProjection {resourceId:$id}) DELETE j",Map.of("id",id)).consume();
    }
  }

  @Test void callersWaitingTogetherShareATurnAtTheLifecycleLock() throws Exception {
    int callers=5;
    var index=mock(NodeIndexingService.class);
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      service.turnWaitMillis=60_000;
      List<String> ids=new ArrayList<>();
      for (int i=0; i<callers; i++) { ids.add(template(10+i)); recorded(s,ids.get(i)); }
      long before=lockRevision(s);
      List<CompletableFuture<Boolean>> completions=new ArrayList<>();
      // Something else holds the lock, as a save does, while five callers arrive to claim their jobs.
      try(var holder=reader.session(); var held=holder.beginTransaction()) {
        VersionChainTransaction.lock(held);
        for (String id : ids) completions.add(CompletableFuture.supplyAsync(() -> service.complete(id,index,null)));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while (service.waitingForATurn()<callers && System.nanoTime()<deadline) Thread.sleep(10);
        assertEquals(callers,service.waitingForATurn(),"every caller is waiting behind the held lock");
        held.commit();
      }
      for (var completion : completions) assertTrue(completion.get(30,TimeUnit.SECONDS));
      for (String id : ids) assertEquals(0,jobs(s,id));
      verify(index,times(callers)).indexDocumentForProjection(any(),isNull());
      // One turn for the holder and one for all five claims. The removals share turns as they
      // arrive, so they take between one and five. A turn for each claim and removal would be ten.
      long turns=lockRevision(s)-before;
      assertTrue(turns>=3 && turns<=7,"the lock was taken "+turns+" times");
    }
  }

  @Test void aCallerStuckInFrontCostsOnlyItself() throws Exception {
    String stuck=template(8), behind=template(9);
    var index=mock(NodeIndexingService.class);
    var holding=new CountDownLatch(1);
    var release=new CountDownLatch(1);
    var entered=new AtomicInteger();
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      recorded(s,stuck);
      recorded(s,behind);
      service.turnWaitMillis=200;
      // The first caller to start a turn never gets its lock, as one transaction was seen not to.
      service.enteringTurn=() -> {
        if (entered.incrementAndGet()==1) {
          holding.countDown();
          try { release.await(30,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
      };
      var first=CompletableFuture.supplyAsync(() -> service.complete(stuck,index,null));
      assertTrue(holding.await(10,TimeUnit.SECONDS));
      // The caller behind it waits a moment, then takes a turn of its own and finishes.
      assertTrue(CompletableFuture.supplyAsync(() -> service.complete(behind,index,null)).get(20,TimeUnit.SECONDS));
      assertEquals(0,jobs(s,behind));
      assertEquals(1,jobs(s,stuck));
      release.countDown();
      assertTrue(first.get(20,TimeUnit.SECONDS));
      assertEquals(0,jobs(s,stuck));
    }
  }

  @Test void aLockThatCannotBeTakenFailsTheCallerInsteadOfRetryingForever() throws Exception {
    String id=template(15);
    var index=mock(NodeIndexingService.class);
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      recorded(s,id);
      s.run("MATCH (n:CedarVersionLock {id:'lifecycle'}) DELETE n").consume();
      try {
        // The projection is reported as pending, promptly, and its job is still there to retry.
        assertFalse(CompletableFuture.supplyAsync(() -> service.complete(id,index,null)).get(20,TimeUnit.SECONDS));
        verifyNoInteractions(index);
        assertEquals(1,jobs(s,id));
      } finally {
        s.run("MERGE (:CedarVersionLock {id:'lifecycle'})").consume();
      }
      assertTrue(service.complete(id,index,null));
      assertEquals(0,jobs(s,id));
    }
  }

  @Test void anArtifactDeletedDuringAWriteLeavesNoDocumentBehind() throws Exception {
    String id=template(4);
    var index=mock(NodeIndexingService.class);
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      recorded(s,id);
      when(index.indexDocumentForProjection(any(),isNull())).thenAnswer(call -> {
        try(var other=reader.session()) {
          other.writeTransaction(tx -> {
            VersionChainTransaction.delete(tx,id);
            tx.run("MATCH (a:Artifact {_id:$id}) DETACH DELETE a",Map.of("id",id)).consume();
            return null;
          });
        }
        return null;
      });
      assertTrue(service.complete(id,index,null));
      // The deletion removed the document before this write landed, so the write has to be undone.
      verify(index).removeDocumentFromIndex(argThat(resource -> id.equals(resource.getId())));
      assertEquals(0,jobs(s,id));
    }
  }

  @Test void aLiveClaimIsLeftAloneAndAnExpiredOneIsTakenOver() throws Exception {
    String id=template(5);
    var index=mock(NodeIndexingService.class);
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      recorded(s,id);
      s.run("MATCH (j:CedarVersionProjection {resourceId:$id}) SET j.claim='another writer', j.claimedAt=timestamp()",Map.of("id",id)).consume();
      assertTrue(service.complete(id,index,null));
      verifyNoInteractions(index);
      assertEquals(1,jobs(s,id));
      // A writer that died holding its claim must not strand the job.
      s.run("MATCH (j:CedarVersionProjection {resourceId:$id}) SET j.claimedAt=timestamp()-$age",
          Map.of("id",id,"age",VersionProjectionService.CLAIM_MILLIS+1000)).consume();
      assertTrue(service.complete(id,index,null));
      verify(index).indexDocumentForProjection(any(),isNull());
      assertEquals(0,jobs(s,id));
    }
  }

  @Test void anArtifactWithNothingPendingCostsNoLockAndNoWrite() throws Exception {
    var index=mock(NodeIndexingService.class);
    try(var reader=driver(); var service=new VersionProjectionService(driver(),index,(artifact,previous) -> {}); var s=reader.session()) {
      long before=lockRevision(s);
      assertTrue(service.complete(template(6),index,null));
      assertEquals(before,lockRevision(s));
      verifyNoInteractions(index);
    }
  }
}
