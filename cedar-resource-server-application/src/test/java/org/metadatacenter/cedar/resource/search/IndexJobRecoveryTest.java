package org.metadatacenter.cedar.resource.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.server.search.util.IndexRebuildRegistry;
import org.metadatacenter.server.search.util.IndexRebuildStore;
import org.metadatacenter.server.search.util.InMemoryIndexRebuildStore;
import org.metadatacenter.util.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a poll says about a rebuild the server did not survive.
 *
 * <p>Before the record outlived the process, a restart in hour seven of an eight-hour rebuild left
 * the status reporting IDLE: that nothing had ever run. These pin the answer that replaced it.
 */
class IndexJobRecoveryTest {

  private IndexRebuildStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryIndexRebuildStore();
    IndexRebuildRegistry.install(store);
    releaseSearchIndex();
  }

  @AfterEach
  void tearDown() {
    releaseSearchIndex();
    store.clearJobRecord();
  }

  /** Leave the guard's static state as the next test expects to find it. */
  private void releaseSearchIndex() {
    IndexJobGuard.reset(IndexJobGuard.Index.SEARCH, Instant.now().plus(IndexJobGuard.STALL_AFTER).plus(IndexJobGuard.STALL_AFTER));
    IndexJobGuard.recoverFromStore();
    IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "settle")
        .ifPresent(claim -> IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, claim, null));
    store.clearJobRecord();
  }

  private void saveRunningRecord(String jobId, String startedAt) throws Exception {
    IndexJobGuard.Status running = new IndexJobGuard.Status(jobId, IndexJobGuard.State.RUNNING,
        "regenerate-search-index", startedAt, null, Instant.parse(startedAt).plusSeconds(21600).toString(),
        false, null, null);
    store.saveJobRecord(JsonMapper.STRICT_MAPPER.writeValueAsString(running));
  }

  @Test
  void aRebuildThatDidNotSurviveIsReportedAsInterrupted() throws Exception {
    saveRunningRecord("job-1", "2026-09-14T03:00:00Z");

    IndexJobGuard.recoverFromStore();

    IndexJobGuard.Status status = IndexJobGuard.status(IndexJobGuard.Index.SEARCH);
    assertEquals(IndexJobGuard.State.INTERRUPTED, status.state());
    assertEquals("job-1", status.jobId());
    assertEquals("regenerate-search-index", status.command());
    assertNotNull(status.failure(), "it must say why it stopped, not merely that it is not running");
  }

  @Test
  void theIndexIsFreeAfterRecovery() throws Exception {
    saveRunningRecord("job-1", "2026-09-14T03:00:00Z");
    IndexJobGuard.recoverFromStore();

    // The dead job holds nothing: a new rebuild must be able to start at once.
    Optional<JobClaim> claim = IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index");

    assertTrue(claim.isPresent());
    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, claim.get(), null);
  }

  @Test
  void aNewJobReplacesWhatThePreviousProcessLeft() throws Exception {
    saveRunningRecord("job-1", "2026-09-14T03:00:00Z");
    IndexJobGuard.recoverFromStore();

    JobClaim claim = IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();

    assertEquals(IndexJobGuard.State.RUNNING, IndexJobGuard.status(IndexJobGuard.Index.SEARCH).state(),
        "the running job, not the one that died before it, is what this index is doing");
    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, claim, null);
  }

  @Test
  void aFinishedJobLeavesNothingToRecover() {
    JobClaim claim = IndexJobGuard.tryStart(IndexJobGuard.Index.SEARCH, "regenerate-search-index").orElseThrow();
    IndexJobGuard.finish(IndexJobGuard.Index.SEARCH, claim, null);

    assertTrue(store.loadJobRecord().isEmpty(),
        "a job that ended is accounted for and must not come back as interrupted");
  }

  @Test
  void anUnreadableRecordIsDiscardedRatherThanFailingStartUp() {
    store.saveJobRecord("{ this is not a status }");

    IndexJobGuard.recoverFromStore();

    assertTrue(store.loadJobRecord().isEmpty());
    assertNotEquals(IndexJobGuard.State.INTERRUPTED, IndexJobGuard.status(IndexJobGuard.Index.SEARCH).state(),
        "an unreadable record must not be reported as a rebuild that was running");
  }

  @Test
  void aRecordThatWasNotRunningIsSimplyCleared() throws Exception {
    IndexJobGuard.Status complete = new IndexJobGuard.Status("job-0", IndexJobGuard.State.COMPLETE,
        "regenerate-search-index", "2026-09-14T03:00:00Z", "2026-09-14T09:00:00Z", null, false, null, null);
    store.saveJobRecord(JsonMapper.STRICT_MAPPER.writeValueAsString(complete));

    IndexJobGuard.recoverFromStore();

    assertTrue(store.loadJobRecord().isEmpty());
    assertNotEquals(IndexJobGuard.State.INTERRUPTED, IndexJobGuard.status(IndexJobGuard.Index.SEARCH).state(),
        "a job that had already ended did not stop because of the restart");
  }
}
