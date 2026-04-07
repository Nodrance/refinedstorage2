package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LpPreviewCalculator {
    private static final Logger LOGGER = LoggerFactory.getLogger(LpPreviewCalculator.class);

    private LpPreviewCalculator() {
    }

    public static Preview calculatePreview(final Collection<Pattern> patterns,
                                           final RootStorage rootStorage,
                                           final ResourceKey resource,
                                           final long amount,
                                           final CancellationToken cancellationToken) {
        LOGGER.info("[LP] calculatePreview called for resource: {}, amount: {}", resource, amount);
        Objects.requireNonNull(patterns, "patterns cannot be null");
        Objects.requireNonNull(rootStorage, "rootStorage cannot be null");
        Objects.requireNonNull(resource, "resource cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");

        final Preview preview;
        if (cancellationToken.isCancelled()) {
            LOGGER.info("[LP] Preview calculation cancelled by token.");
            preview = cancelled();
        } else {
            final LpResourceSet startingResources = LpResourceSet.fromResourceAmounts(rootStorage.getAll());
            LOGGER.info("[LP] Starting resources: {}", startingResources);
            final LpFuzzyExpander.FuzzyExpansionResult expansionResult = LpFuzzyExpander.expandFuzzyPatterns(
                patterns,
                startingResources,
                LpFuzzyExpander.collectCraftableResources(patterns)
            );
            LOGGER.info(
                "[LP] Fuzzy expansion result: {} recipes, {} variant mappings",
                expansionResult.expandedRecipes().size(),
                expansionResult.variantToSourcePattern().size()
            );
            preview = buildPreview(
                patterns,
                rootStorage,
                resource,
                amount,
                cancellationToken,
                startingResources,
                expansionResult
            );
        }
        return preview;
    }

    private static Preview buildPreview(final Collection<Pattern> patterns,
                                        final RootStorage rootStorage,
                                        final ResourceKey resource,
                                        final long amount,
                                        final CancellationToken cancellationToken,
                                        final LpResourceSet startingResources,
                                        final LpFuzzyExpander.FuzzyExpansionResult expansionResult) {
        final Preview preview;
        if (expansionResult.expandedRecipes().isEmpty()) {
            LOGGER.info("[LP] No expanded recipes available for preview.");
            preview = notAvailable();
        } else {
            final LpResourceSet augmentedStartingResources = LpFuzzyExpander.augmentStartingResources(
                startingResources,
                expansionResult.createdSubsets()
            );
            LOGGER.info("[LP] Augmented starting resources: {}", augmentedStartingResources);
            final LpResourceSet target = new LpResourceSet();
            target.setAmount(resource, rootStorage.get(resource) + amount);
            LOGGER.info("[LP] Target resource set: {}", target);

            final LpCraftingSolver.PlanningOutcome outcome = new LpCraftingSolver().solve(
                expansionResult.expandedRecipes(),
                augmentedStartingResources,
                target
            );
            LOGGER.info(
                "[LP] Planning outcome: maxCraftableAmount={}, executableResultPresent={}, requiredBaseItems={}",
                outcome.maxCraftableAmount(),
                outcome.executableResult().isPresent(),
                outcome.requiredBaseItems()
            );

            if (cancellationToken.isCancelled()) {
                LOGGER.info("[LP] Preview calculation cancelled by token after planning.");
                preview = cancelled();
            } else if (outcome.executableResult().isPresent()) {
                LOGGER.info("[LP] Preview calculation successful, returning success preview.");
                preview = buildSuccessPreview(
                    outcome.executableResult().get().plan(),
                    expansionResult.variantToSourcePattern(),
                    patterns,
                    startingResources
                );
            } else if (!outcome.requiredBaseItems().isEmpty()) {
                LOGGER.info(
                    "[LP] Preview calculation found missing base items: {}",
                    outcome.requiredBaseItems()
                );
                preview = buildMissingPreview(
                    patterns,
                    resource,
                    amount,
                    startingResources,
                    outcome.requiredBaseItems()
                );
            } else {
                LOGGER.info("[LP] Preview calculation not available for resource: {}", resource);
                preview = notAvailable();
            }
        }
        return preview;
    }

    private static Preview buildSuccessPreview(final List<LpExecutionPlanStep> rawSteps,
                                               final Map<UUID, UUID> variantToSourcePattern,
                                               final Collection<Pattern> patterns,
                                               final LpResourceSet startingResources) {
        LOGGER.info("[LP] Building success preview. Steps: {}", rawSteps.size());
        final List<LpExecutionPlanStep> steps = decodeStepsIfNeeded(
            rawSteps,
            variantToSourcePattern,
            patterns,
            startingResources
        );
        final PreviewBuilder builder = PreviewBuilder.create();
        final LpResourceSet remainingStorage = startingResources.copy();

        for (final LpExecutionPlanStep step : steps) {
            LOGGER.info("[LP] Step: {} iterations, recipe: {}", step.iterations(), step.recipe());
            for (final ResourceAmount output : step.recipe().pattern().layout().outputs()) {
                builder.addToCraft(output.resource(), output.amount() * step.iterations());
            }

            for (final var input : step.recipe().input()) {
                final long needed = input.getValue() * step.iterations();
                final long available = Math.min(remainingStorage.getAmount(input.getKey()), needed);
                if (available > 0) {
                    builder.addAvailable(input.getKey(), available);
                    remainingStorage.subtractAmount(input.getKey(), available);
                }
            }
        }

        return builder.build();
    }

    private static List<LpExecutionPlanStep> decodeStepsIfNeeded(final List<LpExecutionPlanStep> rawSteps,
                                                                 final Map<UUID, UUID> variantToSourcePattern,
                                                                 final Collection<Pattern> patterns,
                                                                 final LpResourceSet startingResources) {
        if (variantToSourcePattern.isEmpty()) {
            return rawSteps;
        }

        final Map<UUID, Pattern> patternsById = new LinkedHashMap<>();
        for (final Pattern pattern : patterns) {
            patternsById.put(pattern.id(), pattern);
        }
        LOGGER.info(
            "[LP] Decoding plan steps for fuzzy variants. Variant mappings: {}",
            variantToSourcePattern.size()
        );
        return LpFuzzyExpander.decodePlanSteps(rawSteps, variantToSourcePattern, patternsById, startingResources);
    }

    private static Preview buildMissingPreview(final Collection<Pattern> patterns,
                                               final ResourceKey requestedResource,
                                               final long requestedAmount,
                                               final LpResourceSet startingResources,
                                               final LpResourceSet requiredBaseItems) {
        LOGGER.info(
            "[LP] Building missing preview for resource: {}. Required base items: {}",
            requestedResource,
            requiredBaseItems
        );
        final PreviewBuilder builder = PreviewBuilder.create();
        final long displayedCraftAmount = resolveDisplayedCraftAmount(
            patterns,
            requestedResource,
            requestedAmount
        );
        builder.addToCraft(requestedResource, displayedCraftAmount);

        for (final var entry : requiredBaseItems) {
            final ResourceKey resource = entry.getKey();
            final long missing = entry.getValue();
            if (resource instanceof LpResourceSubset subset) {
                addSubsetMissingPreviewItems(builder, subset, missing, startingResources);
                continue;
            }
            final long available = startingResources.getAmount(resource);
            if (available > 0) {
                builder.addAvailable(resource, available);
            }
            if (missing > 0) {
                builder.addMissing(resource, missing);
            }
        }
        return builder.build();
    }

    private static void addSubsetMissingPreviewItems(final PreviewBuilder builder,
                                                     final LpResourceSubset subset,
                                                     final long missing,
                                                     final LpResourceSet startingResources) {
        for (final ResourceKey member : subset.members()) {
            final long available = startingResources.getAmount(member);
            if (available > 0) {
                builder.addAvailable(member, available);
            }
        }
        if (missing > 0) {
            builder.addMissing(subset.members().getFirst(), missing);
        }
    }

    private static long resolveDisplayedCraftAmount(final Collection<Pattern> patterns,
                                                    final ResourceKey requestedResource,
                                                    final long requestedAmount) {
        for (final Pattern pattern : patterns) {
            final long amountPerIteration = pattern.layout().outputs().stream()
                .filter(output -> output.resource().equals(requestedResource))
                .mapToLong(ResourceAmount::amount)
                .sum();
            if (amountPerIteration > 0) {
                final long iterations = ((requestedAmount - 1) / amountPerIteration) + 1;
                return iterations * amountPerIteration;
            }
        }
        return requestedAmount;
    }

    private static Preview cancelled() {
        return new Preview(PreviewType.CANCELLED, Collections.emptyList(), Collections.emptyList());
    }

    private static Preview notAvailable() {
        return new Preview(PreviewType.NOT_AVAILABLE, Collections.emptyList(), Collections.emptyList());
    }
}

