package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.ConcreteRecipe;
import com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingInitializer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingSolver;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpDispatcherHelper;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpExecutionPlanStep;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpPatternRecipe;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpResourceSet;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpStepPlan;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpTaskDispatcher;
import com.refinedmods.refinedstorage.api.autocrafting.lp.MultiResourceKey;
import com.refinedmods.refinedstorage.api.autocrafting.lp.PreviewCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.RecipeApplicationPath;
import com.refinedmods.refinedstorage.api.autocrafting.lp.RecipeApplicationSet;
import com.refinedmods.refinedstorage.api.autocrafting.lp.RecipeApplicationStep;
import com.refinedmods.refinedstorage.api.autocrafting.lp.RecipeDesanitizer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.ResourcePool;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
            if (cancellationToken.isCancelled()) {
                return Optional.of(new Preview(PreviewType.CANCELLED, Collections.emptyList(), Collections.emptyList()));
            }
            final RootStorage rootStorage = state.getRootStorageProvider().get();
            final CraftingInitializer.Initialization initialization = CraftingInitializer.initialize(
                rootStorage,
                state.getPatternRepository(),
                resource,
                amount,
                cancellationToken
            );
            final Optional<RecipeApplicationPath> path = new CraftingSolver(cancellationToken).solve(
                initialization.concreteRecipes(),
                initialization.relevantStartingResources(),
                initialization.target()
            ).map(p -> desanitizeRecipeApplicationPath(p, initialization.relevantStartingResources(), cancellationToken));
            if (path.isEmpty()) {
                return Optional.of(new Preview(PreviewType.NOT_AVAILABLE, Collections.emptyList(), Collections.emptyList()));
            }
            return Optional.of(PreviewCalculator.calculatePreview(path.get(), cancellationToken));
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
            final CraftingInitializer.Initialization initialization = CraftingInitializer.initialize(
                rootStorage,
                state.getPatternRepository(),
                resource,
                amount,
                cancellationToken
            );
            final Optional<RecipeApplicationPath> recipeApplicationPath = new CraftingSolver(cancellationToken).solve(
                initialization.concreteRecipes(),
                initialization.relevantStartingResources(),
                initialization.target()
            ).map(path -> desanitizeRecipeApplicationPath(path, initialization.relevantStartingResources(), cancellationToken));

            final Optional<LpStepPlan> stepPlan = recipeApplicationPath.map(path -> toLpStepPlan(
                path.steps(),
                initialization.relevantPatterns()
            ));
            if (stepPlan.isPresent()) {
                return Optional.of(buildTreePreviewFromSteps(resource, amount, rootStorage, stepPlan.get()));
            }

            final Preview preview = new Preview(PreviewType.NOT_AVAILABLE, Collections.emptyList(), Collections.emptyList());
            return Optional.of(buildFallbackTreePreview(resource, amount, preview, initialization.relevantPatterns()));
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
            return findMaxCraftableAmountViaLp(
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
        return calculateStepsViaInitializer(
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
        return calculateStepsViaInitializer(
            rootStorage,
            resource,
            correctedAmount,
            cancellationToken
        )
            .flatMap(steps -> addLpDispatcherTask(resource, correctedAmount, actor, steps, false))
            .map(taskId -> AutocraftingNetworkComponent.EnsureResult.TASK_CREATED)
            .orElseGet(() -> ensureTaskForCraftableAmountViaLp(
                rootStorage,
                resource,
                correctedAmount,
                actor,
                cancellationToken
            ));
    }

    private AutocraftingNetworkComponent.EnsureResult ensureTaskForCraftableAmountViaLp(
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
            rootStorage,
            resource,
            amount,
            cancellationToken
        );
        if (correctedAmount <= 0) {
            return AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES;
        }

        return calculateStepsViaInitializer(
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

    private long findMaxCraftableAmountViaLp(final RootStorage rootStorage,
                                             final ResourceKey resource,
                                             final long amount,
                                             final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            return 0;
        }

        long low = 1;
        long high = 1;
        while (high < amount && !cancellationToken.isCancelled()) {
            if (calculateStepsViaInitializer(rootStorage, resource, high, cancellationToken).isPresent()) {
                low = high;
                if (high > Long.MAX_VALUE / 2) {
                    high = amount;
                } else {
                    high = Math.min(amount, high * 2);
                }
            } else {
                break;
            }
        }

        long best = 0;

        while (low <= high && !cancellationToken.isCancelled()) {
            final long middle = low + ((high - low) / 2);
            final boolean craftable = calculateStepsViaInitializer(
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

    private Optional<LpStepPlan> calculateStepsViaInitializer(
        final RootStorage rootStorage,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        if (cancellationToken.isCancelled()) {
            return Optional.empty();
        }

        final CraftingInitializer.Initialization initialization = CraftingInitializer.initialize(
            rootStorage,
            state.getPatternRepository(),
            resource,
            amount,
            cancellationToken
        );
        final Optional<RecipeApplicationPath> solved = new CraftingSolver(cancellationToken).solve(
            initialization.concreteRecipes(),
            initialization.relevantStartingResources(),
            initialization.target()
        );
        if (solved.isEmpty()) {
            return Optional.empty();
        }

        final RecipeApplicationPath desanitized = desanitizeRecipeApplicationPath(
            solved.get(),
            initialization.relevantStartingResources(),
            cancellationToken
        );
        return Optional.of(toLpStepPlan(desanitized.steps(), initialization.relevantPatterns()));
    }

    private static RecipeApplicationPath desanitizeRecipeApplicationPath(
        final RecipeApplicationPath path,
        final ResourcePool availableResources,
        final CancellationToken cancellationToken
    ) {
        LOGGER.info(
            "[LP] desanitizeRecipeApplicationPath start: steps={} available={} used={} missing={} final={}",
            path.steps().size(),
            summarizePool(availableResources),
            summarizePool(path.applicationSet().usedResources()),
            summarizePool(path.applicationSet().missingResources()),
            summarizePool(path.applicationSet().finalInventoryValues())
        );

        final List<RecipeApplicationStep> decodedSteps = RecipeDesanitizer.decodePlanSteps(
            path.steps(),
            availableResources,
            cancellationToken
        );

        final RecipeApplicationSet original = path.applicationSet();
        final ResourcePool decodedUsed = RecipeDesanitizer.decodeSanitizedResources(
            original.usedResources(),
            availableResources,
            cancellationToken
        );
        final ResourcePool decodedMissing = RecipeDesanitizer.decodeSanitizedResources(
            original.missingResources(),
            availableResources,
            cancellationToken
        );
        final ResourcePool decodedFinal = RecipeDesanitizer.decodeSanitizedResources(
            original.finalInventoryValues(),
            availableResources,
            cancellationToken
        );

        final List<ResourceKey> decodedRelevantResources = decodeRelevantResourceKeys(original.relevantResourceKeys());
        final RecipeApplicationSet decodedSet = new RecipeApplicationSet(
            original.recipes(),
            original.recipeValues(),
            decodedUsed,
            decodedFinal,
            decodedMissing,
            decodedRelevantResources
        );

        LOGGER.info(
            "[LP] desanitizeRecipeApplicationPath result: steps={} used={} missing={} final={} relevantResources={}",
            decodedSteps.size(),
            summarizePool(decodedUsed),
            summarizePool(decodedMissing),
            summarizePool(decodedFinal),
            decodedRelevantResources.size()
        );

        return new RecipeApplicationPath(decodedSet, decodedSteps);
    }

    private static String summarizePool(final ResourcePool pool) {
        return "{resources=" + countPoolEntries(pool) + ", total=" + sumPoolAmounts(pool) + ", values=" + pool + "}";
    }

    private static int countPoolEntries(final ResourcePool pool) {
        int count = 0;
        for (final Map.Entry<ResourceKey, Long> ignored : pool) {
            count++;
        }
        return count;
    }

    private static long sumPoolAmounts(final ResourcePool pool) {
        long total = 0L;
        for (final Map.Entry<ResourceKey, Long> entry : pool) {
            total += entry.getValue();
        }
        return total;
    }

    private static List<ResourceKey> decodeRelevantResourceKeys(final List<ResourceKey> relevantResourceKeys) {
        final LinkedHashSet<ResourceKey> decoded = new LinkedHashSet<>();
        for (final ResourceKey key : relevantResourceKeys) {
            if (key instanceof MultiResourceKey multiResourceKey) {
                decoded.addAll(multiResourceKey.members());
            } else {
                decoded.add(key);
            }
        }
        return List.copyOf(decoded);
    }

    private static LpStepPlan toLpStepPlan(
        final List<RecipeApplicationStep> steps,
        final Collection<Pattern> patterns
    ) {
        final Map<UUID, Pattern> patternsById = new LinkedHashMap<>();
        for (final Pattern pattern : patterns) {
            patternsById.put(pattern.id(), pattern);
        }

        final List<LpExecutionPlanStep> lpSteps = new ArrayList<>();
        for (final RecipeApplicationStep step : steps) {
            final ConcreteRecipe recipe = step.recipe();
            final Pattern sourcePattern = patternsById.get(recipe.sourcePatternId());
            if (sourcePattern == null) {
                continue;
            }
            final int priority = recipe.priority() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (recipe.priority() < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) recipe.priority());
            final LpPatternRecipe lpRecipe = new LpPatternRecipe(
                sourcePattern,
                toLpResourceSet(recipe.input()),
                toLpResourceSet(recipe.output()),
                priority,
                null
            );
            lpSteps.add(new LpExecutionPlanStep(lpRecipe, step.timesApplied()));
        }

        return new LpStepPlan(lpSteps, false);
    }

    private static LpResourceSet toLpResourceSet(final ResourcePool resourcePool) {
        final LpResourceSet result = new LpResourceSet();
        for (final Map.Entry<ResourceKey, Long> entry : resourcePool) {
            result.setAmount(entry.getKey(), entry.getValue());
        }
        return result;
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

