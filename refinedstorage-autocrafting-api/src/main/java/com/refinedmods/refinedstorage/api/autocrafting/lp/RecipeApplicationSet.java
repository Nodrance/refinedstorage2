package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Describes the result of an LP solution. Tells you how many times to apply recipes,
// and tracks input resources, final inventory, and missing resources.
// Used resources represent how much the inventory will drop by if this recipe is used
// finalInventoryValues represent what the inventory will look like after using the recipe
// Missing resources describe what you'd need to add to make it craftable
public record RecipeApplicationSet(
    List<SanitizedRecipe> recipes,
    Map<SanitizedRecipe, Long> recipeValues,
    ResourcePool usedResources,
    ResourcePool finalInventoryValues,
    ResourcePool missingResources,
    List<MultiResourceKey> relevantResourceKeys
) {
    public RecipeApplicationSet {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(recipeValues, "recipeValues cannot be null");
        Objects.requireNonNull(usedResources, "usedResources cannot be null");
        Objects.requireNonNull(finalInventoryValues, "finalInventoryValues cannot be null");
        Objects.requireNonNull(missingResources, "missingResources cannot be null");
        Objects.requireNonNull(relevantResourceKeys, "relevantResourceKeys cannot be null");
        recipes = List.copyOf(recipes);
        recipeValues = Map.copyOf(new LinkedHashMap<>(recipeValues));
        relevantResourceKeys = List.copyOf(relevantResourceKeys);
    }
}
