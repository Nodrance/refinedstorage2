package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import java.util.List;
import java.util.Objects;

public record CraftingSolution(
    CraftingApplication application,
    List<CraftingStep> steps,
    ResourcePool peakResourceUsage,
    boolean hasCycles
) {
    public CraftingSolution {
        Objects.requireNonNull(application, "application cannot be null");
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(peakResourceUsage, "peakResourceUsage cannot be null");
        steps = List.copyOf(steps);
        peakResourceUsage = peakResourceUsage.copy();
    }
}
