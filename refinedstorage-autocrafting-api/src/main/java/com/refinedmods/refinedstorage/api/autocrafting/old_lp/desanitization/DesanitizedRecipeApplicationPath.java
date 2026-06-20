package com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.ResourceList;
import com.refinedmods.refinedstorage.api.resource.list.ResourceListImpl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Describes the fully desanitized LP solution plus the actual steps to apply it in, in order.
// All resources, recipes, and resource pools have been fully desanitized.
// If missingResources is non-empty, this represents what the crafter would do if they weren't missing.
// If the item is craftable, this represents what the crafter will actually do.
public record DesanitizedRecipeApplicationPath(
    List<DesanitizedRecipeApplicationStep> steps,
    ResourceList usedResources,
    Map<ResourceKey, Long> finalInventoryValues,
    ResourceList missingResources,
    List<ResourceKey> relevantResourceKeys,
    ResourceList peakResourceUsage,
    boolean hasCycles
) {
    public DesanitizedRecipeApplicationPath(
        final List<DesanitizedRecipeApplicationStep> steps,
        final Map<ResourceKey, Long> usedResources,
        final Map<ResourceKey, Long> finalInventoryValues,
        final Map<ResourceKey, Long> missingResources,
        final List<ResourceKey> relevantResourceKeys,
        final Map<ResourceKey, Long> peakResourceUsage,
        final boolean hasCycles
    ) {
        this(
            steps,
            ResourceListImpl.copyOf(usedResources),
            finalInventoryValues,
            ResourceListImpl.copyOf(missingResources),
            relevantResourceKeys,
            ResourceListImpl.copyOf(peakResourceUsage),
            hasCycles
        );
    }

    public DesanitizedRecipeApplicationPath {
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(usedResources, "usedResources cannot be null");
        Objects.requireNonNull(finalInventoryValues, "finalInventoryValues cannot be null");
        Objects.requireNonNull(missingResources, "missingResources cannot be null");
        Objects.requireNonNull(relevantResourceKeys, "relevantResourceKeys cannot be null");
        Objects.requireNonNull(peakResourceUsage, "peakResourceUsage cannot be null");
        steps = List.copyOf(steps);
        usedResources = ResourceListImpl.copyOf(usedResources);
        finalInventoryValues = Map.copyOf(new LinkedHashMap<>(finalInventoryValues));
        missingResources = ResourceListImpl.copyOf(missingResources);
        relevantResourceKeys = List.copyOf(relevantResourceKeys);
        peakResourceUsage = ResourceListImpl.copyOf(peakResourceUsage);
    }
}
