package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Augments a fully desanitized set of recipe applications with the actual steps to apply them in, in order.
// All resources, recipes, and resource pools have been fully desanitized.
// If the DesanitizedRecipeApplicationSet has missing resources, this represents what the crafter would do if they weren't missing.
// If the item is craftable, this represents what the crafter will actually do.
public record DesanitizedRecipeApplicationPath(
    DesanitizedRecipeApplicationSet applicationSet,
    List<DesanitizedRecipeApplicationStep> steps,
    Map<ResourceKey, Long> peakResourceUsage,
    boolean hasCycles
) {
    public DesanitizedRecipeApplicationPath {
        Objects.requireNonNull(applicationSet, "applicationSet cannot be null");
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(peakResourceUsage, "peakResourceUsage cannot be null");
        steps = List.copyOf(steps);
        peakResourceUsage = Map.copyOf(new LinkedHashMap<>(peakResourceUsage));
    }
}
