package com.brendan.holograms;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectorUpdateQueueTest {
    @Test
    void drainReturnsEachQueuedValueOnceAndClearsTheQueue() {
        ProjectorUpdateQueue<Long> queue = new ProjectorUpdateQueue<>();
        queue.add(1L);
        queue.add(2L);
        queue.add(1L);

        assertEquals(2, queue.size());
        List<Long> drained = queue.drain();
        assertEquals(2, drained.size());
        assertTrue(drained.contains(1L));
        assertTrue(drained.contains(2L));
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void removeDropsAPendingValue() {
        ProjectorUpdateQueue<Long> queue = new ProjectorUpdateQueue<>();
        queue.add(7L);
        queue.remove(7L);

        assertTrue(queue.drain().isEmpty());
    }

    /**
     * Regression test for the server crash caused by computer threads adding to the pending set
     * while the server tick took its snapshot. This exercises the exact drain path the tick uses.
     */
    @Test
    void drainToleratesUpdatesArrivingFromComputerThreads() throws Exception {
        final int writerCount = 4;
        final int valuesPerWriter = 20_000;
        final long totalValues = (long) writerCount * valuesPerWriter;

        ProjectorUpdateQueue<Long> queue = new ProjectorUpdateQueue<>();
        Set<Long> observed = ConcurrentHashMap.newKeySet();
        AtomicBoolean draining = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + 1);

        try {
            Future<?> drainer = pool.submit(() -> {
                start.await();
                while (draining.get()) {
                    observed.addAll(queue.drain());
                    Thread.onSpinWait();
                }
                return null;
            });

            List<Future<?>> writers = new ArrayList<>(writerCount);
            for (int writer = 0; writer < writerCount; writer++) {
                final int writerIndex = writer;
                writers.add(pool.submit(() -> {
                    start.await();
                    for (int index = 0; index < valuesPerWriter; index++) {
                        queue.add((long) writerIndex * valuesPerWriter + index);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(30, TimeUnit.SECONDS);
            }
            draining.set(false);
            drainer.get(30, TimeUnit.SECONDS);
            observed.addAll(queue.drain());

            Set<Long> expected = LongStream.range(0, totalValues).boxed().collect(Collectors.toSet());
            assertEquals(expected, observed);
            assertTrue(queue.drain().isEmpty());
            assertEquals(0, queue.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
