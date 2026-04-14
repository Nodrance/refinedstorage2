package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A virtual resource representing a set of interchangeable {@link ResourceKey}s.
 * Used in fuzzy recipe expansion where multiple items can satisfy the same ingredient slot.
 * This key is internal to LP solving and intentionally separate from {@link ResourceKey}.
 * Equivalent to old_lp's LpResourceSubset.
 */
public final class MultiResourceKey {
    private final List<ResourceKey> members;

    public MultiResourceKey(final Collection<ResourceKey> members) {
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

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof MultiResourceKey other && members.equals(other.members);
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }

    @Override
    public String toString() {
        return "MRK[" + members.stream().map(m -> {
            // Try to extract a concise name for ItemResource, else fallback to toString
            if (m.getClass().getSimpleName().equals("ItemResource")) {
                try {
                    java.lang.reflect.Field itemField = m.getClass().getDeclaredField("item");
                    itemField.setAccessible(true);
                    Object item = itemField.get(m);
                    // Use the registry name if possible
                    java.lang.reflect.Method getDescriptionId = item.getClass().getMethod("getDescriptionId");
                    String descId = (String) getDescriptionId.invoke(item);
                    // descId is like "item.minecraft.oak_planks", take the last part
                    String[] parts = descId.split("\\.");
                    return parts[parts.length - 1];
                } catch (Exception e) {
                    // fallback
                    return m.toString();
                }
            } else {
                return m.toString();
            }
        }).collect(Collectors.joining(", ")) + "]";
    }
}
