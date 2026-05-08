package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Describes the fully desanitized result of an LP solution. All ResourcePools and MRKs have been
// converted to simple Map<ResourceKey, Long> entries.
// Tells you how many times to apply recipes and tracks input resources, final inventory, and missing resources.
// usedResources represent how much the inventory will drop by if these recipes are used
// finalInventoryValues represent what the inventory will look like after using the recipes
// missingResources describe what you'd need to add to make it craftable
public record DesanitizedRecipeApplicationSet(
    List<DesanitizedRecipe> recipes,
    Map<DesanitizedRecipe, Long> recipeValues,
    Map<ResourceKey, Long> usedResources,
    Map<ResourceKey, Long> finalInventoryValues,
    Map<ResourceKey, Long> missingResources,
    List<ResourceKey> relevantResourceKeys
) {
    public DesanitizedRecipeApplicationSet {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(recipeValues, "recipeValues cannot be null");
        Objects.requireNonNull(usedResources, "usedResources cannot be null");
        Objects.requireNonNull(finalInventoryValues, "finalInventoryValues cannot be null");
        Objects.requireNonNull(missingResources, "missingResources cannot be null");
        Objects.requireNonNull(relevantResourceKeys, "relevantResourceKeys cannot be null");
        recipes = List.copyOf(recipes);
        recipeValues = Map.copyOf(new LinkedHashMap<>(recipeValues));
        usedResources = Map.copyOf(usedResources);
        finalInventoryValues = Map.copyOf(finalInventoryValues);
        missingResources = Map.copyOf(missingResources);
        relevantResourceKeys = List.copyOf(relevantResourceKeys);
    }
}
