package com.refinedmods.refinedstorage.common.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PendingAutocraftingRequests {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingAutocraftingRequests.class);

    private final ConcurrentMap<CancellationToken, CompletableFuture<?>> requests = new ConcurrentHashMap<>();

    public <T> void add(final CompletableFuture<T> future, final CancellationToken cancellationToken) {
        requests.put(cancellationToken, future);
        future.whenComplete((value, e) -> requests.remove(cancellationToken, future));
    }

    public void cancelAll() {
        if (requests.isEmpty()) {
            return;
        }

        LOGGER.info("Cancelling {} pending autocrafting requests", requests.size());

        requests.forEach((token, future) -> {
            token.cancel();
            future.cancel(true);
        });
        requests.clear();
    }
}
