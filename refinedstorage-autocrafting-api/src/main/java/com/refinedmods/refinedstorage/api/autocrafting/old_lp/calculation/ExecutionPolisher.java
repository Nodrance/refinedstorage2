package com.refinedmods.refinedstorage.api.autocrafting.lp.calculation;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExecutionPolisher {
    private ExecutionPolisher() {
    }

    public static CraftingSolution polish(
        final CraftingSolution solution,
        final CancellationToken cancellationToken
    ) {
        final List<CraftingStep> mergedSteps = mergeAdjacentLikeTerms(solution.steps());
        final boolean cyclesExist = hasRecipeCycles(mergedSteps);
        if (!cyclesExist) {
            return new CraftingSolution(solution.application(), List.copyOf(mergedSteps), ResourcePool.empty(), false);
        }

        final List<CraftingStep> reorderedSteps = reorderCyclicSteps(mergedSteps, cancellationToken);
        final List<CraftingStep> mergedReorderedSteps = mergeAdjacentLikeTerms(reorderedSteps);
        final ResourcePool peakResourceUsage = computePeakResourceUsage(mergedReorderedSteps, cancellationToken);
        return new CraftingSolution(solution.application(), mergedReorderedSteps, peakResourceUsage, true);
    }

    private static List<CraftingStep> mergeAdjacentLikeTerms(final List<CraftingStep> steps) {
        if (steps.isEmpty()) {
            return List.of();
        }

        final List<CraftingStep> merged = new ArrayList<>(steps.size());
        CraftingStep current = steps.getFirst();
        for (int index = 1; index < steps.size(); index++) {
            final CraftingStep next = steps.get(index);
            if (current.recipe().recipeId().equals(next.recipe().recipeId())) {
                current = new CraftingStep(current.recipe(), current.timesApplied() + next.timesApplied());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    private static boolean hasRecipeCycles(final List<CraftingStep> steps) {
        return true; // debug to test cyclic path handling
        // final Map<UUID, SanitizedRecipe> recipesById = new LinkedHashMap<>();
        // for (final CraftingStep step : steps) {
        //     recipesById.putIfAbsent(step.recipe().recipeId(), step.recipe());
        // }
        // return !SanitizedRecipeCycleAnalyzer.detectRecipeCycles(new ArrayList<>(recipesById.values())).cycles().isEmpty();
    }

    private static List<CraftingStep> reorderCyclicSteps(
        final List<CraftingStep> steps,
        final CancellationToken cancellationToken
    ) {
        final List<CraftingStep> remaining = new ArrayList<>(steps);
        final List<CraftingStep> reordered = new ArrayList<>(steps.size());
        final ResourcePool craftedBalance = ResourcePool.empty();
        final ResourcePool remainingProduction = computeRemainingProduction(remaining);

        while (!remaining.isEmpty()) {
            throwIfCancelled(cancellationToken);
            final int nextIndex = selectNextStepIndex(remaining, remainingProduction, craftedBalance, cancellationToken);
            final CraftingStep next = remaining.remove(nextIndex);
            subtractProducedOutputs(remainingProduction, next);
            reordered.add(next);
            applyStepToBalance(next, craftedBalance);
        }

        return List.copyOf(reordered);
    }

    private static int selectNextStepIndex(
        final List<CraftingStep> remaining,
        final ResourcePool remainingProduction,
        final ResourcePool craftedBalance,
        final CancellationToken cancellationToken
    ) {
        int bestReadyIndex = -1;
        long bestReadyExternalIncrease = Long.MAX_VALUE;
        int bestFallbackIndex = 0;
        long bestFallbackExternalIncrease = Long.MAX_VALUE;

        for (int index = 0; index < remaining.size(); index++) {
            throwIfCancelled(cancellationToken);
            final CraftingStep step = remaining.get(index);
            final long externalIncrease = computeExternalIncrease(step, craftedBalance);

            if (externalIncrease < bestFallbackExternalIncrease) {
                bestFallbackExternalIncrease = externalIncrease;
                bestFallbackIndex = index;
            }

            if (!isReadyStep(step, craftedBalance, remainingProduction)) {
                continue;
            }
            if (externalIncrease < bestReadyExternalIncrease) {
                bestReadyExternalIncrease = externalIncrease;
                bestReadyIndex = index;
            }
        }

        return bestReadyIndex >= 0 ? bestReadyIndex : bestFallbackIndex;
    }

    private static boolean isReadyStep(
        final CraftingStep step,
        final ResourcePool craftedBalance,
        final ResourcePool remainingProduction
    ) {
        for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().input()) {
            final long needed = entry.getValue() * step.timesApplied();
            if (needed <= 0) {
                continue;
            }
            if (craftedBalance.getAmount(entry.getKey()) >= needed) {
                continue;
            }
            final long futureProduction = remainingProduction.getAmount(entry.getKey())
                - producedAmount(step, entry.getKey());
            if (futureProduction > 0) {
                return false;
            }
        }
        return true;
    }

    private static long computeExternalIncrease(
        final CraftingStep step,
        final ResourcePool craftedBalance
    ) {
        long totalIncrease = 0;
        for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().input()) {
            final long required = entry.getValue() * step.timesApplied();
            if (required <= 0) {
                continue;
            }
            final long currentBalance = craftedBalance.getAmount(entry.getKey());
            final long currentNeed = Math.max(0L, -currentBalance);
            final long newNeed = Math.max(0L, -(currentBalance - required));
            totalIncrease += Math.max(0L, newNeed - currentNeed);
        }
        return totalIncrease;
    }

    private static ResourcePool computePeakResourceUsage(
        final List<CraftingStep> steps,
        final CancellationToken cancellationToken
    ) {
        final ResourcePool balance = ResourcePool.empty();
        final ResourcePool peakUsage = ResourcePool.empty();
        for (final CraftingStep step : steps) {
            throwIfCancelled(cancellationToken);
            for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().input()) {
                final long required = entry.getValue() * step.timesApplied();
                if (required <= 0) {
                    continue;
                }
                final long updatedBalance = balance.getAmount(entry.getKey()) - required;
                balance.setAmount(entry.getKey(), updatedBalance);
                peakUsage.setAmount(
                    entry.getKey(),
                    Math.max(peakUsage.getAmount(entry.getKey()), Math.max(0L, -updatedBalance))
                );
            }
            for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().output()) {
                balance.addAmount(entry.getKey(), entry.getValue() * step.timesApplied());
            }
        }
        return peakUsage;
    }

    private static ResourcePool computeRemainingProduction(final List<CraftingStep> steps) {
        final ResourcePool remainingProduction = ResourcePool.empty();
        for (final CraftingStep step : steps) {
            for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().output()) {
                remainingProduction.addAmount(entry.getKey(), entry.getValue() * step.timesApplied());
            }
        }
        return remainingProduction;
    }

    private static void subtractProducedOutputs(final ResourcePool remainingProduction, final CraftingStep step) {
        for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().output()) {
            remainingProduction.subtractAmount(entry.getKey(), entry.getValue() * step.timesApplied());
        }
    }

    private static void applyStepToBalance(final CraftingStep step, final ResourcePool balance) {
        for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().input()) {
            balance.subtractAmount(entry.getKey(), entry.getValue() * step.timesApplied());
        }
        for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().output()) {
            balance.addAmount(entry.getKey(), entry.getValue() * step.timesApplied());
        }
    }

    private static long producedAmount(final CraftingStep step, final MultiResourceKey resourceKey) {
        long amount = 0;
        for (final Map.Entry<MultiResourceKey, Long> entry : step.recipe().output()) {
            if (entry.getKey().equals(resourceKey)) {
                amount += entry.getValue() * step.timesApplied();
            }
        }
        return amount;
    }

    private static void throwIfCancelled(final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP execution planner cancelled");
        }
    }

}
