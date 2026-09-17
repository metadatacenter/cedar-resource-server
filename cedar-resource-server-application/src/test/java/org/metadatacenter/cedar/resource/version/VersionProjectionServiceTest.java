package org.metadatacenter.cedar.resource.version;

import org.junit.jupiter.api.*;
import org.metadatacenter.model.folderserver.basic.FolderServerSchemaArtifact;
import org.metadatacenter.server.neo4j.VersionChainTransaction;
import org.metadatacenter.server.search.elasticsearch.service.NodeIndexingService;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VersionProjectionServiceTest {
  @Test void failedDocumentAndIndexProjectionsSurviveRestartAndReadCurrentGraphState() throws Exception {
    try(var neo=Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
        var reader=GraphDatabase.driver(neo.boltURI(),AuthTokens.none())) {
      var index=mock(NodeIndexingService.class);
      var calls=new AtomicInteger();
      String id="https://repo.metadatacenter.org/templates/11111111-1111-1111-1111-111111111111";
      try(var service=new VersionProjectionService(GraphDatabase.driver(neo.boltURI(),AuthTokens.none()),index,
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
      try(var service=new VersionProjectionService(GraphDatabase.driver(neo.boltURI(),AuthTokens.none()),index,
          (artifact,previous) -> { assertNull(previous); calls.incrementAndGet(); });
          var s=reader.session()) {
        when(index.indexDocument(any(),isNull())).thenThrow(new org.metadatacenter.exception.CedarProcessingException("index unavailable"));
        service.completePending(index,null);
        assertEquals(1,s.run("MATCH (j:CedarVersionProjection) RETURN count(j) AS n").single().get("n").asInt());
        reset(index);
        // Recovery reads the current graph rather than replaying the stale object held by an old request.
        s.run("MATCH (a:Artifact {_id:$id}) SET a.isLatestVersion=false",Map.of("id",id)).consume();
        service.completePending(index,null);
        verify(index).indexDocument(argThat(a -> !((FolderServerSchemaArtifact)a).isLatestVersion()),isNull());
        assertEquals(0,s.run("MATCH (j:CedarVersionProjection) RETURN count(j) AS n").single().get("n").asInt());
        assertEquals(3,calls.get());
      }
    }
  }
}
