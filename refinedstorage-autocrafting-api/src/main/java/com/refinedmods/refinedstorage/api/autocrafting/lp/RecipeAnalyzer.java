package com.refinedmods.refinedstorage.api.autocrafting.lp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Deprecated(forRemoval = false)
public final class RecipeAnalyzer {
    private RecipeAnalyzer() {
    }

    public static List<MultiResourceKey> collectLeafResources(final List<SanitizedRecipe> recipes) {
        return SanitizedRecipeAnalyzer.collectLeafResources(recipes);
    }

    public static SnippedRecipes snipLoopsOnTargetBranches(
        final List<SanitizedRecipe> recipes,
        final ResourcePool target
    ) {
        final SanitizedRecipeAnalyzer.SnippedRecipes result = SanitizedRecipeAnalyzer.snipLoopsOnTargetBranches(
            recipes,
            target
        );
        return new SnippedRecipes(result.unsnipped(), result.snipped());
    }

    public static CycleDetectionResult detectRecipeCycles(final List<SanitizedRecipe> recipes) {
        final SanitizedRecipeAnalyzer.CycleDetectionResult result = SanitizedRecipeAnalyzer.detectRecipeCycles(recipes);
        return new CycleDetectionResult(result.inLoopByRecipeId(), result.cycles());
    }

    public static Set<MultiResourceKey> collectRelevantResourceKeys(final List<SanitizedRecipe> recipes) {
        return SanitizedRecipeAnalyzer.collectRelevantResourceKeys(recipes);
    }

    public static List<SanitizedRecipe> applyEffectivePriorities(
        final List<SanitizedRecipe> recipes,
        final ResourcePool target
    ) {
        return SanitizedRecipeAnalyzer.applyEffectivePriorities(recipes, target);
    }

    public static List<SanitizedRecipe> selectTopPriorityRecipesPerOutputResource(final List<SanitizedRecipe> recipes) {
        return SanitizedRecipeAnalyzer.selectTopPriorityRecipesPerOutputResource(recipes);
    }

    public static Set<MultiResourceKey> collectLoopEntryDeficitResourcesOnTargetBranches(
        final List<SanitizedRecipe> recipes,
        final ResourcePool target
    ) {
        return SanitizedRecipeAnalyzer.collectLoopEntryDeficitResourcesOnTargetBranches(recipes, target);
    }

    public record SnippedRecipes(List<SanitizedRecipe> unsnipped, List<SanitizedRecipe> snipped) {
        public SnippedRecipes {
            unsnipped = List.copyOf(unsnipped);
            snipped = List.copyOf(snipped);
        }
    }

    public record CycleDetectionResult(Map<UUID, Boolean> inLoopByRecipeId, List<List<UUID>> cycles) {
        public CycleDetectionResult {
            inLoopByRecipeId = Map.copyOf(inLoopByRecipeId);
            cycles = List.copyOf(cycles);
        }
    }
}
