package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;

final class OjalgoSolveRunner {
    private static final long WAIT_SLICE_MILLIS = 250L;

    private final CancellationToken cancellationToken;
    private final Logger logger;

    OjalgoSolveRunner(final CancellationToken cancellationToken, final Logger logger) {
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    <T> T run(
        final String threadName,
        final String description,
        final Supplier<T> supplier,
        final Runnable timeoutLogger
    ) throws InterruptedException, ExecutionException {
        Objects.requireNonNull(threadName, "threadName cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        Objects.requireNonNull(supplier, "supplier cannot be null");
        Objects.requireNonNull(timeoutLogger, "timeoutLogger cannot be null");

        throwIfCancelled();

        final FutureTask<T> task = new FutureTask<>(supplier::get);
        final Thread workerThread = new Thread(task, threadName);
        workerThread.setDaemon(true);
        workerThread.start();

        while (true) {
            throwIfCancelled();

            final long remainingMillis = cancellationToken.timeRemainingMillis();
            if (remainingMillis <= 0L) {
                timeout(workerThread, description, timeoutLogger);
            }

            final long waitMillis = remainingMillis == Long.MAX_VALUE
                ? WAIT_SLICE_MILLIS
                : Math.min(remainingMillis, WAIT_SLICE_MILLIS);
            try {
                return task.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException e) {
                if (cancellationToken.timeRemainingMillis() <= 0L) {
                    timeout(workerThread, description, timeoutLogger);
                }
            }
        }
    }

    private void throwIfCancelled() {
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP solver cancelled");
        }
    }

    private void timeout(final Thread workerThread, final String description, final Runnable timeoutLogger) {
        cancellationToken.cancel();
        timeoutLogger.run();
        workerThread.interrupt();
        throw new java.util.concurrent.CancellationException("Timed out " + description);
    }

    Runnable simpleTimeoutLogger(final String description) {
        Objects.requireNonNull(description, "description cannot be null");
        return () -> logger.error("[LP] Timed out {} due to cancellation deadline.", description);
    }
}
