package org.metadatacenter.cedar.resource.resources;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.resource.search.IndexJobGuard;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A claim that is never handed to a running job is released rather than held.
 *
 * <p>{@link IndexJobGuard#runClaimed} covers every path the job itself can take, but the claim is
 * taken before the executor exists. A thread that cannot be created, or a task that is refused,
 * leaves the window between the two: without this the index stays claimed on behalf of a job that
 * never started, every later rebuild is refused with a 409 naming it, and only restarting the
 * server clears it — the guard has no timeout and no reset path.
 */
public class ClaimedIndexJobSubmissionTest {

  private static final IndexJobGuard.Index INDEX = IndexJobGuard.Index.SEARCH;

  @AfterEach
  public void releaseAnyClaim() {
    if (IndexJobGuard.status(INDEX).state() == IndexJobGuard.State.RUNNING) {
      IndexJobGuard.finish(INDEX, null);
    }
  }

  @Test
  public void aRefusedTaskReleasesTheClaim() {
    assertTrue(IndexJobGuard.tryStart(INDEX, "regenerate-search-index"));

    ExecutorService refuses = Executors.newSingleThreadExecutor();
    refuses.shutdown();  // a shut-down executor refuses every task it is offered

    assertThrows(RejectedExecutionException.class,
        () -> CommandSearchResource.submitClaimedIndexJob(INDEX, "search index regeneration",
            () -> { }, () -> refuses));

    assertEquals(IndexJobGuard.State.FAILED, IndexJobGuard.status(INDEX).state());
    assertTrue(IndexJobGuard.tryStart(INDEX, "regenerate-search-index"),
        "a task the executor refused must not leave the index claimed");
  }

  @Test
  public void anExecutorThatCannotBeCreatedReleasesTheClaim() {
    assertTrue(IndexJobGuard.tryStart(INDEX, "regenerate-search-index"));

    assertThrows(OutOfMemoryError.class,
        () -> CommandSearchResource.submitClaimedIndexJob(INDEX, "search index regeneration",
            () -> { }, () -> { throw new OutOfMemoryError("unable to create native thread"); }));

    IndexJobGuard.Status status = IndexJobGuard.status(INDEX);
    assertEquals(IndexJobGuard.State.FAILED, status.state());
    assertTrue(status.failure().contains("unable to create native thread"), status.failure());
    assertTrue(IndexJobGuard.tryStart(INDEX, "regenerate-search-index"),
        "a thread that could not be created must not leave the index claimed");
  }

  @Test
  public void aSubmittedJobKeepsTheClaimUntilItRuns() throws Exception {
    assertTrue(IndexJobGuard.tryStart(INDEX, "regenerate-search-index"));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CommandSearchResource.submitClaimedIndexJob(INDEX, "search index regeneration",
        () -> { }, () -> executor);

    executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(IndexJobGuard.State.COMPLETE, IndexJobGuard.status(INDEX).state(),
        "a job that ran to completion reports complete and leaves the index free");
  }
}
