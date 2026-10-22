package me.neznamy.tab.shared.cpu;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ThreadExecutorTest {

    @Test
    public void interruptedShutdownStopsWorkAndPreservesInterrupt() throws Exception {
        ThreadExecutor executor = new ThreadExecutor("TAB test");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                stopped.countDown();
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            executor.shutdown();
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
        } finally {
            Thread.interrupted();
            executor.shutdown();
        }
    }

    @Test
    public void shutdownRaceIsIgnoredForEverySubmissionMethod() throws Exception {
        for (int method = 0; method < 4; method++) {
            RejectingExecutor delegate = new RejectingExecutor(true);
            ThreadExecutor executor = withDelegate(delegate);
            try {
                submit(executor, method);
                assertTrue(delegate.isShutdown());
            } finally {
                delegate.shutdownNow();
            }
        }
    }

    @Test
    public void unexpectedRejectionIsPropagatedForEverySubmissionMethod() throws Exception {
        for (int method = 0; method < 4; method++) {
            RejectingExecutor delegate = new RejectingExecutor(false);
            ThreadExecutor executor = withDelegate(delegate);
            try {
                submit(executor, method);
                fail("Expected rejection for submission method " + method);
            } catch (RejectedExecutionException expected) {
                assertFalse(delegate.isShutdown());
            } finally {
                delegate.shutdownNow();
            }
        }
    }

    private static ThreadExecutor withDelegate(RejectingExecutor delegate) throws Exception {
        ThreadExecutor executor = new ThreadExecutor("TAB test");
        executor.shutdown();
        Field field = ThreadExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        field.set(executor, delegate);
        return executor;
    }

    private static void submit(ThreadExecutor executor, int method) {
        TimedCaughtTask task = new TimedCaughtTask(null, () -> {}, "test", "test");
        switch (method) {
            case 0: executor.execute((Runnable) () -> {}); break;
            case 1: executor.execute(task); break;
            case 2: executor.executeLater(task, 1); break;
            case 3: executor.repeatTask(task, 1); break;
            default: throw new AssertionError(method);
        }
    }

    /** Simulates shutdown after the initial isShutdown check, before acceptance. */
    private static class RejectingExecutor extends ScheduledThreadPoolExecutor {
        private final boolean shutdownOnSubmission;

        private RejectingExecutor(boolean shutdownOnSubmission) {
            super(1);
            this.shutdownOnSubmission = shutdownOnSubmission;
        }

        private RejectedExecutionException rejection() {
            if (shutdownOnSubmission) shutdown();
            return new RejectedExecutionException("Test rejection");
        }

        @Override
        public void execute(Runnable command) {
            throw rejection();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw rejection();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw rejection();
        }
    }
}
