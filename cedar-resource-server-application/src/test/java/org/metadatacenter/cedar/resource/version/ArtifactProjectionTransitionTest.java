package org.metadatacenter.cedar.resource.version;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.model.folderserver.basic.FolderServerArtifact;
import org.metadatacenter.server.neo4j.VersionChainTransaction;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.neo4j.driver.*;
import org.neo4j.harness.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The downstream probes persist independently of the relay's transaction, like HTTP/Redis writes. */
class ArtifactProjectionTransitionTest {
  private static Neo4j neo;
  private static Driver observer;
  private static final String ID="https://repo.metadatacenter.org/templates/11111111-1111-1111-1111-111111111111";
  private static final String INSTANCE="https://repo.metadatacenter.org/template-instances/22222222-2222-2222-2222-222222222222";
  private final AtomicReference<String> failure=new AtomicReference<>("");
  private NodeIndexingService index;

  @BeforeAll static void start() {
    neo=Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
    observer=GraphDatabase.driver(neo.boltURI(),AuthTokens.none());
    VersionChainTransaction.initialize(observer);
  }
  @AfterAll static void stop() { observer.close(); neo.close(); }
  @BeforeEach void reset() throws Exception {
    try(var s=observer.session()) {
      s.run("MATCH (n) WHERE NOT n:CedarVersionLock DETACH DELETE n").consume();
      s.run("CREATE (:Artifact:Template {_id:$id, resourceType:'template', schema_name:'Before', "
          + "pav_version:'0.0.1', bibo_status:'bibo:draft'})",Map.of("id",ID)).consume();
    }
    index=mock(NodeIndexingService.class);
    when(index.indexDocumentForProjection(any(),isNull())).thenAnswer(call -> {
      if (failure.get().equals("index")) throw new CedarProcessingException("index unavailable");
      FolderServerArtifact a=call.getArgument(0);
      persisted("index",a.getName());
      return null;
    });
  }

  private VersionProjectionService relay() {
    return new VersionProjectionService(GraphDatabase.driver(neo.boltURI(),AuthTokens.none()),index,
        (a, previous) -> {
          if (failure.get().equals("link")) throw new IllegalStateException("document unavailable");
          persisted("link",a.getName());
        }, (a, body, context) -> {
          if (failure.get().equals("inclusion")) throw new IllegalStateException("inclusion unavailable");
          persisted("inclusion",body.path("included").asText());
        }, a -> {
          if (failure.get().equals("notification")) throw new IllegalStateException("Redis unavailable");
          persisted("notification",a.getName());
          if (failure.get().equals("acknowledgement")) throw new IllegalStateException("reply lost after enqueue");
        });
  }
  private void persisted(String surface,String value) {
    try(var s=observer.session()) {
      s.run("MERGE (p:ProjectionProbe {surface:$surface}) SET p.value=$value",
          Map.of("surface",surface,"value",value)).consume();
    }
  }
  private String observed(String surface) {
    try(var s=observer.session()) {
      var r=s.run("MATCH (p:ProjectionProbe {surface:$surface}) RETURN p.value AS value",Map.of("surface",surface));
      return r.hasNext()?r.single().get("value").asString():null;
    }
  }
  private long pending() {
    try(var s=observer.session()) { return s.run("MATCH (j:CedarVersionProjection) RETURN count(j) AS n").single().get("n").asLong(); }
  }
  private void save(String name) {
    try(var s=observer.session()) {
      s.writeTransaction(tx -> {
        VersionChainTransaction.lock(tx);
        tx.run("MATCH (a:Artifact {_id:$id}) SET a.schema_name=$name",Map.of("id",ID,"name",name)).consume();
        VersionChainTransaction.enqueueContent(tx,ID,"{\"included\":\""+name+"\"}");
        return null;
      });
    }
  }
  private void assertDerived(String name) {
    assertEquals(name,observed("inclusion"));
    assertEquals(name,observed("index"));
    assertEquals(name,observed("notification"));
    assertEquals(0,pending());
  }

  @ParameterizedTest @ValueSource(strings={"link","inclusion","index","notification","acknowledgement"})
  void partialCompletionSurvivesRestartAndConverges(String failedAt) {
    save("Saved");
    try(var s=observer.session()) { s.writeTransaction(tx -> { VersionChainTransaction.lock(tx); VersionChainTransaction.enqueue(tx,ID,true); return null; }); }
    failure.set(failedAt);
    try(var first=relay()) {
      assertFalse(first.complete(ID,index,null));
      assertEquals(1,pending(),"partial external success must not acknowledge the job");
    }
    failure.set("");
    try(var restarted=relay()) { restarted.completePending(index,null); }
    assertDerived("Saved");
    assertEquals("Saved",observed("link"));
  }

  @ParameterizedTest @ValueSource(booleans={false,true})
  void aLifecycleRequestRefreshesItsWholeChainDespiteABlockedNotification(boolean blockedInSameChain) throws Exception {
    save("Blocked older save"); failure.set("notification");
    String release=blockedInSameChain?ID:ID+"-release", draft=ID+"-draft";
    try(var session=observer.session()) {
      session.writeTransaction(tx -> {
        VersionChainTransaction.lock(tx);
        tx.run("MERGE (r:Artifact:Template {_id:$release}) "
            + "SET r.resourceType='template', r.schema_name='Published', r.pav_version='1.0.0', r.bibo_status='bibo:published' "
            + "CREATE (d:Artifact:Template {_id:$draft,resourceType:'template',schema_name:'Draft',pav_version:'2.0.0',"
            + "bibo_status:'bibo:draft',pav_previousVersion:$release}) CREATE (d)-[:PREVIOUSVERSION]->(r)",
            Map.of("release",release,"draft",draft)).consume();
        VersionChainTransaction.reconcile(tx,draft);
        return null;
      });
    }
    try(var request=relay()) { request.completeRelated(request.relatedIds(draft),index,null); }
    verify(index).indexDocumentForProjection(argThat(a -> a.getId().equals(release)),isNull());
    verify(index).indexDocumentForProjection(argThat(a -> a.getId().equals(draft)),isNull());
    if(!blockedInSameChain) verify(index,never()).indexDocumentForProjection(argThat(a -> a.getId().equals(ID)),isNull());
    assertEquals(1,pending(),"The failed notification stays durable, without blocking either version's index");
  }

