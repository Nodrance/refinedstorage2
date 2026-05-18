package com.refinedmods.refinedstorage.api.resource.list;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable implementation of a {@link ResourceList} that stores positive resource amounts in memory.
 */
public final class ResourceListImpl implements ResourceList {
    private static final ResourceListImpl EMPTY = new ResourceListImpl(Map.of());

    private final Map<ResourceKey, ResourceAmount> entries;
    private final List<ResourceAmount> state;
    private final Set<ResourceKey> resources;

    private ResourceListImpl(final Map<ResourceKey, ResourceAmount> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.state = List.copyOf(this.entries.values());
        this.resources = Collections.unmodifiableSet(new LinkedHashSet<>(this.entries.keySet()));
    }

    public static ResourceListImpl empty() {
        return EMPTY;
    }

    public static ResourceListImpl copyOf(final ResourceList resourceList) {
        Objects.requireNonNull(resourceList, "resourceList cannot be null");
        if (resourceList instanceof ResourceListImpl resourceListImpl) {
            return resourceListImpl;
        }
        return copyOf(resourceList.copyState());
    }

    public static ResourceListImpl copyOf(final Collection<ResourceAmount> resourceAmounts) {
        Objects.requireNonNull(resourceAmounts, "resourceAmounts cannot be null");
        if (resourceAmounts.isEmpty()) {
            return empty();
        }

        final Map<ResourceKey, ResourceAmount> entries = new LinkedHashMap<>();
        for (final ResourceAmount resourceAmount : resourceAmounts) {
            Objects.requireNonNull(resourceAmount, "resourceAmount cannot be null");
            ResourceAmount.validate(resourceAmount.resource(), resourceAmount.amount());
            entries.merge(
                resourceAmount.resource(),
                resourceAmount,
                (existing, additional) -> new ResourceAmount(
                    existing.resource(),
                    Math.addExact(existing.amount(), additional.amount())
                )
            );
        }
        return new ResourceListImpl(entries);
    }

    public static ResourceListImpl copyOf(final Map<ResourceKey, Long> resourceAmounts) {
        Objects.requireNonNull(resourceAmounts, "resourceAmounts cannot be null");
        if (resourceAmounts.isEmpty()) {
            return empty();
        }

        final Map<ResourceKey, ResourceAmount> entries = new LinkedHashMap<>();
        resourceAmounts.forEach((resource, amount) -> {
            ResourceAmount.validate(resource, amount);
            entries.put(resource, new ResourceAmount(resource, amount));
        });
        return new ResourceListImpl(entries);
    }

    @Override
    public Collection<ResourceAmount> copyState() {
        return state;
    }

    @Override
    public Set<ResourceKey> getAll() {
        return resources;
    }

    @Override
    public long get(final ResourceKey resource) {
        final ResourceAmount entry = entries.get(resource);
        return entry != null ? entry.amount() : 0L;
    }

    @Override
    public boolean contains(final ResourceKey resource) {
        return entries.containsKey(resource);
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public String toString() {
        return entries.toString();
    }
}