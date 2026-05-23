package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CraftingApplication(
    CraftingProblem problem,
    Map<SanitizedRecipe, Long> recipeValues,
    ResourcePool usedResources,
    ResourcePool finalInventoryValues,
    ResourcePool missingResources
) {
    public CraftingApplication {
        Objects.requireNonNull(problem, "problem cannot be null");
        Objects.requireNonNull(recipeValues, "recipeValues cannot be null");
        Objects.requireNonNull(usedResources, "usedResources cannot be null");
        Objects.requireNonNull(finalInventoryValues, "finalInventoryValues cannot be null");
        Objects.requireNonNull(missingResources, "missingResources cannot be null");
        recipeValues = Map.copyOf(new LinkedHashMap<>(recipeValues));
    }

    public List<SanitizedRecipe> recipes() {
        return problem.sanitizedRecipes();
    }

    public List<MultiResourceKey> relevantResourceKeys() {
        return problem.relevantResourceKeys().stream()
            .sorted(java.util.Comparator.comparing(Object::toString))
            .toList();
    }
}
