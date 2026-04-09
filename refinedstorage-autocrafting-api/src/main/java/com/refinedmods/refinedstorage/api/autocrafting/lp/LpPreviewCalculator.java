package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

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

            final LpCraftingSolver.PlanningOutcome outcome;
            try {
                outcome = new LpCraftingSolver(cancellationToken).solve(
                    expansionResult.expandedRecipes(),
                    augmentedStartingResources,
                    target
                );
            } catch (final CancellationException e) {
                LOGGER.info("[LP] Preview calculation cancelled while solving.", e);
                return cancelled();
            }
            LOGGER.info(
                "[LP] Planning outcome: maxCraftableAmount={}, recipeApplicationResultPresent={}, requiredBaseItems={}",
                outcome.maxCraftableAmount(),
                outcome.recipeApplicationResult().isPresent(),
                outcome.requiredBaseItems()
            );

            if (cancellationToken.isCancelled()) {
                LOGGER.info("[LP] Preview calculation cancelled by token after planning.");
                preview = cancelled();
            } else if (outcome.recipeApplicationResult().isPresent()) {
                preview = buildPreviewFromRecipeApplicationResult(
                    patterns,
                    resource,
                    amount,
                    startingResources,
                    expansionResult.variantToSourcePattern(),
                    outcome.recipeApplicationResult().get()
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
                    outcome.requiredBaseItems(),
                    List.of()
                );
            } else {
                LOGGER.info("[LP] Preview calculation not available for resource: {}", resource);
                preview = notAvailable();
            }
        }
        return preview;
    }

    private static Preview buildPreviewFromRecipeApplicationResult(
        final Collection<Pattern> patterns,
        final ResourceKey resource,
        final long amount,
        final LpResourceSet startingResources,
        final Map<UUID, UUID> variantToSourcePattern,
        final LpCraftingSolver.RecipeApplicationPlanResult planResult
    ) {
        if (planResult.requiredBaseItems().isEmpty()) {
            LOGGER.info("[LP] Preview calculation successful, returning success preview.");
            return buildSuccessPreview(
                planResult.plan(),
                variantToSourcePattern,
                patterns,
                startingResources
            );
        }
        LOGGER.info("[LP] Preview calculation produced plan with missing base items.");
        return buildMissingPreview(
            patterns,
            resource,
            amount,
            startingResources,
            planResult.requiredBaseItems(),
            planResult.plan()
        );
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

        return sortPreviewItemsByDependency(builder.build(), steps);
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
                                               final LpResourceSet requiredBaseItems,
                                               final List<LpExecutionPlanStep> recipeApplicationPlan) {
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

        final Set<ResourceKey> requiredBaseResources = new LinkedHashSet<>();
        for (final var entry : requiredBaseItems) {
            requiredBaseResources.add(entry.getKey());
        }

        addAvailableForNonBaseInputs(
            builder,
            recipeApplicationPlan,
            startingResources,
            requiredBaseResources
        );

        for (final LpExecutionPlanStep step : recipeApplicationPlan) {
            for (final ResourceAmount output : step.recipe().pattern().layout().outputs()) {
                if (!output.resource().equals(requestedResource)) {
                    builder.addToCraft(output.resource(), output.amount() * step.iterations());
                }
            }
        }

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
        return sortPreviewItemsByDependency(builder.build(), recipeApplicationPlan);
    }

    private static Preview sortPreviewItemsByDependency(final Preview preview,
                                                        final List<LpExecutionPlanStep> steps) {
        if (steps.isEmpty()) {
            return preview;
        }

        final List<PreviewItem> items = preview.items();
        final Map<ResourceKey, PreviewItem> itemByResource = new LinkedHashMap<>();
        final Map<ResourceKey, Integer> firstIndexByResource = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            final PreviewItem item = items.get(i);
            itemByResource.put(item.resource(), item);
            firstIndexByResource.put(item.resource(), i);
        }

        final Set<ResourceKey> nodes = new LinkedHashSet<>(itemByResource.keySet());
        final Map<ResourceKey, Set<ResourceKey>> edges = new HashMap<>();
        final Map<ResourceKey, Integer> indegree = new HashMap<>();
        for (final ResourceKey node : nodes) {
            edges.put(node, new LinkedHashSet<>());
            indegree.put(node, 0);
        }

        for (final LpExecutionPlanStep step : steps) {
            final LpResourceSet outputs = step.recipe().output();
            final LpResourceSet inputs = step.recipe().input();
            for (final var outputEntry : outputs) {
                final ResourceKey output = outputEntry.getKey();
                if (!nodes.contains(output)) {
                    continue;
                }
                for (final var inputEntry : inputs) {
                    final ResourceKey input = inputEntry.getKey();
                    if (!nodes.contains(input) || output.equals(input)) {
                        continue;
                    }
                    if (edges.get(output).add(input)) {
                        indegree.put(input, indegree.get(input) + 1);
                    }
                }
            }
        }

        final ArrayDeque<ResourceKey> queue = new ArrayDeque<>();
        nodes.stream()
            .filter(node -> indegree.get(node) == 0)
            .sorted(Comparator.comparingInt(firstIndexByResource::get))
            .forEach(queue::addLast);

        final List<ResourceKey> orderedResources = new java.util.ArrayList<>(nodes.size());
        while (!queue.isEmpty()) {
            final ResourceKey node = queue.removeFirst();
            orderedResources.add(node);

            final List<ResourceKey> dependents = edges.get(node).stream()
                .sorted(Comparator.comparingInt(firstIndexByResource::get))
                .toList();
            for (final ResourceKey dependent : dependents) {
                final int next = indegree.get(dependent) - 1;
                indegree.put(dependent, next);
                if (next == 0) {
                    queue.addLast(dependent);
                }
            }
        }

        if (orderedResources.size() != nodes.size()) {
            return preview;
        }

        final List<PreviewItem> orderedItems = orderedResources.stream()
            .map(itemByResource::get)
            .toList();
        return new Preview(preview.type(), orderedItems, preview.outputsOfPatternWithCycle());
    }

    private static void addAvailableForNonBaseInputs(final PreviewBuilder builder,
                                                     final List<LpExecutionPlanStep> recipeApplicationPlan,
                                                     final LpResourceSet startingResources,
                                                     final Set<ResourceKey> requiredBaseResources) {
        final LpResourceSet remainingStorage = startingResources.copy();
        for (final LpExecutionPlanStep step : recipeApplicationPlan) {
            for (final var input : step.recipe().input()) {
                final ResourceKey resource = input.getKey();
                if (requiredBaseResources.contains(resource)) {
                    continue;
                }
                final long needed = input.getValue() * step.iterations();
                final long available = Math.min(remainingStorage.getAmount(resource), needed);
                if (available > 0) {
                    builder.addAvailable(resource, available);
                    remainingStorage.subtractAmount(resource, available);
                }
            }
        }
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

