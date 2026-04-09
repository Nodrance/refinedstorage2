package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpDispatcherHelper;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpPlanningHelper;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpPreviewCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpExecutionPlanStep;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpStepPlan;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpStepPlanCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpTaskDispatcher;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreviewNode;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.core.CoreValidations;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LinearAutocraftingNetworkComponentImpl extends TraditionalAutocraftingNetworkComponentImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinearAutocraftingNetworkComponentImpl.class);

    LinearAutocraftingNetworkComponentImpl(final AutocraftingNetworkComponentState state) {
        super(state);
    }

    @Override
    public CompletableFuture<Optional<Preview>> getPreview(final ResourceKey resource,
                                                           final long amount,
                                                           final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        return CompletableFuture.supplyAsync(() -> {
            final RootStorage rootStorage = state.getRootStorageProvider().get();
            final Collection<Pattern> relevantPatterns =
                LpPlanningHelper.collectRelevantPatternsForLp(resource, rootStorage, state.getPatternRepository());
            return Optional.of(LpPreviewCalculator.calculatePreview(
                relevantPatterns,
                rootStorage,
                resource,
                amount,
                cancellationToken
            ));
        }, state.getExecutorService());
    }

    @Override
    public CompletableFuture<Optional<TreePreview>> getTreePreview(final ResourceKey resource,
                                                                   final long amount,
                                                                   final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        return CompletableFuture.supplyAsync(() -> {
            if (cancellationToken.isCancelled()) {
                return Optional.of(new TreePreview(PreviewType.CANCELLED, null, Collections.emptyList()));
            }

            final RootStorage rootStorage = state.getRootStorageProvider().get();
            final Collection<Pattern> relevantPatterns =
                LpPlanningHelper.collectRelevantPatternsForLp(resource, rootStorage, state.getPatternRepository());

            final Optional<LpStepPlan> stepPlan = LpStepPlanCalculator.calculateSteps(
                relevantPatterns,
                LOGGER,
                rootStorage,
                resource,
                amount,
                cancellationToken
            );
            if (stepPlan.isPresent()) {
                return Optional.of(buildTreePreviewFromSteps(resource, amount, rootStorage, stepPlan.get()));
            }

            final Preview preview = LpPreviewCalculator.calculatePreview(
                relevantPatterns,
                rootStorage,
                resource,
                amount,
                cancellationToken
            );
            return Optional.of(buildFallbackTreePreview(resource, amount, preview, relevantPatterns));
        }, state.getExecutorService());
    }

    @Override
    public CompletableFuture<Long> getMaxAmount(final ResourceKey resource,
                                                final CancellationToken cancellationToken) {
        CoreValidations.validateNotNull(resource, "Resource cannot be null");
        return CompletableFuture.supplyAsync(() -> {
            if (cancellationToken.isCancelled()) {
                return 0L;
            }
            final RootStorage rootStorage = state.getRootStorageProvider().get();
            final Collection<Pattern> relevantPatterns =
                LpPlanningHelper.collectRelevantPatternsForLp(resource, rootStorage, state.getPatternRepository());
            return LpStepPlanCalculator.calculateMaxAmount(
                relevantPatterns,
                LOGGER,
                rootStorage,
                resource,
                Long.MAX_VALUE,
                cancellationToken
            );
        }, state.getExecutorService());
    }

    @Override
    public Optional<TaskId> startTask(final ResourceKey resource,
                                      final long amount,
                                      final Actor actor,
                                      final boolean notify,
                                      final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        final RootStorage rootStorage = state.getRootStorageProvider().get();
        final Collection<Pattern> relevantPatterns =
            LpPlanningHelper.collectRelevantPatternsForLp(resource, rootStorage, state.getPatternRepository());
        return LpStepPlanCalculator.calculateSteps(
            relevantPatterns,
            LOGGER,
            rootStorage,
            resource,
            amount,
            cancellationToken
        ).flatMap(steps -> addLpDispatcherTask(resource, amount, actor, steps, notify));
    }

    @Override
    public AutocraftingNetworkComponent.EnsureResult ensureTask(final ResourceKey resource,
                                                                final long amount,
                                                                final Actor actor,
                                                                final CancellationToken cancellationToken) {
        ResourceAmount.validate(resource, amount);
        final long currentlyCrafting = state.getStatuses().stream()
            .filter(status -> status.info().resource().equals(resource))
            .mapToLong(status -> status.info().amount())
            .sum();
        if (currentlyCrafting >= amount) {
            return AutocraftingNetworkComponent.EnsureResult.TASK_ALREADY_RUNNING;
        }

        final RootStorage rootStorage = state.getRootStorageProvider().get();
        final long correctedAmount = amount - currentlyCrafting;
        final Collection<Pattern> relevantPatterns =
            LpPlanningHelper.collectRelevantPatternsForLp(resource, rootStorage, state.getPatternRepository());
        return LpStepPlanCalculator.calculateSteps(
            relevantPatterns,
            LOGGER,
            rootStorage,
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(steps -> addLpDispatcherTask(resource, correctedAmount, actor, steps, false))
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElseGet(() -> ensureTaskForCraftableAmountViaLp(
                relevantPatterns,
                rootStorage,
                resource,
                correctedAmount,
                actor,
                cancellationToken
            ));
    }

    private AutocraftingNetworkComponent.EnsureResult ensureTaskForCraftableAmountViaLp(
        final Collection<Pattern> relevantPatterns,
        final RootStorage rootStorage,
        final ResourceKey resource,
        final long amount,
        final Actor actor,
        final CancellationToken cancellationToken
    ) {
        if (cancellationToken.isCancelled()) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }

        final long correctedAmount = findMaxCraftableAmountViaLp(
            relevantPatterns,
            rootStorage,
            resource,
            amount,
            cancellationToken
        );
        if (correctedAmount <= 0) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }

        return LpStepPlanCalculator.calculateSteps(
            state.getPatternRepository().getAll(),
            LOGGER,
            rootStorage,
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(
                steps -> addLpDispatcherTask(resource, correctedAmount, actor, steps, false)
            )
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElse(AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES);
    }

    private long findMaxCraftableAmountViaLp(final Collection<Pattern> relevantPatterns,
                                             final RootStorage rootStorage,
                                             final ResourceKey resource,
                                             final long amount,
                                             final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return 0;
        }

        final long lpMax = LpStepPlanCalculator.calculateMaxAmount(
            relevantPatterns,
            LOGGER,
            rootStorage,
            resource,
            amount,
            cancellationToken
        );
        if (lpMax <= 0) {
            return 0;
        }

        if (!LpStepPlanCalculator.hasPatternRecipeCycles(relevantPatterns)) {
            return lpMax;
        }

        long low = 1;
        long high = lpMax;
        long best = 0;

        while (low <= high && !cancellationToken.isCancelled()) {
            final long middle = low + ((high - low) / 2);
            final boolean craftable = LpStepPlanCalculator.calculateSteps(
                relevantPatterns,
                LOGGER,
                rootStorage,
                resource,
                middle,
                cancellationToken
            ).isPresent();
            if (craftable) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return best;
    }

    private Optional<TaskId> addLpDispatcherTask(final ResourceKey resource,
                                                 final long amount,
                                                 final Actor actor,
                                                 final LpStepPlan lpStepPlan,
                                                 final boolean notify) {
        if (lpStepPlan.steps().size() == 1) {
            final TaskPlan singleStepPlan = LpDispatcherHelper.toSingleStepPlan(
                resource,
                amount,
                lpStepPlan.steps().getFirst(),
                true
            );
            return Optional.of(state.addSingleStepTask(actor, singleStepPlan, notify, LOGGER));
        }

        final Pattern rootPattern = LpDispatcherHelper.findRootPattern(resource, lpStepPlan.steps());
        if (rootPattern == null) {
            return Optional.empty();
        }

        final PatternProvider provider = state.getProviderByPatternMap().get(rootPattern);
        if (provider == null) {
            return Optional.empty();
        }

        final Task dispatcher = new LpTaskDispatcher(
            resource,
            amount,
            actor,
            notify,
            lpStepPlan,
            rootPattern,
            pattern -> state.getProviderByPatternMap().containsKey(pattern)
        );
        provider.addTask(dispatcher);
        return Optional.of(dispatcher.getId());
    }

    private static TreePreview buildTreePreviewFromSteps(final ResourceKey resource,
                                                         final long amount,
                                                         final RootStorage rootStorage,
                                                         final LpStepPlan stepPlan) {
        final NodeBuilder root = new NodeBuilder(resource, amount);
        final ArrayDeque<PendingNode> frontier = new ArrayDeque<>();
        frontier.add(new PendingNode(root, amount));

        final List<LpExecutionPlanStep> reversedSteps = new ArrayList<>(stepPlan.steps());
        Collections.reverse(reversedSteps);

        for (final LpExecutionPlanStep step : reversedSteps) {
            if (frontier.isEmpty()) {
                break;
            }

            final List<PendingNode> matchedNodes = new ArrayList<>();
            for (final PendingNode pendingNode : frontier) {
                if (step.recipe().output().getAmount(pendingNode.node.resource) > 0) {
                    matchedNodes.add(pendingNode);
                }
            }
            if (matchedNodes.isEmpty()) {
                continue;
            }

            long remainingIterations = step.iterations();
            final List<PendingNode> nextNodes = new ArrayList<>();
            for (final PendingNode pendingNode : matchedNodes) {
                frontier.remove(pendingNode);
                if (remainingIterations <= 0) {
                    nextNodes.add(pendingNode);
                    continue;
                }

                final NodeBuilder node = pendingNode.node;
                final long requiredAmount = pendingNode.requiredAmount;
                final long outputPerIteration = step.recipe().output().getAmount(node.resource);
                if (outputPerIteration <= 0) {
                    nextNodes.add(pendingNode);
                    continue;
                }

                final long requiredIterations = ceilDiv(requiredAmount, outputPerIteration);
                final long usedIterations = Math.min(remainingIterations, requiredIterations);
                if (usedIterations <= 0) {
                    nextNodes.add(pendingNode);
                    continue;
                }
                remainingIterations -= usedIterations;

                final long craftedAmount = outputPerIteration * usedIterations;
                node.toCraft += craftedAmount;

                for (final var input : step.recipe().input()) {
                    final long childAmount = input.getValue() * usedIterations;
                    if (childAmount <= 0) {
                        continue;
                    }

                    final NodeBuilder child = node.addChild(input.getKey(), childAmount);
                    nextNodes.add(new PendingNode(child, childAmount));
                }

                final long unresolvedAmount = requiredAmount - craftedAmount;
                if (unresolvedAmount > 0) {
                    nextNodes.add(new PendingNode(node, unresolvedAmount));
                }
            }
            frontier.addAll(nextNodes);
        }

        final Map<ResourceKey, Long> remainingStorage = new LinkedHashMap<>();
        for (final ResourceAmount resourceAmount : rootStorage.getAll()) {
            remainingStorage.put(resourceAmount.resource(), resourceAmount.amount());
        }

        final Set<ResourceKey> producibleResources = new HashSet<>();
        for (final LpExecutionPlanStep step : stepPlan.steps()) {
            for (final var output : step.recipe().output()) {
                producibleResources.add(output.getKey());
            }
        }

        for (final PendingNode pendingNode : frontier) {
            final NodeBuilder node = pendingNode.node;
            final long requiredAmount = pendingNode.requiredAmount;
            final long available = Math.min(requiredAmount, remainingStorage.getOrDefault(node.resource, 0L));
            if (available > 0) {
                node.available += available;
                remainingStorage.put(node.resource, remainingStorage.getOrDefault(node.resource, 0L) - available);
            }
            if (!producibleResources.contains(node.resource)) {
                final long missing = requiredAmount - available;
                if (missing > 0) {
                    node.missing += missing;
                }
            }
        }

        final PreviewType type = hasMissing(root) ? PreviewType.MISSING_RESOURCES : PreviewType.SUCCESS;
        return new TreePreview(type, root.build(), outputsOfPatternWithCycle(stepPlan));
    }

    private static List<ResourceAmount> outputsOfPatternWithCycle(final LpStepPlan stepPlan) {
        if (!stepPlan.hasRecipeCycles()) {
            return Collections.emptyList();
        }

        final Set<ResourceAmount> outputs = new HashSet<>();
        for (final LpExecutionPlanStep step : stepPlan.steps()) {
            outputs.addAll(step.recipe().pattern().layout().outputs());
        }
        return List.copyOf(outputs);
    }

    private static TreePreview buildFallbackTreePreview(final ResourceKey requestedResource,
                                                        final long requestedAmount,
                                                        final Preview preview,
                                                        final Collection<Pattern> relevantPatterns) {
        if (preview.type() == PreviewType.CANCELLED || preview.type() == PreviewType.NOT_AVAILABLE) {
            return new TreePreview(preview.type(), null, preview.outputsOfPatternWithCycle());
        }

        final Map<ResourceKey, PreviewItem> previewItemsByResource = new LinkedHashMap<>();
        for (final PreviewItem item : preview.items()) {
            previewItemsByResource.put(item.resource(), item);
        }

        final PreviewItem rootItem = previewItemsByResource.get(requestedResource);
        final long rootAmount = rootItem == null
            ? requestedAmount
            : rootItem.available() + rootItem.missing() + rootItem.toCraft();
        final NodeBuilder root = new NodeBuilder(requestedResource, rootAmount);
        applyPreviewItem(root, rootItem);

        final Set<ResourceKey> assignedResources = new HashSet<>();
        assignedResources.add(requestedResource);
        attachPatternChildren(root, previewItemsByResource, relevantPatterns, assignedResources, new HashSet<>());

        for (final PreviewItem item : preview.items()) {
            if (assignedResources.contains(item.resource())) {
                continue;
            }
            final long amount = item.available() + item.missing() + item.toCraft();
            final NodeBuilder child = root.addChild(item.resource(), amount);
            applyPreviewItem(child, item);
            assignedResources.add(item.resource());
        }

        return new TreePreview(preview.type(), root.build(), preview.outputsOfPatternWithCycle());
    }

    private static void attachPatternChildren(final NodeBuilder node,
                                              final Map<ResourceKey, PreviewItem> previewItemsByResource,
                                              final Collection<Pattern> relevantPatterns,
                                              final Set<ResourceKey> assignedResources,
                                              final Set<ResourceKey> path) {
        if (!path.add(node.resource)) {
            return;
        }

        final Pattern pattern = findPatternProducing(node.resource, relevantPatterns);
        if (pattern == null) {
            path.remove(node.resource);
            return;
        }

        for (final Ingredient ingredient : pattern.layout().ingredients()) {
            final ResourceKey selectedInput = pickInputResourceForIngredient(ingredient, previewItemsByResource);
            if (selectedInput == null || !assignedResources.add(selectedInput)) {
                continue;
            }

            final PreviewItem childItem = previewItemsByResource.get(selectedInput);
            final long childAmount = childItem == null
                ? ingredient.amount()
                : childItem.available() + childItem.missing() + childItem.toCraft();
            final NodeBuilder child = node.addChild(selectedInput, childAmount);
            applyPreviewItem(child, childItem);
            attachPatternChildren(child, previewItemsByResource, relevantPatterns, assignedResources, path);
        }

        path.remove(node.resource);
    }

    private static Pattern findPatternProducing(final ResourceKey resource,
                                                final Collection<Pattern> patterns) {
        for (final Pattern pattern : patterns) {
            for (final ResourceAmount output : pattern.layout().outputs()) {
                if (output.resource().equals(resource)) {
                    return pattern;
                }
            }
        }
        return null;
    }

    private static ResourceKey pickInputResourceForIngredient(final Ingredient ingredient,
                                                              final Map<ResourceKey, PreviewItem> previewItemsByResource) {
        for (final ResourceKey input : ingredient.inputs()) {
            if (previewItemsByResource.containsKey(input)) {
                return input;
            }
        }
        return ingredient.inputs().isEmpty() ? null : ingredient.inputs().getFirst();
    }

    private static void applyPreviewItem(final NodeBuilder node,
                                         final PreviewItem item) {
        if (item == null) {
            return;
        }
        node.available += item.available();
        node.missing += item.missing();
        node.toCraft += item.toCraft();
    }

    private static boolean hasMissing(final NodeBuilder node) {
        if (node.missing > 0) {
            return true;
        }
        for (final NodeBuilder child : node.children.values()) {
            if (hasMissing(child)) {
                return true;
            }
        }
        return false;
    }

    private static long ceilDiv(final long numerator, final long denominator) {
        return ((numerator - 1) / denominator) + 1;
    }

    private record PendingNode(NodeBuilder node, long requiredAmount) {
    }

    private static class NodeBuilder {
        private final ResourceKey resource;
        private final Map<ResourceKey, NodeBuilder> children = new LinkedHashMap<>();
        private long amount;
        private long toCraft;
        private long available;
        private long missing;

        private NodeBuilder(final ResourceKey resource, final long amount) {
            this.resource = resource;
            this.amount = amount;
        }

        private NodeBuilder addChild(final ResourceKey childResource, final long childAmount) {
            final NodeBuilder existing = children.get(childResource);
            if (existing != null) {
                existing.amount += childAmount;
                return existing;
            }
            final NodeBuilder child = new NodeBuilder(childResource, childAmount);
            children.put(childResource, child);
            return child;
        }

        private TreePreviewNode build() {
            return new TreePreviewNode(
                resource,
                amount,
                toCraft,
                available,
                missing,
                children.values().stream().map(NodeBuilder::build).toList()
            );
        }
    }
}

