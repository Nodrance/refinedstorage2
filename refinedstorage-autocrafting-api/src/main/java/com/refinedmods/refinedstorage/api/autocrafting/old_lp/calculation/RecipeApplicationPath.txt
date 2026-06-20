package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import java.util.List;
import java.util.Objects;

// Augments a set of recipe applications with the actual steps to apply them in, in order.
// If the RecipeApplicationSet has missing resources, this represents what the crafter would do if they weren't missing
// If the the item is craftable, this represents what the crafter will actually do.
public record RecipeApplicationPath(
    RecipeApplicationSet applicationSet,
    List<RecipeApplicationStep> steps,
    ResourcePool peakResourceUsage,
    boolean hasCycles
) {
    public RecipeApplicationPath {
        Objects.requireNonNull(applicationSet, "applicationSet cannot be null");
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(peakResourceUsage, "peakResourceUsage cannot be null");
        steps = List.copyOf(steps);
        peakResourceUsage = peakResourceUsage.copy();
    }
}

