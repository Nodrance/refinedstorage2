package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// A pool of resources, where each resource is represented by a MultiResourceKey and has a long amount.
// Similar to 
public class ResourcePool implements Iterable<Map.Entry<MultiResourceKey, Long>> {
    private final Map<MultiResourceKey, Long> amounts;

    public ResourcePool() {
        this.amounts = new LinkedHashMap<>();
    }

    public ResourcePool(final Map<MultiResourceKey, Long> amounts) {
        this();
        Objects.requireNonNull(amounts, "amounts cannot be null");
        amounts.forEach(this::setAmount);
    }

    public static ResourcePool empty() {
        return new ResourcePool();
    }

    public static ResourcePool copyOf(final ResourcePool other) {
        Objects.requireNonNull(other, "other cannot be null");
        return new ResourcePool(other.amounts);
    }

    public Map<MultiResourceKey, Long> asMap() {
        return Collections.unmodifiableMap(amounts);
    }

    public Set<MultiResourceKey> resourceKeys() {
        return Collections.unmodifiableSet(amounts.keySet());
    }

    public long getAmount(final MultiResourceKey resource) {
        Objects.requireNonNull(resource, "resource cannot be null");
        return amounts.getOrDefault(resource, 0L);
    }

    public long totalAmount() {
        long total = 0;
        for (final long amount : amounts.values()) {
            total += amount;
        }
        return total;
    }

    public boolean isEmpty() {
        return amounts.isEmpty();
    }

    public void setAmount(final MultiResourceKey resource, final long amount) {
        Objects.requireNonNull(resource, "resource cannot be null");
        if (amount == 0L) {
            amounts.remove(resource);
            return;
        }
        amounts.put(resource, amount);
    }

    public void addAmount(final MultiResourceKey resource, final long amount) {
        Objects.requireNonNull(resource, "resource cannot be null");
        if (amount == 0L) {
            return;
        }
        setAmount(resource, getAmount(resource) + amount);
    }

    public void addAll(final ResourcePool other) {
        Objects.requireNonNull(other, "other cannot be null");
        other.amounts.forEach(this::addAmount);
    }

    public void subtractAll(final ResourcePool other) {
        Objects.requireNonNull(other, "other cannot be null");
        other.amounts.forEach(this::subtractAmount);
    }

    public void subtractAmount(final MultiResourceKey resource, final long amount) {
        addAmount(resource, -amount);
    }

    @Override
    public java.util.Iterator<Map.Entry<MultiResourceKey, Long>> iterator() {
        return amounts.entrySet().iterator();
    }

    public ResourcePool copy() {
        return copyOf(this);
    }

    @Override
    public String toString() {
        return amounts.toString();
    }
}
