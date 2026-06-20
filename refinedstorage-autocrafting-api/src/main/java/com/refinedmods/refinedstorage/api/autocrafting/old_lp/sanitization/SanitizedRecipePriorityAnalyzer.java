package com.refinedmods.refinedstorage.api.autocrafting.lp.sanitization;

import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.MultiResourceKey;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.ResourcePool;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.SanitizedRecipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SanitizedRecipePriorityAnalyzer {
    private SanitizedRecipePriorityAnalyzer() {
    }

    public static List<SanitizedRecipe> applyEffectivePriorities(
        final List<SanitizedRecipe> recipes,
        final ResourcePool target
    ) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(target, "target cannot be null");

        final TraversalState state = initializeTraversalState(target);
        propagatePriorities(recipes, state);
        return buildPrioritizedRecipes(recipes, state.bestRecipePriorities());
    }

    public static List<SanitizedRecipe> selectTopPriorityRecipesPerOutputResource(final List<SanitizedRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes cannot be null");

        final List<SanitizedRecipe> sorted = recipes.stream()
            .sorted(Comparator
                .comparingLong(SanitizedRecipe::priority)
                .reversed()
                .thenComparingLong(SanitizedRecipe::insertionOrder)
                .thenComparing(SanitizedRecipe::recipeId))
            .toList();

        final Set<MultiResourceKey> seenOutputResources = new LinkedHashSet<>();
        final Set<UUID> selectedRecipeIds = new LinkedHashSet<>();
        for (final SanitizedRecipe recipe : sorted) {
            boolean producesNewResource = false;
            for (final MultiResourceKey resource : recipe.output().resourceKeys()) {
                if (!seenOutputResources.contains(resource)) {
                    producesNewResource = true;
                    break;
                }
            }
            if (!producesNewResource) {
                continue;
            }
            selectedRecipeIds.add(recipe.recipeId());
            seenOutputResources.addAll(recipe.output().resourceKeys());
        }

        final List<SanitizedRecipe> selected = new ArrayList<>();
        for (final SanitizedRecipe recipe : sorted) {
            if (selectedRecipeIds.contains(recipe.recipeId())) {
                selected.add(recipe);
            }
        }
        return selected;
    }

    private static TraversalState initializeTraversalState(final ResourcePool target) {
        final Map<MultiResourceKey, PriorityKey> bestResourcePriorities = new LinkedHashMap<>();
        final Map<UUID, PriorityKey> bestRecipePriorities = new LinkedHashMap<>();
        final Deque<ResourcePriorityEntry> queue = new ArrayDeque<>();
        final PriorityKey basePriority = new PriorityKey();
        for (final MultiResourceKey targetResource : target.resourceKeys()) {
            bestResourcePriorities.put(targetResource, basePriority);
            queue.addLast(new ResourcePriorityEntry(targetResource, basePriority));
        }
        return new TraversalState(bestResourcePriorities, bestRecipePriorities, queue);
    }

    private static void propagatePriorities(final List<SanitizedRecipe> recipes, final TraversalState state) {
        while (!state.queue().isEmpty()) {
            final ResourcePriorityEntry outputEntry = state.queue().removeLast();
            applyOutputPriorityToRecipes(recipes, outputEntry, state);
        }
    }

    private static void applyOutputPriorityToRecipes(
        final List<SanitizedRecipe> recipes,
        final ResourcePriorityEntry outputEntry,
        final TraversalState state
    ) {
        for (final SanitizedRecipe recipe : recipes) {
            if (!produces(recipe, outputEntry.resource())) {
                continue;
            }

            final PriorityKey candidateRecipePriority = outputEntry.priority().appendRecipePriority(recipe);
            if (!tryUpdateBestRecipePriority(
                recipe.recipeId(),
                candidateRecipePriority,
                state.bestRecipePriorities()
            )) {
                continue;
            }
            pushImprovedInputPriorities(recipe, candidateRecipePriority, state);
        }
    }

    private static boolean tryUpdateBestRecipePriority(
        final UUID recipeId,
        final PriorityKey candidatePriority,
        final Map<UUID, PriorityKey> bestRecipePriorities
    ) {
        final PriorityKey currentPriority = bestRecipePriorities.get(recipeId);
        if (currentPriority != null && candidatePriority.compareTo(currentPriority) >= 0) {
            return false;
        }
        bestRecipePriorities.put(recipeId, candidatePriority);
        return true;
    }

    private static void pushImprovedInputPriorities(
        final SanitizedRecipe recipe,
        final PriorityKey candidateRecipePriority,
        final TraversalState state
    ) {
        for (final MultiResourceKey inputResource : recipe.input().resourceKeys()) {
            final PriorityKey currentInputPriority = state.bestResourcePriorities().get(inputResource);
            if (currentInputPriority != null && candidateRecipePriority.compareTo(currentInputPriority) >= 0) {
                continue;
            }
            state.bestResourcePriorities().put(inputResource, candidateRecipePriority);
            state.queue().addLast(new ResourcePriorityEntry(inputResource, candidateRecipePriority));
        }
    }

    private static List<SanitizedRecipe> buildPrioritizedRecipes(
        final List<SanitizedRecipe> recipes,
        final Map<UUID, PriorityKey> bestRecipePriorities
    ) {
        final List<RecipePriorityEntry> prunedRecipesWithPriority = collectPrunedRecipesWithPriority(
            recipes,
            bestRecipePriorities
        );
        final List<SanitizedRecipe> updatedRecipes = assignEffectivePriorities(prunedRecipesWithPriority, recipes);
        return mapEntriesToRecipes(prunedRecipesWithPriority, updatedRecipes);
    }

    private static List<RecipePriorityEntry> collectPrunedRecipesWithPriority(
        final List<SanitizedRecipe> recipes,
        final Map<UUID, PriorityKey> bestRecipePriorities
    ) {
        final Map<UUID, SanitizedRecipe> recipeById = new HashMap<>();
        for (final SanitizedRecipe recipe : recipes) {
            recipeById.put(recipe.recipeId(), recipe);
        }

        final List<RecipePriorityEntry> entries = new ArrayList<>();
        for (final SanitizedRecipe recipe : recipes) {
            final PriorityKey priority = bestRecipePriorities.get(recipe.recipeId());
            if (priority != null) {
                entries.add(new RecipePriorityEntry(recipe.recipeId(), priority));
            }
        }
        entries.sort(
            Comparator.comparing(RecipePriorityEntry::priority)
                .thenComparingLong(entry -> recipeById.get(entry.recipeId()).insertionOrder())
                .thenComparing(RecipePriorityEntry::recipeId)
        );
        return entries;
    }

    private static List<SanitizedRecipe> assignEffectivePriorities(
        final List<RecipePriorityEntry> sortedEntries,
        final List<SanitizedRecipe> recipes
    ) {
        final Map<UUID, Long> effectivePriorities = new LinkedHashMap<>();
        for (int index = 0; index < sortedEntries.size(); index++) {
            final RecipePriorityEntry recipeAndPriority = sortedEntries.get(index);
            effectivePriorities.put(recipeAndPriority.recipeId(), (long) (sortedEntries.size() - 1 - index));
        }

        final List<SanitizedRecipe> result = new ArrayList<>(recipes.size());
        for (final SanitizedRecipe recipe : recipes) {
            final Long effectivePriority = effectivePriorities.get(recipe.recipeId());
            if (effectivePriority != null) {
                result.add(new SanitizedRecipe(
                    recipe.recipeId(),
                    recipe.sourcePatternId(),
                    recipe.input(),
                    recipe.output(),
                    effectivePriority,
                    recipe.insertionOrder()
                ));
            } else {
                result.add(recipe);
            }
        }
        return result;
    }

    private static List<SanitizedRecipe> mapEntriesToRecipes(
        final List<RecipePriorityEntry> sortedEntries,
        final List<SanitizedRecipe> recipes
    ) {
        final Map<UUID, SanitizedRecipe> byId = new LinkedHashMap<>();
        for (final SanitizedRecipe recipe : recipes) {
            byId.put(recipe.recipeId(), recipe);
        }
        final List<SanitizedRecipe> prioritizedRecipes = new ArrayList<>(sortedEntries.size());
        for (final RecipePriorityEntry entry : sortedEntries) {
            prioritizedRecipes.add(byId.get(entry.recipeId()));
        }
        return prioritizedRecipes;
    }

    private static boolean produces(final SanitizedRecipe recipe, final MultiResourceKey resource) {
        return recipe.output().getAmount(resource) > 0;
    }

    private record ResourcePriorityEntry(MultiResourceKey resource, PriorityKey priority) {
    }

    private record TraversalState(
        Map<MultiResourceKey, PriorityKey> bestResourcePriorities,
        Map<UUID, PriorityKey> bestRecipePriorities,
        Deque<ResourcePriorityEntry> queue
    ) {
    }

    private record RecipePriorityEntry(UUID recipeId, PriorityKey priority) {
    }

    private static final class PriorityKey implements Comparable<PriorityKey> {
        private final List<Long> values;

        private PriorityKey() {
            this.values = List.of();
        }

        private PriorityKey(final List<Long> values) {
            this.values = List.copyOf(values);
        }

        private PriorityKey appendRecipePriority(final SanitizedRecipe recipe) {
            final List<Long> copy = new ArrayList<>(values);
            copy.add(recipe.priority());
            return new PriorityKey(copy);
        }

        @Override
        public int compareTo(final PriorityKey other) {
            final int sharedSize = Math.min(values.size(), other.values.size());
            for (int index = 0; index < sharedSize; index++) {
                final long left = values.get(index);
                final long right = other.values.get(index);
                if (left != right) {
                    return Long.compare(right, left);
                }
            }
            return Integer.compare(values.size(), other.values.size());
        }
    }
}
