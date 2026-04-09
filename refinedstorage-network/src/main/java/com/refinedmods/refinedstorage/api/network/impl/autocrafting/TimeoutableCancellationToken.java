package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;

import java.util.concurrent.atomic.AtomicBoolean;

public class TimeoutableCancellationToken implements CancellationToken {
    private static final long TIMEOUT_MS = 5000;

    private final long createdAt = System.currentTimeMillis();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    @Override
    public boolean isCancelled() {
        if (System.currentTimeMillis() - createdAt >= TIMEOUT_MS) {
            cancel();
        }
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public long timeRemainingMillis() {
        if (cancelled.get()) {
            return 0L;
        }

        final long elapsed = System.currentTimeMillis() - createdAt;
        final long remaining = TIMEOUT_MS - elapsed;
        if (remaining <= 0L) {
            cancel();
            return 0L;
        }
        return remaining;
    }
}
