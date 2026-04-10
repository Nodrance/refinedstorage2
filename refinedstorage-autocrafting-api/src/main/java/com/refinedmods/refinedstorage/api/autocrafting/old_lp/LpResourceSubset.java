package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A virtual resource representing a set of interchangeable {@link ResourceKey}s.
 * Used in fuzzy recipe expansion where multiple items can satisfy the same ingredient slot.
 * Implements {@link ResourceKey} so it can be used transparently in {@link LpResourceSet} and the LP solver.
 */
public final class LpResourceSubset implements ResourceKey {
    private final List<ResourceKey> members;

    public LpResourceSubset(final Collection<ResourceKey> members) {
        Objects.requireNonNull(members, "members cannot be null");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("members cannot be empty");
        }
        this.members = members.stream()
            .sorted(Comparator.comparing(Object::toString))
            .toList();
    }

    public List<ResourceKey> members() {
        return members;
    }

    public boolean contains(final ResourceKey resource) {
        return members.contains(resource);
    }

    /**
     * Returns the total amount available in storage across all members.
     */
    public long availableAmount(final LpResourceSet storage) {
        long total = 0;
        for (final ResourceKey member : members) {
            total += Math.max(0L, storage.getAmount(member));
        }
        return total;
    }

    /**
     * Allocates concrete amounts from the subset's members using available storage.
     * Greedily uses members in order, consuming as much as possible from each.
     */
    public Map<ResourceKey, Long> allocateConcrete(final long totalNeeded, final LpResourceSet available) {
        final Map<ResourceKey, Long> allocation = new LinkedHashMap<>();
        long remaining = totalNeeded;
        for (final ResourceKey member : members) {
            if (remaining <= 0) {
                break;
            }
            final long memberAvailable = Math.max(0L, available.getAmount(member));
            final long use = Math.min(remaining, memberAvailable);
            if (use > 0) {
                allocation.put(member, use);
                remaining -= use;
            }
        }
        return allocation;
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof LpResourceSubset other && members.equals(other.members);
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }

    @Override
    public String toString() {
        return "Subset" + members;
    }
}
