package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.List;
import java.util.Objects;

/**
 * Describes a list of concrete recipe applications. This is the "path" that the crafting system will take
 * to get from the initial inventory to the desired output.
 * Equivalent to old_lp's LpStepPlan.
 */
public record RecipeApplicationPath(
    RecipeApplicationSet applicationSet,
    List<RecipeApplicationStep> steps
) {
    public RecipeApplicationPath {
        Objects.requireNonNull(applicationSet, "applicationSet cannot be null");
        Objects.requireNonNull(steps, "steps cannot be null");
        steps = List.copyOf(steps);
    }
}

