package com.refinedmods.refinedstorage.api.autocrafting.lp.preview;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingOrchestrator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.DesanitizedRecipe;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.DesanitizedRecipeApplicationStep;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.DesanitizedRecipeApplicationPath;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreviewNode;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.ResourceList;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayDeque;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PreviewCalculator {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreviewCalculator.class);

    private PreviewCalculator() {
    }

    public static Preview calculatePreview(final DesanitizedRecipeApplicationPath path) {
        return calculatePreview(path, Set.of(), CancellationToken.NONE);
    }

    public static Preview calculatePreview(
        final DesanitizedRecipeApplicationPath path,
        final CancellationToken cancellationToken
    ) {
        return calculatePreview(path, Set.of(), cancellationToken);
    }

    public static Preview calculatePreview(
        final DesanitizedRecipeApplicationPath path,
        final Set<ResourceKey> targetResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(targetResources, "targetResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> crafted = computeCraftedAmounts(path, cancellationToken);
        final Map<ResourceKey, Long> used = toPositiveMap(path.usedResources());
        final Map<ResourceKey, Long> missing = toPositiveMap(path.missingResources());
        final Map<ResourceKey, Long> finalInventory = toRawMap(path.finalInventoryValues());
        final Set<ResourceKey> relevantResources = new LinkedHashSet<>(path.relevantResourceKeys());
        relevantResources.addAll(targetResources);

        final Map<ResourceKey, Amounts> byResource = new LinkedHashMap<>();
        for (final ResourceKey resource : relevantResources) {
            throwIfCancelled(cancellationToken);
            final long usedAmount = used.getOrDefault(resource, 0L);
            final long craftedAmount = crafted.getOrDefault(resource, 0L);
            final long finalAmount = finalInventory.getOrDefault(resource, 0L);
            final long estimatedStarting = finalAmount - craftedAmount + usedAmount;
            final long availableAmount = Math.min(usedAmount, Math.max(0L, estimatedStarting));
            final long missingAmount = missing.getOrDefault(resource, 0L);
            if (availableAmount > 0L || missingAmount > 0L || craftedAmount > 0L) {
                byResource.put(resource, new Amounts(availableAmount, missingAmount, craftedAmount));
            }
        }

        removeCraftedButUnusedRecipeResourcesDesanitized(
            path.steps(),
            byResource,
            targetResources,
            cancellationToken
        );

        final List<ResourceKey> orderedResources = orderResourcesDesanitized(
            path.steps(),
            byResource.keySet(),
            cancellationToken
        );
        final List<PreviewItem> items = new ArrayList<>(orderedResources.size());
        for (final ResourceKey resource : orderedResources) {
            throwIfCancelled(cancellationToken);
            final Amounts amounts = byResource.get(resource);
            if (amounts == null) {
                continue;
            }
            items.add(new PreviewItem(resource, amounts.available(), amounts.missing(), amounts.toCraft()));
        }

        final PreviewType type = missing.isEmpty() ? PreviewType.SUCCESS : PreviewType.MISSING_RESOURCES;
        LOGGER.info(
            "[LP] Preview calculated: type={}, items={}, craftedResources={}, usedResources={}, "
                + "missingResources={}, totalToCraft={}, totalMissing={}",
            type,
            items.size(),
            crafted.size(),
            used.size(),
            missing.size(),
            sumPreviewToCraft(items),
            sumValues(missing)
        );
        if (!missing.isEmpty()) {
            LOGGER.info("[LP] Preview missing resources: {}", missing);
        }
        return new Preview(type, List.copyOf(items), Collections.<ResourceAmount>emptyList());
    }

    public static TreePreview calculateTreePreview(
        final ResourceKey resource,
        final long amount,
        final RootStorage rootStorage,
        final CraftingOrchestrator.Initialization initialization,
        final java.util.Optional<DesanitizedRecipeApplicationPath> recipeApplicationPath
    ) {
        LOGGER.debug("[LPT] Entering calculateTreePreview()");
        Objects.requireNonNull(resource, "resource cannot be null");
        Objects.requireNonNull(rootStorage, "rootStorage cannot be null");
        Objects.requireNonNull(initialization, "initialization cannot be null");
        Objects.requireNonNull(recipeApplicationPath, "recipeApplicationPath cannot be null");

        if (recipeApplicationPath.isPresent()) {
            return buildTreePreviewFromPath(
                resource,
                amount,
                rootStorage,
                recipeApplicationPath.get(),
                initialization.relevantPatterns()
            );
        }

        final Preview preview = new Preview(
            PreviewType.NOT_AVAILABLE,
            Collections.emptyList(),
            Collections.emptyList()
        );
        return buildFallbackTreePreview(resource, amount, preview, initialization.relevantPatterns());
    }

    private static Map<ResourceKey, Long> computeCraftedAmounts(
        final DesanitizedRecipeApplicationPath path,
        final CancellationToken cancellationToken
    ) {
        final Map<ResourceKey, Long> crafted = new LinkedHashMap<>();
        for (final DesanitizedRecipeApplicationStep step : path.steps()) {
            throwIfCancelled(cancellationToken);
            if (step.timesApplied() <= 0L) {
                continue;
            }
            for (final ResourceAmount output : step.recipe().layout().outputs()) {
                throwIfCancelled(cancellationToken);
                final long produced = output.amount() * step.timesApplied();
                if (produced > 0L) {
                    crafted.merge(output.resource(), produced, Long::sum);
                }
            }
        }
        return crafted;
    }

    private static Map<ResourceKey, Long> toPositiveMap(final ResourceList resources) {
        final Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (final ResourceAmount resourceAmount : resources.copyState()) {
            if (resourceAmount.amount() > 0L) {
                result.merge(resourceAmount.resource(), resourceAmount.amount(), Long::sum);
            }
        }
        return result;
    }

    private static Map<ResourceKey, Long> toRawMap(final Map<ResourceKey, Long> resources) {
        final Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (final var entry : resources.entrySet()) {
            result.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        return result;
    }

    private static List<ResourceKey> orderResourcesDesanitized(
        final List<DesanitizedRecipeApplicationStep> steps,
        final Set<ResourceKey> nodes,
        final CancellationToken cancellationToken
    ) {
        if (nodes.isEmpty()) {
            return List.of();
        }

        final Map<ResourceKey, Set<ResourceKey>> edges = new HashMap<>();
        final Map<ResourceKey, Integer> indegree = new HashMap<>();
        final Map<ResourceKey, Integer> firstIndex = new HashMap<>();
        int nextIndex = 0;
        for (final ResourceKey node : nodes) {
            edges.put(node, new LinkedHashSet<>());
            indegree.put(node, 0);
            firstIndex.put(node, nextIndex++);
        }

        for (final DesanitizedRecipeApplicationStep step : steps) {
            throwIfCancelled(cancellationToken);
            if (step.timesApplied() <= 0L) {
                continue;
            }
            for (final ResourceAmount output : step.recipe().layout().outputs()) {
                throwIfCancelled(cancellationToken);
                final ResourceKey outputResource = output.resource();
                if (!nodes.contains(outputResource)) {
                    continue;
                }
                for (final Ingredient ingredient : step.recipe().layout().ingredients()) {
                    for (final ResourceKey inputResource : ingredient.inputs()) {
                        if (!nodes.contains(inputResource) || outputResource.equals(inputResource)) {
                            continue;
                        }
                        if (edges.get(outputResource).add(inputResource)) {
                            indegree.put(inputResource, indegree.get(inputResource) + 1);
                        }
                    }
                }
            }
        }

        final ArrayDeque<ResourceKey> queue = new ArrayDeque<>();
        nodes.stream()
            .filter(node -> indegree.get(node) == 0)
            .sorted(Comparator.comparingInt(firstIndex::get))
            .forEach(queue::addLast);

        final List<ResourceKey> ordered = new ArrayList<>(nodes.size());
        while (!queue.isEmpty()) {
            throwIfCancelled(cancellationToken);
            final ResourceKey node = queue.removeFirst();
            ordered.add(node);
            final List<ResourceKey> dependents = edges.get(node).stream()
                .sorted(Comparator.comparingInt(firstIndex::get))
                .toList();
            for (final ResourceKey dependent : dependents) {
                final int next = indegree.get(dependent) - 1;
                indegree.put(dependent, next);
                if (next == 0) {
                    queue.addLast(dependent);
                }
            }
        }

        if (ordered.size() == nodes.size()) {
            return List.copyOf(ordered);
        }

        return nodes.stream()
            .sorted(Comparator.comparingInt(firstIndex::get))
            .toList();
    }

    private static long sumValues(final Map<ResourceKey, Long> values) {
        long total = 0L;
        for (final long value : values.values()) {
            total += value;
        }
        return total;
    }

    private static void removeCraftedButUnusedRecipeResourcesDesanitized(
        final List<DesanitizedRecipeApplicationStep> steps,
        final Map<ResourceKey, Amounts> byResource,
        final Set<ResourceKey> targetResources,
        final CancellationToken cancellationToken
    ) {
        final Set<ResourceKey> resourcesUsedInRecipes = new LinkedHashSet<>();
        for (final DesanitizedRecipeApplicationStep step : steps) {
            throwIfCancelled(cancellationToken);
            if (step.timesApplied() <= 0L) {
                continue;
            }
            for (final Ingredient ingredient : step.recipe().layout().ingredients()) {
                throwIfCancelled(cancellationToken);
                if (ingredient.amount() > 0L) {
                    resourcesUsedInRecipes.addAll(ingredient.inputs());
                }
            }
        }

        byResource.entrySet().removeIf(entry -> {
            throwIfCancelled(cancellationToken);
            final Amounts amounts = entry.getValue();
            return amounts.available() == 0L
                && amounts.missing() == 0L
                && amounts.toCraft() > 0L
                && !targetResources.contains(entry.getKey())
                && !resourcesUsedInRecipes.contains(entry.getKey());
        });
    }

    private static long sumPreviewToCraft(final List<PreviewItem> items) {
        long total = 0L;
        for (final PreviewItem item : items) {
            total += item.toCraft();
        }
        return total;
    }

    private static TreePreview buildTreePreviewFromPath(
        final ResourceKey resource,
        final long amount,
        final RootStorage rootStorage,
        final DesanitizedRecipeApplicationPath path,
        final Collection<Pattern> relevantPatterns
    ) {
        LOGGER.debug("[LPT] Entering buildTreePreviewFromPath()");
        return buildTreePreviewFromSteps(resource, amount, rootStorage, path, relevantPatterns);
    }

    private static TreePreview buildTreePreviewFromSteps(
        final ResourceKey resource,
        final long amount,
        final RootStorage rootStorage,
        final DesanitizedRecipeApplicationPath path,
        final Collection<Pattern> relevantPatterns
    ) {
        LOGGER.debug("[LPT] Entering buildTreePreviewFromSteps()");
        final NodeBuilder root = new NodeBuilder(resource, amount);
        final ArrayDeque<PendingNode> frontier = new ArrayDeque<>();
        frontier.add(new PendingNode(root, amount));

        final List<DesanitizedRecipeApplicationStep> reversedSteps = new ArrayList<>(path.steps());
        Collections.reverse(reversedSteps);

        for (final DesanitizedRecipeApplicationStep step : reversedSteps) {
            if (frontier.isEmpty()) {
                break;
            }

            final List<PendingNode> matchedNodes = new ArrayList<>();
            for (final PendingNode pendingNode : frontier) {
                if (getDesanitizedOutputAmount(step.recipe(), pendingNode.node.resource) > 0L) {
                    matchedNodes.add(pendingNode);
                }
            }
            if (matchedNodes.isEmpty()) {
                continue;
            }

            long remainingIterations = step.timesApplied();
            final List<PendingNode> nextNodes = new ArrayList<>();
            for (final PendingNode pendingNode : matchedNodes) {
                frontier.remove(pendingNode);
                if (remainingIterations <= 0) {
                    nextNodes.add(pendingNode);
                    continue;
                }

                final NodeBuilder node = pendingNode.node;
                final long requiredAmount = pendingNode.requiredAmount;
                final long outputPerIteration = getDesanitizedOutputAmount(step.recipe(), node.resource);
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

                for (final Ingredient ingredient : step.recipe().layout().ingredients()) {
                    if (ingredient.inputs().isEmpty()) {
                        continue;
                    }
                    final ResourceKey inputResource = ingredient.inputs().getFirst();
                    final long childAmount = ingredient.amount() * usedIterations;
                    if (childAmount <= 0) {
                        continue;
                    }

                    final NodeBuilder child = node.addChild(inputResource, childAmount);
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
        for (final DesanitizedRecipeApplicationStep step : path.steps()) {
            for (final ResourceAmount output : step.recipe().layout().outputs()) {
                producibleResources.add(output.resource());
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

        root.amount = Math.max(amount, root.toCraft);

        final PreviewType type = hasMissing(root) ? PreviewType.MISSING_RESOURCES : PreviewType.SUCCESS;
        return new TreePreview(type, root.build(), Collections.emptyList());
    }

    private static TreePreview buildFallbackTreePreview(
        final ResourceKey requestedResource,
        final long requestedAmount,
        final Preview preview,
        final Collection<Pattern> relevantPatterns
    ) {
        LOGGER.debug("[LPT] Entering buildFallbackTreePreview()");
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

    private static void attachPatternChildren(
        final NodeBuilder node,
        final Map<ResourceKey, PreviewItem> previewItemsByResource,
        final Collection<Pattern> relevantPatterns,
        final Set<ResourceKey> assignedResources,
        final Set<ResourceKey> path
    ) {
        LOGGER.debug("[LPT] Entering attachPatternChildren()");
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

    private static Pattern findPatternProducing(
        final ResourceKey resource,
        final Collection<Pattern> patterns
    ) {
        LOGGER.debug("[LPT] Entering findPatternProducing()");
        for (final Pattern pattern : patterns) {
            for (final ResourceAmount output : pattern.layout().outputs()) {
                if (output.resource().equals(resource)) {
                    return pattern;
                }
            }
        }
        return null;
    }

    private static ResourceKey pickInputResourceForIngredient(
        final Ingredient ingredient,
        final Map<ResourceKey, PreviewItem> previewItemsByResource
    ) {
        LOGGER.debug("[LPT] Entering pickInputResourceForIngredient()");
        for (final ResourceKey input : ingredient.inputs()) {
            if (previewItemsByResource.containsKey(input)) {
                return input;
            }
        }
        return ingredient.inputs().isEmpty() ? null : ingredient.inputs().getFirst();
    }

    private static void applyPreviewItem(
        final NodeBuilder node,
        final PreviewItem item
    ) {
        LOGGER.debug("[LPT] Entering applyPreviewItem()");
        if (item == null) {
            return;
        }
        node.available += item.available();
        node.missing += item.missing();
        node.toCraft += item.toCraft();
    }

    private static boolean hasMissing(final NodeBuilder node) {
        LOGGER.debug("[LPT] Entering hasMissing()");
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
        LOGGER.debug("[LPT] Entering ceilDiv()");
        return ((numerator - 1) / denominator) + 1;
    }

    private static long getDesanitizedOutputAmount(final DesanitizedRecipe recipe, final ResourceKey resource) {
        long total = 0L;
        for (final ResourceAmount output : recipe.layout().outputs()) {
            if (output.resource().equals(resource)) {
                total += output.amount();
            }
        }
        return total;
    }

    private static final class PendingNode {
        private final NodeBuilder node;
        private final long requiredAmount;

        private PendingNode(final NodeBuilder node, final long requiredAmount) {
            this.node = node;
            this.requiredAmount = requiredAmount;
        }
    }

    private static final class NodeBuilder {
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

    private static void throwIfCancelled(final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP preview calculator cancelled");
        }
    }

    private record Amounts(long available, long missing, long toCraft) {
    }
}
