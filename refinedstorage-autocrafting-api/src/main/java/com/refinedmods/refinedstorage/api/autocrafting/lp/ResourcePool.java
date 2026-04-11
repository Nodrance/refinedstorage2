package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Describes a collection of resource keys and amounts.
 * Maps resources to amounts, supporting negative amounts for deficit tracking.
 * Zero amounts are not stored.
 * 
 * Keys can be either {@link ResourceKey} (concrete) or {@link MultiResourceKey} (LP fuzzy groupings).
 * Conversion between these LP types and external types (Map, MutableResourceList, storage) is handled
 * by {@link RecipeSanitizer} (external → LP) and {@link RecipeDesanitizer} (LP → external).
 * 
 * INTERNAL LP-ONLY: Do not use outside the LP solver implementation.
 * Equivalent to old_lp's LpResourceSet.
 */
class ResourcePool implements Iterable<Map.Entry<Object, Long>> {
    private final Map<Object, Long> amounts;

    public ResourcePool() {
        this.amounts = new LinkedHashMap<>();
    }

    public ResourcePool(final Map<Object, Long> amounts) {
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

    public static ResourcePool fromResourceAmounts(final Collection<ResourceAmount> resourceAmounts) {
        Objects.requireNonNull(resourceAmounts, "resourceAmounts cannot be null");
        final ResourcePool result = new ResourcePool();
        for (final ResourceAmount resourceAmount : resourceAmounts) {
            result.addAmount(resourceAmount.resource(), resourceAmount.amount());
        }
        return result;
    }

    public Map<Object, Long> asMap() {
        return Collections.unmodifiableMap(amounts);
    }

    public Set<Object> resourceKeys() {
        return Collections.unmodifiableSet(amounts.keySet());
    }

    public long getAmount(final Object resource) {
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

    public void setAmount(final Object resource, final long amount) {
        Objects.requireNonNull(resource, "resource cannot be null");
        if (amount == 0L) {
            amounts.remove(resource);
            return;
        }
        amounts.put(resource, amount);
    }

    public void addAmount(final Object resource, final long amount) {
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

    public void subtractAmount(final Object resource, final long amount) {
        addAmount(resource, -amount);
    }

    @Override
    public java.util.Iterator<Map.Entry<Object, Long>> iterator() {
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
