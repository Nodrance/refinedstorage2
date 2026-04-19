package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ExecutionPlanner {
    private ExecutionPlanner() {
    }

    public static Optional<RecipeApplicationPath> buildRecipeApplicationPathFromApplicationSet(
        final RecipeApplicationSet applicationSet,
        final ResourcePool startingResources
    ) {
        return buildRecipeApplicationPathFromApplicationSet(
            applicationSet,
            startingResources,
            CancellationToken.NONE
        );
    }

    public static Optional<RecipeApplicationPath> buildRecipeApplicationPathFromApplicationSet(
        final RecipeApplicationSet applicationSet,
        final ResourcePool startingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(applicationSet, "applicationSet cannot be null");
        Objects.requireNonNull(startingResources, "startingResources cannot be null");
        validateCancellationToken(cancellationToken);
        throwIfCancelled(cancellationToken);

        final Map<UUID, Long> recipeValues = new LinkedHashMap<>();
        for (final Map.Entry<SanitizedRecipe, Long> entry : applicationSet.recipeValues().entrySet()) {
            throwIfCancelled(cancellationToken);
            final long value = entry.getValue() == null ? 0L : entry.getValue();
            if (value < 0L) {
                throw new IllegalArgumentException("Negative usage count for recipe: " + entry.getKey().recipeId());
            }
            if (value > 0L) {
                recipeValues.put(entry.getKey().recipeId(), value);
            }
        }

        final ResourcePool availableForPlanning = startingResources.copy();
        final ResourcePool missingResourceAllowance = applicationSet.missingResources().copy();
        availableForPlanning.addAll(missingResourceAllowance);

        final Optional<List<RecipeApplicationStep>> executablePlan;
        try {
            executablePlan = buildExecutableSteps(
                applicationSet.recipes(),
                recipeValues,
                availableForPlanning,
                cancellationToken
            );
        } finally {
            availableForPlanning.subtractAll(missingResourceAllowance);
        }

        final List<RecipeApplicationStep> steps;
        if (executablePlan.isPresent()) {
            steps = executablePlan.get();
        } else if (!applicationSet.missingResources().isEmpty()) {
            steps = buildFallbackSteps(applicationSet.recipes(), recipeValues, cancellationToken);
        } else {
            return Optional.empty();
        }

        return Optional.of(new RecipeApplicationPath(applicationSet, steps));
    }

    private static Optional<List<RecipeApplicationStep>> buildExecutableSteps(
        final List<SanitizedRecipe> recipes,
        final Map<UUID, Long> recipeValues,
        final ResourcePool startingResources,
        final CancellationToken cancellationToken
    ) {
        validateInputs(recipes, recipeValues, startingResources);
        validateCancellationToken(cancellationToken);
        throwIfCancelled(cancellationToken);

        final Map<UUID, Long> remainingCounts = new LinkedHashMap<>();
        for (final SanitizedRecipe recipe : recipes) {
            throwIfCancelled(cancellationToken);
            final long rawValue = recipeValues.getOrDefault(recipe.recipeId(), 0L);
            if (rawValue < 0L) {
                throw new IllegalArgumentException("Negative usage count for recipe: " + recipe.recipeId());
            }
            if (rawValue > 0L) {
                remainingCounts.put(recipe.recipeId(), rawValue);
            }
        }

        final RecipeAnalyzer.CycleDetectionResult cycleDetectionResult = RecipeAnalyzer.detectRecipeCycles(recipes);
        final Map<UUID, Boolean> inLoopById = cycleDetectionResult.inLoopByRecipeId();

        final ResourcePool inventory = startingResources.copy();
        final long totalRemaining = remainingCounts.values().stream().mapToLong(Long::longValue).sum();
        final List<RecipeApplicationStep> plan = new ArrayList<>();

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

    private static List<RecipeApplicationStep> buildFallbackSteps(
        final List<SanitizedRecipe> recipes,
        final Map<UUID, Long> recipeValues,
        final CancellationToken cancellationToken
    ) {
        final List<RecipeApplicationStep> steps = new ArrayList<>();
        for (final SanitizedRecipe recipe : recipes) {
            throwIfCancelled(cancellationToken);
            final long rawValue = recipeValues.getOrDefault(recipe.recipeId(), 0L);
            if (rawValue < 0L) {
                throw new IllegalArgumentException("Negative usage count for recipe: " + recipe.recipeId());
            }
            if (rawValue > 0L) {
                steps.add(new RecipeApplicationStep(recipe, rawValue));
            }
        }
        return List.copyOf(steps);
    }

    private static boolean recursivelyBacksolvePlan(
        final List<SanitizedRecipe> recipes,
        final Map<UUID, Boolean> inLoopById,
        final Map<UUID, Long> remainingCounts,
        final ResourcePool inventory,
        final long totalRemaining,
        final List<RecipeApplicationStep> plan,
        final CancellationToken cancellationToken
    ) {
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

    private static List<Candidate> buildCandidates(
        final List<SanitizedRecipe> recipes,
        final Map<UUID, Boolean> inLoopById,
        final Map<UUID, Long> remainingCounts,
        final ResourcePool inventory,
        final CancellationToken cancellationToken
    ) {
        final List<Candidate> candidates = new ArrayList<>();
        for (final SanitizedRecipe recipe : recipes) {
            throwIfCancelled(cancellationToken);
            final Candidate candidate = toCandidate(recipe, remainingCounts, inventory, cancellationToken);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator
            .comparing((Candidate candidate) -> inLoopById.getOrDefault(candidate.recipe.recipeId(), false))
            .reversed()
            .thenComparing(Candidate::maxBatch, Comparator.reverseOrder())
            .thenComparing(Candidate::remaining, Comparator.reverseOrder())
            .thenComparing((Candidate candidate) -> candidate.recipe.priority(), Comparator.reverseOrder())
            .thenComparing(candidate -> candidate.recipe.recipeId()));
        return List.copyOf(candidates);
    }

    private static Candidate toCandidate(
        final SanitizedRecipe recipe,
        final Map<UUID, Long> remainingCounts,
        final ResourcePool inventory,
        final CancellationToken cancellationToken
    ) {
        throwIfCancelled(cancellationToken);
        final long remaining = remainingCounts.getOrDefault(recipe.recipeId(), 0L);
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

    private static boolean tryCandidateBatch(
        final List<SanitizedRecipe> recipes,
        final Map<UUID, Boolean> inLoopById,
        final Map<UUID, Long> remainingCounts,
        final ResourcePool inventory,
        final long totalRemaining,
        final List<RecipeApplicationStep> plan,
        final Candidate candidate,
        final long batch,
        final CancellationToken cancellationToken
    ) {
        throwIfCancelled(cancellationToken);
        if (batch <= 0 || batch > candidate.remaining) {
            return false;
        }

        applyRecipeBatch(candidate.recipe, batch, inventory);
        remainingCounts.put(candidate.recipe.recipeId(), candidate.remaining - batch);
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
        remainingCounts.put(candidate.recipe.recipeId(), candidate.remaining);
        rollbackRecipeBatch(candidate.recipe, batch, inventory);
        return false;
    }

    private static long computeMaxAffordableBatch(
        final SanitizedRecipe recipe,
        final ResourcePool inventory,
        final CancellationToken cancellationToken
    ) {
        long maxBatch = Long.MAX_VALUE;
        for (final Map.Entry<MultiResourceKey, Long> entry : recipe.input()) {
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

    private static void applyRecipeBatch(final SanitizedRecipe recipe, final long batch, final ResourcePool inventory) {
        for (final Map.Entry<MultiResourceKey, Long> entry : recipe.input()) {
            inventory.subtractAmount(entry.getKey(), entry.getValue() * batch);
        }
        for (final Map.Entry<MultiResourceKey, Long> entry : recipe.output()) {
            inventory.addAmount(entry.getKey(), entry.getValue() * batch);
        }
    }

    private static void rollbackRecipeBatch(
        final SanitizedRecipe recipe,
        final long batch,
        final ResourcePool inventory
    ) {
        for (final Map.Entry<MultiResourceKey, Long> entry : recipe.output()) {
            inventory.subtractAmount(entry.getKey(), entry.getValue() * batch);
        }
        for (final Map.Entry<MultiResourceKey, Long> entry : recipe.input()) {
            inventory.addAmount(entry.getKey(), entry.getValue() * batch);
        }
    }

    private static void appendOrMergePlanStep(
        final List<RecipeApplicationStep> plan,
        final SanitizedRecipe recipe,
        final long batch,
        final Map<UUID, Boolean> inLoopById
    ) {
        final boolean inLoop = inLoopById.getOrDefault(recipe.recipeId(), false);
        if (!inLoop && !plan.isEmpty()) {
            final RecipeApplicationStep last = plan.get(plan.size() - 1);
            if (last.recipe().recipeId().equals(recipe.recipeId())) {
                plan.set(plan.size() - 1, new RecipeApplicationStep(recipe, last.timesApplied() + batch));
                return;
            }
        }
        plan.add(new RecipeApplicationStep(recipe, batch));
    }

    private static void removeOrShrinkLastPlanStep(
        final List<RecipeApplicationStep> plan,
        final SanitizedRecipe recipe,
        final long batch
    ) {
        if (plan.isEmpty()) {
            return;
        }
        final RecipeApplicationStep last = plan.get(plan.size() - 1);
        if (!last.recipe().recipeId().equals(recipe.recipeId())) {
            return;
        }
        if (last.timesApplied() == batch) {
            plan.remove(plan.size() - 1);
            return;
        }
        plan.set(plan.size() - 1, new RecipeApplicationStep(recipe, last.timesApplied() - batch));
    }

    private static void validateInputs(
        final List<SanitizedRecipe> recipes,
        final Map<UUID, Long> recipeValues,
        final ResourcePool startingResources
    ) {
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

    private record Candidate(SanitizedRecipe recipe, long remaining, long maxBatch) {
    }
}
