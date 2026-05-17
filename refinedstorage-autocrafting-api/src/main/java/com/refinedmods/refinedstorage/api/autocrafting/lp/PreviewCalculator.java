package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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

    public static Preview calculatePreview(final RecipeApplicationPath path) {
        return calculatePreview(path, Set.of(), CancellationToken.NONE);
    }

    public static Preview calculatePreview(
        final RecipeApplicationPath path,
        final CancellationToken cancellationToken
    ) {
        return calculatePreview(path, Set.of(), cancellationToken);
    }

    public static Preview calculatePreview(
        final RecipeApplicationPath path,
        final Set<ResourceKey> targetResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(targetResources, "targetResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final RecipeApplicationSet applicationSet = path.applicationSet();
        final Map<ResourceKey, Long> crafted = computeCraftedAmounts(path, cancellationToken);
        final Map<ResourceKey, Long> used = toPositiveMap(applicationSet.usedResources(), cancellationToken);
        final Map<ResourceKey, Long> missing = toPositiveMap(applicationSet.missingResources(), cancellationToken);
        final Map<ResourceKey, Long> finalInventory = toRawMap(
            applicationSet.finalInventoryValues(),
            cancellationToken
        );

        final Set<ResourceKey> universe = new LinkedHashSet<>();
        for (final MultiResourceKey key : applicationSet.relevantResourceKeys()) {
            universe.add(toPreviewResourceKey(key));
        }
        universe.addAll(crafted.keySet());
        universe.addAll(used.keySet());
        universe.addAll(missing.keySet());
        universe.addAll(finalInventory.keySet());

        final Map<ResourceKey, Long> available = new LinkedHashMap<>();
        for (final ResourceKey resource : universe) {
            throwIfCancelled(cancellationToken);
            final long usedAmount = used.getOrDefault(resource, 0L);
            final long craftedAmount = crafted.getOrDefault(resource, 0L);
            final long finalAmount = finalInventory.getOrDefault(resource, 0L);
            final long estimatedStarting = finalAmount - craftedAmount + usedAmount;
            final long availableAmount = Math.min(usedAmount, Math.max(0L, estimatedStarting));
            if (availableAmount > 0L) {
                available.put(resource, availableAmount);
            }
        }

        final Map<ResourceKey, Amounts> byResource = new LinkedHashMap<>();
        for (final ResourceKey resource : universe) {
            throwIfCancelled(cancellationToken);
            final long availableAmount = available.getOrDefault(resource, 0L);
            final long missingAmount = missing.getOrDefault(resource, 0L);
            final long craftedAmount = crafted.getOrDefault(resource, 0L);
            if (availableAmount > 0L || missingAmount > 0L || craftedAmount > 0L) {
                byResource.put(resource, new Amounts(availableAmount, missingAmount, craftedAmount));
            }
        }

        // COMPATABILITY
        // Comment this line to display byproducts as to_craft instead of hiding them
        removeCraftedButUnusedRecipeResources(path.steps(), byResource, targetResources, cancellationToken);

        final List<ResourceKey> orderedResources = orderResources(path.steps(), byResource.keySet(), cancellationToken);
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
        final RecipeApplicationPath sanitizedPath = RecipeDesanitizer.toRecipeApplicationPath(path);
        return calculatePreview(sanitizedPath, targetResources, cancellationToken);
    }

    private static Map<ResourceKey, Long> computeCraftedAmounts(
        final RecipeApplicationPath path,
        final CancellationToken cancellationToken
    ) {
        final Map<ResourceKey, Long> crafted = new LinkedHashMap<>();
        for (final RecipeApplicationStep step : path.steps()) {
            throwIfCancelled(cancellationToken);
            if (step.timesApplied() <= 0L) {
                continue;
            }
            for (final Map.Entry<MultiResourceKey, Long> output : step.recipe().output()) {
                throwIfCancelled(cancellationToken);
                final long produced = output.getValue() * step.timesApplied();
                if (produced > 0L) {
                    crafted.merge(toPreviewResourceKey(output.getKey()), produced, Long::sum);
                }
            }
        }
        return crafted;
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
            for (final Map.Entry<ResourceKey, Long> output : step.recipe().output().entrySet()) {
                throwIfCancelled(cancellationToken);
                final long produced = output.getValue() * step.timesApplied();
                if (produced > 0L) {
                    crafted.merge(output.getKey(), produced, Long::sum);
                }
            }
        }
        return crafted;
    }

    private static Map<ResourceKey, Long> toPositiveMap(
        final ResourcePool pool,
        final CancellationToken cancellationToken
    ) {
        final Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (final Map.Entry<MultiResourceKey, Long> entry : pool) {
            throwIfCancelled(cancellationToken);
            if (entry.getValue() > 0L) {
                result.merge(toPreviewResourceKey(entry.getKey()), entry.getValue(), Long::sum);
            }
        }
        return result;
    }

    private static Map<ResourceKey, Long> toRawMap(
        final ResourcePool pool,
        final CancellationToken cancellationToken
    ) {
        final Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (final Map.Entry<MultiResourceKey, Long> entry : pool) {
            throwIfCancelled(cancellationToken);
            result.merge(toPreviewResourceKey(entry.getKey()), entry.getValue(), Long::sum);
        }
        return result;
    }

    private static Map<ResourceKey, Long> toPositiveMap(final Map<ResourceKey, Long> resources) {
        final Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (final var entry : resources.entrySet()) {
            if (entry.getValue() > 0L) {
                result.merge(entry.getKey(), entry.getValue(), Long::sum);
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

    private static List<ResourceKey> orderResources(
        final List<RecipeApplicationStep> steps,
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

        for (final RecipeApplicationStep step : steps) {
            throwIfCancelled(cancellationToken);
            if (step.timesApplied() <= 0L) {
                continue;
            }
            for (final Map.Entry<MultiResourceKey, Long> output : step.recipe().output()) {
                throwIfCancelled(cancellationToken);
                final ResourceKey outputResource = toPreviewResourceKey(output.getKey());
                if (!nodes.contains(outputResource)) {
                    continue;
                }
                for (final Map.Entry<MultiResourceKey, Long> input : step.recipe().input()) {
                    final ResourceKey inputResource = toPreviewResourceKey(input.getKey());
                    if (!nodes.contains(inputResource) || outputResource.equals(inputResource)) {
                        continue;
                    }
                    if (edges.get(outputResource).add(inputResource)) {
                        indegree.put(inputResource, indegree.get(inputResource) + 1);
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

        // Fallback for any remaining cycles: keep deterministic insertion order.
        LOGGER.info(
            "[LP] Preview resource ordering encountered cycle(s); falling back to insertion order for {} resources",
            nodes.size()
        );
        return nodes.stream()
            .sorted(Comparator.comparingInt(firstIndex::get))
            .toList();
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
            for (final Map.Entry<ResourceKey, Long> output : step.recipe().output().entrySet()) {
                throwIfCancelled(cancellationToken);
                final ResourceKey outputResource = output.getKey();
                if (!nodes.contains(outputResource)) {
                    continue;
                }
                for (final Map.Entry<ResourceKey, Long> input : step.recipe().input().entrySet()) {
                    final ResourceKey inputResource = input.getKey();
                    if (!nodes.contains(inputResource) || outputResource.equals(inputResource)) {
                        continue;
                    }
                    if (edges.get(outputResource).add(inputResource)) {
                        indegree.put(inputResource, indegree.get(inputResource) + 1);
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

    private static void removeCraftedButUnusedRecipeResources(
        final List<RecipeApplicationStep> steps,
        final Map<ResourceKey, Amounts> byResource,
        final Set<ResourceKey> targetResources,
        final CancellationToken cancellationToken
    ) {
        final Set<ResourceKey> resourcesUsedInRecipes = new LinkedHashSet<>();
        for (final RecipeApplicationStep step : steps) {
            throwIfCancelled(cancellationToken);
            if (step.timesApplied() <= 0L) {
                continue;
            }
            for (final Map.Entry<MultiResourceKey, Long> input : step.recipe().input()) {
                throwIfCancelled(cancellationToken);
                if (input.getValue() > 0L) {
                    resourcesUsedInRecipes.add(toPreviewResourceKey(input.getKey()));
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
            for (final Map.Entry<ResourceKey, Long> input : step.recipe().input().entrySet()) {
                throwIfCancelled(cancellationToken);
                if (input.getValue() > 0L) {
                    resourcesUsedInRecipes.add(input.getKey());
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

    private static void throwIfCancelled(final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP preview calculator cancelled");
        }
    }

    private static ResourceKey toPreviewResourceKey(final MultiResourceKey key) {
        if (!key.members().isEmpty()) {
            return key.members().getFirst();
        }
        throw new IllegalStateException("MultiResourceKey has no members: " + key);
    }

    private record Amounts(long available, long missing, long toCraft) {
    }
}
