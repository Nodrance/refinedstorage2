package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LpExecutionPlanner {
    private LpExecutionPlanner() {
    }

    public static Optional<List<LpExecutionPlanStep>> buildExecutablePlanFromRecipeUsage(
        final List<LpPatternRecipe> recipes,
        final Map<UUID, Long> recipeValues,
        final LpResourceSet startingResources
    ) {
        return buildExecutablePlanFromRecipeUsage(recipes, recipeValues, startingResources, CancellationToken.NONE);
    }

    public static Optional<List<LpExecutionPlanStep>> buildExecutablePlanFromRecipeUsage(
        final List<LpPatternRecipe> recipes,
        final Map<UUID, Long> recipeValues,
        final LpResourceSet startingResources,
        final CancellationToken cancellationToken
    ) {
        validateInputs(recipes, recipeValues, startingResources);
        validateCancellationToken(cancellationToken);
        throwIfCancelled(cancellationToken);

        final Map<UUID, Long> remainingCounts = new LinkedHashMap<>();
        for (final LpPatternRecipe recipe : recipes) {
            throwIfCancelled(cancellationToken);
            final long rawValue = recipeValues.getOrDefault(recipe.uniqueId(), 0L);
            if (rawValue < 0) {
                throw new IllegalArgumentException(
                    "Negative usage count for recipe: " + recipe.description()
                );
            }
            if (rawValue > 0) {
                remainingCounts.put(recipe.uniqueId(), rawValue);
            }
        }

        final LpRecipeAnalysis.CycleDetectionResult cycleDetectionResult =
            LpRecipeAnalysis.detectRecipeCycles(recipes);
        final Map<UUID, Boolean> inLoopById = cycleDetectionResult.inLoopByRecipeId();

        final LpResourceSet inventory = startingResources.copy();
        final long totalRemaining = remainingCounts.values()
            .stream()
            .mapToLong(Long::longValue)
            .sum();
        final List<LpExecutionPlanStep> plan = new ArrayList<>();

        final boolean success = recursivelyBacksolvePlan(
            recipes,
            inLoopById,
            remainingCounts,
            inventory,
            totalRemaining,
            plan,
            cancellationToken
        );
        return success ? Optional.of(List.copyOf(plan)) : Optional.empty();
    }

    public static List<LpExecutionPlanStep> buildRecipeApplicationPlanFromRecipeUsage(
        final List<LpPatternRecipe> recipes,
        final Map<UUID, Long> recipeValues,
        final LpResourceSet startingResources
    ) {
        return buildRecipeApplicationPlanFromRecipeUsage(
            recipes,
            recipeValues,
            startingResources,
            CancellationToken.NONE
        );
    }

    public static List<LpExecutionPlanStep> buildRecipeApplicationPlanFromRecipeUsage(
        final List<LpPatternRecipe> recipes,
        final Map<UUID, Long> recipeValues,
        final LpResourceSet startingResources,
        final CancellationToken cancellationToken
    ) {
        validateInputs(recipes, recipeValues, startingResources);
        validateCancellationToken(cancellationToken);
        throwIfCancelled(cancellationToken);

        final Optional<List<LpExecutionPlanStep>> executablePlan = buildExecutablePlanFromRecipeUsage(
            recipes,
            recipeValues,
            startingResources,
            cancellationToken
        );
        if (executablePlan.isPresent()) {
            return executablePlan.get();
        }

        final List<LpExecutionPlanStep> steps = new ArrayList<>();
        for (final LpPatternRecipe recipe : recipes) {
            throwIfCancelled(cancellationToken);
            final long rawValue = recipeValues.getOrDefault(recipe.uniqueId(), 0L);
            if (rawValue < 0) {
                throw new IllegalArgumentException(
                    "Negative usage count for recipe: " + recipe.description()
                );
            }
            if (rawValue > 0) {
                steps.add(new LpExecutionPlanStep(recipe, rawValue));
            }
        }
        return List.copyOf(steps);
    }

    private static boolean recursivelyBacksolvePlan(final List<LpPatternRecipe> recipes,
                                                    final Map<UUID, Boolean> inLoopById,
                                                    final Map<UUID, Long> remainingCounts,
                                                    final LpResourceSet inventory,
                                                    final long totalRemaining,
                                                    final List<LpExecutionPlanStep> plan,
                                                    final CancellationToken cancellationToken) {
        throwIfCancelled(cancellationToken);
        if (totalRemaining == 0) {
            return true;
        }

        final List<Candidate> candidates = buildCandidates(
            recipes,
            inLoopById,
            remainingCounts,
            inventory,
            cancellationToken
        );

        if (candidates.isEmpty()) {
            throwIfCancelled(cancellationToken);
            return false;
        }

        for (final Candidate candidate : candidates) {
            throwIfCancelled(cancellationToken);
            for (final long batch : buildBatchAttempts(candidate)) {
                throwIfCancelled(cancellationToken);
                if (tryCandidateBatch(
                    recipes,
                    inLoopById,
                    remainingCounts,
                    inventory,
                    totalRemaining,
                    plan,
                    candidate,
                    batch,
                    cancellationToken
                )) {
                    return true;
                }
            }
        }

        return false;
    }

    private static List<Candidate> buildCandidates(final List<LpPatternRecipe> recipes,
                                                   final Map<UUID, Boolean> inLoopById,
                                                   final Map<UUID, Long> remainingCounts,
                                                   final LpResourceSet inventory,
                                                   final CancellationToken cancellationToken) {
        final List<Candidate> candidates = new ArrayList<>();
        for (final LpPatternRecipe recipe : recipes) {
            throwIfCancelled(cancellationToken);
            final Candidate candidate = toCandidate(recipe, remainingCounts, inventory, cancellationToken);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator
            .comparing((Candidate candidate) -> inLoopById.getOrDefault(
                candidate.recipe.uniqueId(),
                false
            ))
            .reversed()
            .thenComparing(Candidate::maxBatch, Comparator.reverseOrder())
            .thenComparing(Candidate::remaining, Comparator.reverseOrder())
            .thenComparing(
                Comparator.comparingInt((Candidate candidate) -> candidate.recipe.effectivePriority() == null
                    ? Integer.MIN_VALUE
                    : candidate.recipe.effectivePriority()).reversed()
            )
            .thenComparing(candidate -> candidate.recipe.uniqueId()));
        return List.copyOf(candidates);
    }

    private static Candidate toCandidate(final LpPatternRecipe recipe,
                                         final Map<UUID, Long> remainingCounts,
                                         final LpResourceSet inventory,
                                         final CancellationToken cancellationToken) {
        throwIfCancelled(cancellationToken);
        final long remaining = remainingCounts.getOrDefault(recipe.uniqueId(), 0L);
        if (remaining <= 0) {
            return null;
        }
        final long maxBatch = Math.min(remaining, computeMaxAffordableBatch(recipe, inventory, cancellationToken));
        if (maxBatch <= 0) {
            return null;
        }
        return new Candidate(recipe, remaining, maxBatch);
    }

    private static List<Long> buildBatchAttempts(final Candidate candidate) {
        final List<Long> tryBatches = new ArrayList<>();
        tryBatches.add(candidate.maxBatch);
        if (candidate.maxBatch > 1) {
            tryBatches.add(1L);
        }
        if (candidate.maxBatch > 2) {
            tryBatches.add(candidate.maxBatch / 2L);
        }
        tryBatches.sort(Comparator.reverseOrder());
        return tryBatches.stream().distinct().toList();
    }

    private static boolean tryCandidateBatch(final List<LpPatternRecipe> recipes,
                                             final Map<UUID, Boolean> inLoopById,
                                             final Map<UUID, Long> remainingCounts,
                                             final LpResourceSet inventory,
                                             final long totalRemaining,
                                             final List<LpExecutionPlanStep> plan,
                                             final Candidate candidate,
                                             final long batch,
                                             final CancellationToken cancellationToken) {
        throwIfCancelled(cancellationToken);
        if (batch <= 0 || batch > candidate.remaining) {
            return false;
        }

        applyRecipeBatch(candidate.recipe, batch, inventory);
        remainingCounts.put(candidate.recipe.uniqueId(), candidate.remaining - batch);
        appendOrMergePlanStep(plan, candidate.recipe, batch, inLoopById);

        final boolean success = recursivelyBacksolvePlan(
            recipes,
            inLoopById,
            remainingCounts,
            inventory,
            totalRemaining - batch,
            plan,
            cancellationToken
        );
        if (success) {
            return true;
        }
        removeOrShrinkLastPlanStep(plan, candidate.recipe, batch);
        remainingCounts.put(candidate.recipe.uniqueId(), candidate.remaining);
        rollbackRecipeBatch(candidate.recipe, batch, inventory);
        return false;
    }

    private static long computeMaxAffordableBatch(final LpPatternRecipe recipe, final LpResourceSet inventory) {
        return computeMaxAffordableBatch(recipe, inventory, CancellationToken.NONE);
    }

    private static long computeMaxAffordableBatch(final LpPatternRecipe recipe,
                                                  final LpResourceSet inventory,
                                                  final CancellationToken cancellationToken) {
        long maxBatch = Long.MAX_VALUE;
        for (final Map.Entry<ResourceKey, Long> entry : recipe.input()) {
            throwIfCancelled(cancellationToken);
            final long inputCount = entry.getValue();
            if (inputCount <= 0) {
                continue;
            }
            final long available = inventory.getAmount(entry.getKey());
            maxBatch = Math.min(maxBatch, available / inputCount);
        }
        return maxBatch;
    }

    private static void applyRecipeBatch(final LpPatternRecipe recipe,
                                         final long batch,
                                         final LpResourceSet inventory) {
        // Applies a recipe batch to the inventory, subtracting inputs and adding outputs.
        for (final Map.Entry<ResourceKey, Long> entry : recipe.input()) {
            inventory.subtractAmount(entry.getKey(), entry.getValue() * batch);
        }
        for (final Map.Entry<ResourceKey, Long> entry : recipe.output()) {
            inventory.addAmount(entry.getKey(), entry.getValue() * batch);
        }
    }

    private static void rollbackRecipeBatch(final LpPatternRecipe recipe,
                                            final long batch,
                                            final LpResourceSet inventory) {
        // Rolls back a recipe batch in the inventory, adding inputs and subtracting outputs.
        // Exact inverse of applyRecipeBatch
        for (final Map.Entry<ResourceKey, Long> entry : recipe.output()) {
            inventory.subtractAmount(entry.getKey(), entry.getValue() * batch);
        }
        for (final Map.Entry<ResourceKey, Long> entry : recipe.input()) {
            inventory.addAmount(entry.getKey(), entry.getValue() * batch);
        }
    }

    private static void appendOrMergePlanStep(final List<LpExecutionPlanStep> plan,
                                              final LpPatternRecipe recipe,
                                              final long batch,
                                              final Map<UUID, Boolean> inLoopById) {
        // Appends a new plan step or merges it with the last step if it's the same recipe.
        // Cycle recipes are never merged so their ordered steps are preserved.
        final boolean inLoop = inLoopById.getOrDefault(recipe.uniqueId(), false);
        if (!inLoop && !plan.isEmpty()) {
            final LpExecutionPlanStep last = plan.getLast();
            if (last.recipe().uniqueId().equals(recipe.uniqueId())) {
                plan.set(plan.size() - 1, new LpExecutionPlanStep(recipe, last.iterations() + batch));
                return;
            }
        }
        plan.add(new LpExecutionPlanStep(recipe, batch));
    }

    private static void removeOrShrinkLastPlanStep(final List<LpExecutionPlanStep> plan,
                                                   final LpPatternRecipe recipe,
                                                   final long batch) {
        // Removes or shrinks the last plan step if it matches the given recipe and batch size.
        // Exact inverse of appendOrMergePlanStep
        if (plan.isEmpty()) {
            return;
        }
        final LpExecutionPlanStep last = plan.getLast();
        if (!last.recipe().uniqueId().equals(recipe.uniqueId())) {
            return;
        }
        if (last.iterations() == batch) {
            plan.removeLast();
            return;
        }
        plan.set(plan.size() - 1, new LpExecutionPlanStep(recipe, last.iterations() - batch));
    }

    private static void validateInputs(final List<LpPatternRecipe> recipes,
                                       final Map<UUID, Long> recipeValues,
                                       final LpResourceSet startingResources) {
        Objects.requireNonNull(recipes, "recipes cannot be null");
        Objects.requireNonNull(recipeValues, "recipeValues cannot be null");
        Objects.requireNonNull(startingResources, "startingResources cannot be null");
    }

    private static void validateCancellationToken(final CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
    }

    private static void throwIfCancelled(final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP execution planner cancelled");
        }
    }

    private record Candidate(LpPatternRecipe recipe, long remaining, long maxBatch) {
    }
}
