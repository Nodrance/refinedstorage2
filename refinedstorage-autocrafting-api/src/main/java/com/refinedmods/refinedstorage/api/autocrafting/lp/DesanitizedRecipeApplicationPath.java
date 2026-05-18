package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

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
    Map<ResourceKey, Long> usedResources,
    Map<ResourceKey, Long> finalInventoryValues,
    Map<ResourceKey, Long> missingResources,
    List<ResourceKey> relevantResourceKeys,
    Map<ResourceKey, Long> peakResourceUsage,
    boolean hasCycles
) {
    public DesanitizedRecipeApplicationPath {
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(usedResources, "usedResources cannot be null");
        Objects.requireNonNull(finalInventoryValues, "finalInventoryValues cannot be null");
        Objects.requireNonNull(missingResources, "missingResources cannot be null");
        Objects.requireNonNull(relevantResourceKeys, "relevantResourceKeys cannot be null");
        Objects.requireNonNull(peakResourceUsage, "peakResourceUsage cannot be null");
        steps = List.copyOf(steps);
        usedResources = Map.copyOf(new LinkedHashMap<>(usedResources));
        finalInventoryValues = Map.copyOf(new LinkedHashMap<>(finalInventoryValues));
        missingResources = Map.copyOf(new LinkedHashMap<>(missingResources));
        relevantResourceKeys = List.copyOf(relevantResourceKeys);
        peakResourceUsage = Map.copyOf(new LinkedHashMap<>(peakResourceUsage));
    }
}