  @Test void aLateEarlierRequestProjectsOnlyTheSuccessorsSnapshot() throws Exception {
    save("First"); // Request A pauses after committing its graph update, before invoking the relay.
    save("Second");
    try(var successor=relay();var earlier=relay()) {
      assertTrue(successor.complete(ID,index,null));
      assertDerived("Second");
      assertTrue(earlier.complete(ID,index,null)); // No request-held content may be replayed here.
      assertDerived("Second");
      verify(index,times(1)).indexDocumentForProjection(any(),isNull());
    }
  }

  @Test void aNewSaveSupersedesPartiallyAppliedWorkAcrossRestart() {
    save("First"); failure.set("notification");
    try(var first=relay()) { assertFalse(first.complete(ID,index,null)); }
    assertEquals("First",observed("index"));
    save("Second"); failure.set("");
    try(var restarted=relay()) { restarted.completePending(index,null); }
    assertDerived("Second");
  }

  @Test void aSaveWaitsForAnInFlightProjectionAndThenLeavesItsOwnDurableWork() throws Exception {
    save("First");
    var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var started=new CountDownLatch(1);
    doAnswer(call -> {
      FolderServerArtifact a=call.getArgument(0);
      if (a.getName().equals("First")) { entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); }
      persisted("index",a.getName()); return null;
    }).when(index).indexDocumentForProjection(any(),isNull());
    var threads=Executors.newFixedThreadPool(2);
    try(var first=relay();var second=relay()) {
      var projecting=threads.submit(() -> first.complete(ID,index,null));
      assertTrue(entered.await(5,TimeUnit.SECONDS));
      var saving=threads.submit(() -> { started.countDown(); save("Second"); });
      assertTrue(started.await(5,TimeUnit.SECONDS));
      assertThrows(TimeoutException.class,() -> saving.get(150,TimeUnit.MILLISECONDS));
      release.countDown();
      assertTrue(projecting.get(10,TimeUnit.SECONDS)); saving.get(10,TimeUnit.SECONDS);
      assertEquals(1,pending(),"acknowledging A must not remove B's later work");
      assertTrue(second.complete(ID,index,null)); assertDerived("Second");
    } finally { release.countDown(); threads.shutdownNow(); }
  }

  @Test void deletionBeforeRecoveryCannotResurrectDerivedState() {
    save("Deleted");
    try(var s=observer.session()) { s.writeTransaction(tx -> { VersionChainTransaction.delete(tx,ID); return null; }); }
    try(var restarted=relay()) { assertTrue(restarted.complete(ID,index,null)); }
    assertEquals(0,pending()); assertNull(observed("inclusion")); assertNull(observed("index"));
    assertNull(observed("notification")); verifyNoInteractions(index);
  }

  @Test void rollbackPreservesThePreviousCommittedSnapshotAndItsPendingWork() {
    save("First");
    try(var session=observer.session();var tx=session.beginTransaction()) {
      VersionChainTransaction.lock(tx);
      tx.run("MATCH (a:Artifact {_id:$id}) SET a.schema_name='Rolled back'",Map.of("id",ID)).consume();
      VersionChainTransaction.enqueueContent(tx,ID,"{\"included\":\"Rolled back\"}");
      tx.rollback();
    }
    try(var restarted=relay()) { assertTrue(restarted.complete(ID,index,null)); }
    assertDerived("First");
  }

  @Test void aLateCallbackAfterDeleteAndRecreateUsesOnlyTheRecreatedArtifact() {
    save("Deleted incarnation");
    try(var session=observer.session()) {
      session.writeTransaction(tx -> { VersionChainTransaction.delete(tx,ID); return null; });
      session.run("CREATE (:Artifact:Template {_id:$id, resourceType:'template', schema_name:'Recreated'})",
          Map.of("id",ID)).consume();
    }
    save("New incarnation");
    try(var earlier=relay()) { assertTrue(earlier.complete(ID,index,null)); }
    assertDerived("New incarnation");
  }

  @Test void templateAndInstanceIntentsCommitTogetherAndRollbackTogether() {
    try(var s=observer.session()) {
      s.run("CREATE (:Artifact:Instance {_id:$instance, resourceType:'instance', schema_isBasedOn:$template})",
          Map.of("instance",INSTANCE,"template",ID)).consume();
      try(var tx=s.beginTransaction()) {
        VersionChainTransaction.lock(tx); VersionChainTransaction.enqueueContent(tx,ID,"{}"); tx.rollback();
      }
      assertEquals(0,pending());
      save("Saved");
      assertEquals(2,pending());
      assertEquals(1,s.run("MATCH (j:CedarVersionProjection {resourceId:$id}) RETURN count(j) AS n",
          Map.of("id",INSTANCE)).single().get("n").asInt());
    }
  }
}
