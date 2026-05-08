package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.List;
import java.util.Objects;

// Augments a fully desanitized set of recipe applications with the actual steps to apply them in, in order.
// All resources, recipes, and resource pools have been fully desanitized.
// If the DesanitizedRecipeApplicationSet has missing resources, this represents what the crafter would do if they weren't missing.
// If the item is craftable, this represents what the crafter will actually do.
public record DesanitizedRecipeApplicationPath(
    DesanitizedRecipeApplicationSet applicationSet,
    List<DesanitizedRecipeApplicationStep> steps
) {
    public DesanitizedRecipeApplicationPath {
        Objects.requireNonNull(applicationSet, "applicationSet cannot be null");
        Objects.requireNonNull(steps, "steps cannot be null");
        steps = List.copyOf(steps);
    }
}
