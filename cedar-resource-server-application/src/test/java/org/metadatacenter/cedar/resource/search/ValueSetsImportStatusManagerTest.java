package org.metadatacenter.cedar.resource.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueSetsImportStatusManagerTest {

  private final ValueSetsImportStatusManager manager = ValueSetsImportStatusManager.getInstance();

  @BeforeEach
  void resetStatus() {
    manager.setImportStatus(ValueSetsImportStatusManager.ImportStatus.NOT_YET_INITIATED);
  }

  @Test
  void exactlyOneOfManySimultaneousClaimsWins() throws Exception {
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier allAtOnce = new CyclicBarrier(threads);
      List<Callable<Boolean>> claims = IntStream.range(0, threads)
          .<Callable<Boolean>>mapToObj(i -> () -> {
            allAtOnce.await(10, TimeUnit.SECONDS);
            return manager.tryStart();
          })
          .collect(Collectors.toList());

      long won = 0;
      for (Future<Boolean> outcome : pool.invokeAll(claims)) {
        if (outcome.get()) {
          won++;
        }
      }
      assertEquals(1, won);
      assertEquals(ValueSetsImportStatusManager.ImportStatus.IN_PROGRESS, manager.getImportStatus());
    } finally {
      pool.shutdownNow();
    }
  }
}
