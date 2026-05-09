package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Contains functions that boil down a list of patterns with fuzzy inputs into a smaller list of sanitized recipes
public class RecipeSanitizer {
    private RecipeSanitizer() {
    }

    /**
     * Filters patterns to only those producing resources in the target resources' crafting tree.
     * Handles cycles using a visited set.
     */
    public static List<Pattern> collectRelevantPatterns(
        final List<Pattern> patterns,
        final List<ResourceKey> targetResources
    ) {
        Objects.requireNonNull(patterns, "patterns cannot be null");
        Objects.requireNonNull(targetResources, "targetResources cannot be null");
        final Map<ResourceKey, List<Pattern>> outputToPatterns = buildOutputToPatterns(patterns);
        final Set<UUID> visited = new LinkedHashSet<>();
        final List<Pattern> result = new ArrayList<>();
        final Deque<ResourceKey> queue = new ArrayDeque<>(targetResources);
        while (!queue.isEmpty()) {
            final ResourceKey resource = queue.poll();
            final List<Pattern> producers = outputToPatterns.get(resource);
            if (producers == null) {
                continue;
            }
            for (final Pattern pattern : producers) {
                if (!visited.add(pattern.id())) {
                    continue;
                }
                result.add(pattern);
                for (final Ingredient ingredient : pattern.layout().ingredients()) {
                    for (final ResourceKey input : ingredient.inputs()) {
                        queue.add(input);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Removes fuzzy ingredient options that are neither in the available resources set nor craftable
     * by any provided pattern. If filtering would leave 0 options for an ingredient, the first is kept.
     */
    public static List<Pattern> trimFuzzyPatterns(
        final List<Pattern> patterns,
        final Set<ResourceKey> availableResources
    ) {
        Objects.requireNonNull(patterns, "patterns cannot be null");
        Objects.requireNonNull(availableResources, "availableResources cannot be null");
        final Set<ResourceKey> eligibleResources = new LinkedHashSet<>(availableResources);
        eligibleResources.addAll(collectCraftableResources(patterns));
        final List<Pattern> result = new ArrayList<>(patterns.size());
        for (final Pattern pattern : patterns) {
            result.add(trimFuzzyPattern(pattern, eligibleResources));
        }
        return result;
    }

    /**
     * Returns a list of MultiResourceKeys covering every resource in the provided patterns.
     * Computes disjoint sets of input resources
     * Fuzzy input resources that always appear together (same ingredient slots across all patterns)
     * are grouped into the same key. Output resources always receive their own singleton key.
     * Non-fuzzy input resources that also appear as fuzzy options in other slots receive
     * singleton keys, since they are not fully interchangeable with the other options.
     */
    public static MultiResourceKeyIndex computeMultiResourceKeyIndex(final List<Pattern> patterns) {
        Objects.requireNonNull(patterns, "patterns cannot be null");
        final Set<ResourceKey> outputResources = collectOutputResources(patterns);

        // For every non-output ingredient resource, record which slots it appears in.
        // Fuzzy slots: "f:pi:ii", non-fuzzy slots: "s:pi:ii".
        // Including non-fuzzy slot keys ensures a resource that appears both as a
        // non-fuzzy input and as one option in a fuzzy ingredient is never merged.
        final Map<ResourceKey, Set<String>> resourceParticipation = new LinkedHashMap<>();
        for (int pi = 0; pi < patterns.size(); pi++) {
            final List<Ingredient> ingredients = patterns.get(pi).layout().ingredients();
            for (int ii = 0; ii < ingredients.size(); ii++) {
                final Ingredient ingredient = ingredients.get(ii);
                if (ingredient.inputs().size() == 1) {
                    final ResourceKey input = ingredient.inputs().getFirst();
                    if (!outputResources.contains(input)) {
                        resourceParticipation
                            .computeIfAbsent(input, k -> new LinkedHashSet<>())
                            .add("s:" + pi + ":" + ii);
                    }
                } else {
                    final String slotKey = "f:" + pi + ":" + ii;
                    for (final ResourceKey input : ingredient.inputs()) {
                        if (outputResources.contains(input)) {
                            continue;
                        }
                        resourceParticipation
                            .computeIfAbsent(input, k -> new LinkedHashSet<>())
                            .add(slotKey);
                    }
                }
            }
        }

        // Group resources with identical participation sets into one MultiResourceKey
        final Map<Set<String>, List<ResourceKey>> groups = new LinkedHashMap<>();
        for (final Map.Entry<ResourceKey, Set<String>> entry : resourceParticipation.entrySet()) {
            groups.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        final List<MultiResourceKey> result = new ArrayList<>();
        final Set<ResourceKey> alreadyGrouped = new LinkedHashSet<>();
        for (final List<ResourceKey> members : groups.values()) {
            result.add(new MultiResourceKey(members));
            alreadyGrouped.addAll(members);
        }

        // Add singleton keys for outputs and any resource not already placed in a group
        final Set<ResourceKey> allResources = collectAllResources(patterns);
        for (final ResourceKey resource : allResources) {
            if (alreadyGrouped.add(resource)) {
                result.add(new MultiResourceKey(List.of(resource)));
            }
        }
        return new MultiResourceKeyIndex(result, buildMemberToMrkMap(result));
    }

    public static List<MultiResourceKey> computeMultiResourceKeys(final List<Pattern> patterns) {
        return computeMultiResourceKeyIndex(patterns).multiResourceKeys();
    }

    /**
     * Replaces fuzzy inputs in each pattern with their corresponding MultiResourceKey.
     * Generates all allocation combinations (stars-and-bars) for multi-unit fuzzy ingredients.
     * Non-fuzzy (single-option) inputs are kept as their original ResourceKey.
     */
    public static List<SanitizedRecipe> toSanitizedRecipes(
        final List<Pattern> patterns,
        final List<MultiResourceKey> multiResourceKeys,
        final Map<UUID, Integer> patternPriorities
    ) {
        Objects.requireNonNull(patterns, "patterns cannot be null");
        Objects.requireNonNull(multiResourceKeys, "multiResourceKeys cannot be null");
        Objects.requireNonNull(patternPriorities, "patternPriorities cannot be null");
        final Map<ResourceKey, MultiResourceKey> resourceToKey = buildResourceToKeyMap(multiResourceKeys);
        return toSanitizedRecipes(patterns, resourceToKey, patternPriorities);
    }

    public static List<SanitizedRecipe> toSanitizedRecipes(
        final List<Pattern> patterns,
        final MultiResourceKeyIndex multiResourceKeyIndex,
        final Map<UUID, Integer> patternPriorities
    ) {
        Objects.requireNonNull(patterns, "patterns cannot be null");
        Objects.requireNonNull(multiResourceKeyIndex, "multiResourceKeyIndex cannot be null");
        Objects.requireNonNull(patternPriorities, "patternPriorities cannot be null");

        final Map<ResourceKey, MultiResourceKey> resourceToKey = new LinkedHashMap<>();
        for (final Map.Entry<ResourceKey, MultiResourceKey> entry : multiResourceKeyIndex.mrkByMember().entrySet()) {
            resourceToKey.put(entry.getKey(), entry.getValue());
        }
        return toSanitizedRecipes(patterns, resourceToKey, patternPriorities);
    }

    private static List<SanitizedRecipe> toSanitizedRecipes(
        final List<Pattern> patterns,
        final Map<ResourceKey, MultiResourceKey> resourceToKey,
        final Map<UUID, Integer> patternPriorities
    ) {
        final List<SanitizedRecipe> result = new ArrayList<>();
        for (final Pattern pattern : patterns) {
            expandPattern(pattern, resourceToKey, patternPriorities, result);
        }
        return result;
    }

    // -------- private helpers --------

    private static Map<ResourceKey, List<Pattern>> buildOutputToPatterns(final List<Pattern> patterns) {
        final Map<ResourceKey, List<Pattern>> map = new LinkedHashMap<>();
        for (final Pattern pattern : patterns) {
            for (final ResourceAmount ra : pattern.layout().outputs()) {
                map.computeIfAbsent(ra.resource(), k -> new ArrayList<>()).add(pattern);
            }
            for (final ResourceAmount ra : pattern.layout().byproducts()) {
                map.computeIfAbsent(ra.resource(), k -> new ArrayList<>()).add(pattern);
            }
        }
        return map;
    }

    private static Set<ResourceKey> collectCraftableResources(final List<Pattern> patterns) {
        final Set<ResourceKey> craftable = new LinkedHashSet<>();
        for (final Pattern pattern : patterns) {
            for (final ResourceAmount ra : pattern.layout().outputs()) {
                craftable.add(ra.resource());
            }
        }
        return craftable;
    }

    private static Set<ResourceKey> collectOutputResources(final List<Pattern> patterns) {
        final Set<ResourceKey> outputs = new LinkedHashSet<>();
        for (final Pattern pattern : patterns) {
            for (final ResourceAmount ra : pattern.layout().outputs()) {
                outputs.add(ra.resource());
            }
            for (final ResourceAmount ra : pattern.layout().byproducts()) {
                outputs.add(ra.resource());
            }
        }
        return outputs;
    }

    private static Set<ResourceKey> collectAllResources(final List<Pattern> patterns) {
        final Set<ResourceKey> all = new LinkedHashSet<>();
        for (final Pattern pattern : patterns) {
            for (final ResourceAmount ra : pattern.layout().outputs()) {
                all.add(ra.resource());
            }
            for (final ResourceAmount ra : pattern.layout().byproducts()) {
                all.add(ra.resource());
            }
            for (final Ingredient ingredient : pattern.layout().ingredients()) {
                all.addAll(ingredient.inputs());
            }
        }
        return all;
    }

    private static Pattern trimFuzzyPattern(
        final Pattern pattern,
        final Set<ResourceKey> eligibleResources
    ) {
        boolean modified = false;
        final List<Ingredient> newIngredients = new ArrayList<>(pattern.layout().ingredients().size());
        for (final Ingredient ingredient : pattern.layout().ingredients()) {
            if (ingredient.inputs().size() <= 1) {
                newIngredients.add(ingredient);
                continue;
            }
            List<ResourceKey> filtered = ingredient.inputs().stream()
                .filter(eligibleResources::contains)
                .toList();
            if (filtered.isEmpty()) {
                filtered = List.of(ingredient.inputs().getFirst());
            }
            if (filtered.size() != ingredient.inputs().size()) {
                modified = true;
                newIngredients.add(new Ingredient(ingredient.amount(), filtered));
            } else {
                newIngredients.add(ingredient);
            }
        }
        if (!modified) {
            return pattern;
        }
        return new Pattern(pattern.id(), new PatternLayout(
            newIngredients,
            pattern.layout().outputs(),
            pattern.layout().byproducts(),
            pattern.layout().type()
        ));
    }

    private static Map<ResourceKey, MultiResourceKey> buildResourceToKeyMap(
        final List<MultiResourceKey> multiResourceKeys
    ) {
        final Map<ResourceKey, MultiResourceKey> map = new LinkedHashMap<>();
        for (final MultiResourceKey mrk : multiResourceKeys) {
            for (final ResourceKey member : mrk.members()) {
                map.put(member, mrk);
            }
        }
        return map;
    }

    private static Map<ResourceKey, MultiResourceKey> buildMemberToMrkMap(
        final List<MultiResourceKey> multiResourceKeys
    ) {
        final Map<ResourceKey, MultiResourceKey> map = new LinkedHashMap<>();
        for (final MultiResourceKey mrk : multiResourceKeys) {
            for (final ResourceKey member : mrk.members()) {
                map.putIfAbsent(member, mrk);
            }
        }
        return map;
    }

    // Converts RS types (RootStorage with ResourceKeys) to LP types (ResourcePool with MRKs).
    // Aggregates resource amounts by their corresponding MultiResourceKey grouping.
    public static ResourcePool buildRelevantStartingResources(
        final RootStorage rootStorage,
        final Collection<MultiResourceKey> relevantResources
    ) {
        final ResourcePool result = ResourcePool.empty();
        final Set<MultiResourceKey> processedMultiResourceKeys = new LinkedHashSet<>();

        for (final MultiResourceKey multiResourceKey : relevantResources) {
            if (!processedMultiResourceKeys.add(multiResourceKey)) {
                continue;
            }

            long total = 0L;
            for (final ResourceKey member : multiResourceKey.members()) {
                total += rootStorage.get(member);
            }
            if (total > 0L) {
                result.setAmount(multiResourceKey, total);
            }
        }

        return result;
    }

    // Converts RS types (RootStorage with ResourceKeys and MRK groupings) to a mapping table.
    // Each distinct ResourceKey member gets mapped to its amount in storage.
    public static Map<ResourceKey, Long> buildSanitizedStartingResources(
        final RootStorage rootStorage,
        final Collection<MultiResourceKey> relevantResources
    ) {
        final Map<ResourceKey, Long> result = new LinkedHashMap<>();
        for (final MultiResourceKey multiResourceKey : relevantResources) {
            for (final ResourceKey member : multiResourceKey.members()) {
                result.putIfAbsent(member, rootStorage.get(member));
            }
        }
        return Map.copyOf(result);
    }

    public record MultiResourceKeyIndex(
        List<MultiResourceKey> multiResourceKeys,
        Map<ResourceKey, MultiResourceKey> mrkByMember
    ) {
        public MultiResourceKeyIndex {
            Objects.requireNonNull(multiResourceKeys, "multiResourceKeys cannot be null");
            Objects.requireNonNull(mrkByMember, "mrkByMember cannot be null");
            multiResourceKeys = List.copyOf(multiResourceKeys);
            mrkByMember = Map.copyOf(mrkByMember);
        }
    }

    private static void expandPattern(
        final Pattern pattern,
        final Map<ResourceKey, MultiResourceKey> resourceToKey,
        final Map<UUID, Integer> patternPriorities,
        final List<SanitizedRecipe> result
    ) {
        final ResourcePool output = buildOutputPool(pattern);

        // Non-fuzzy inputs (single-option ingredients) pass through as-is.
        // Fuzzy inputs are grouped by their representative-key list so that
        // ingredients with identical option sets have their amounts summed.
        final Map<MultiResourceKey, Long> nonFuzzyInputs = new LinkedHashMap<>();
        final Map<List<MultiResourceKey>, Long> fuzzyGroups = new LinkedHashMap<>();

        for (final Ingredient ingredient : pattern.layout().ingredients()) {
            if (ingredient.inputs().size() == 1) {
                final MultiResourceKey mapped = resourceToKey.get(ingredient.inputs().getFirst());
                if (mapped == null) {
                    throw new IllegalStateException(
                        "No MultiResourceKey mapping found for ingredient input "
                            + ingredient.inputs().getFirst()
                    );
                }
                nonFuzzyInputs.merge(mapped, ingredient.amount(), Long::sum);
            } else {
                final List<MultiResourceKey> repKeys = mapToRepresentativeKeys(ingredient.inputs(), resourceToKey);
                fuzzyGroups.merge(repKeys, ingredient.amount(), Long::sum);
            }
        }

        // Cross-product of stars-and-bars allocations across all fuzzy groups
        List<Map<MultiResourceKey, Long>> variants = new ArrayList<>();
        variants.add(new LinkedHashMap<>());
        for (final Map.Entry<List<MultiResourceKey>, Long> entry : fuzzyGroups.entrySet()) {
            final List<Map<MultiResourceKey, Long>> groupAllocations =
                generateAllocations(entry.getKey(), entry.getValue());
            final List<Map<MultiResourceKey, Long>> newVariants = new ArrayList<>();
            for (final Map<MultiResourceKey, Long> existing : variants) {
                for (final Map<MultiResourceKey, Long> allocation : groupAllocations) {
                    final Map<MultiResourceKey, Long> combined = new LinkedHashMap<>(existing);
                    allocation.forEach((k, v) -> combined.merge(k, v, Long::sum));
                    newVariants.add(combined);
                }
            }
            variants = newVariants;
        }

        // Merge non-fuzzy inputs into every variant
        for (final Map<MultiResourceKey, Long> variant : variants) {
            nonFuzzyInputs.forEach((k, v) -> variant.merge(k, v, Long::sum));
        }

        final boolean hasFuzzy = !fuzzyGroups.isEmpty();
        for (final Map<MultiResourceKey, Long> variantInput : variants) {
            final ResourcePool input = new ResourcePool(variantInput);
            final UUID recipeId = hasFuzzy
                ? deterministicVariantRecipeId(pattern.id(), input)
                : pattern.id();
            final long priority = patternPriorities.getOrDefault(pattern.id(), 0);
            final long insertionOrder = result.size();
            result.add(new SanitizedRecipe(recipeId, pattern.id(), input, output, priority, insertionOrder));
        }
    }

    private static UUID deterministicVariantRecipeId(final UUID sourcePatternId, final ResourcePool input) {
        final StringBuilder builder = new StringBuilder(sourcePatternId.toString());
        input.resourceKeys().stream()
            .sorted((left, right) -> left.toString().compareTo(right.toString()))
            .forEach(resource -> builder
                .append('|')
                .append(resource)
                .append('=')
                .append(input.getAmount(resource)));
        return UUID.nameUUIDFromBytes(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<MultiResourceKey> mapToRepresentativeKeys(
        final List<ResourceKey> inputs,
        final Map<ResourceKey, MultiResourceKey> resourceToKey
    ) {
        final List<MultiResourceKey> repKeys = new ArrayList<>();
        final Set<MultiResourceKey> seen = new LinkedHashSet<>();
        for (final ResourceKey input : inputs) {
            final MultiResourceKey rep = resourceToKey.get(input);
            if (rep == null) {
                throw new IllegalStateException("No MultiResourceKey mapping found for ingredient input " + input);
            }
            if (seen.add(rep)) {
                repKeys.add(rep);
            }
        }
        return repKeys;
    }

    private static List<Map<MultiResourceKey, Long>> generateAllocations(
        final List<MultiResourceKey> keys,
        final long totalAmount
    ) {
        if (keys.isEmpty()) {
            return List.of();
        }
        if (keys.size() == 1) {
            return List.of(Map.of(keys.getFirst(), totalAmount));
        }
        final List<Map<MultiResourceKey, Long>> allocations = new ArrayList<>();
        generateAllocationsRecursive(keys, 0, totalAmount, new LinkedHashMap<>(), allocations);
        return allocations;
    }

    private static void generateAllocationsRecursive(
        final List<MultiResourceKey> keys,
        final int index,
        final long remaining,
        final Map<MultiResourceKey, Long> current,
        final List<Map<MultiResourceKey, Long>> results
    ) {
        if (index == keys.size() - 1) {
            if (remaining > 0) {
                current.put(keys.get(index), remaining);
            }
            results.add(new LinkedHashMap<>(current));
            current.remove(keys.get(index));
            return;
        }
        for (long amount = 0; amount <= remaining; amount++) {
            if (amount > 0) {
                current.put(keys.get(index), amount);
            }
            generateAllocationsRecursive(keys, index + 1, remaining - amount, current, results);
            current.remove(keys.get(index));
        }
    }

    private static ResourcePool buildOutputPool(final Pattern pattern) {
        final ResourcePool pool = new ResourcePool();
        for (final ResourceAmount ra : pattern.layout().outputs()) {
            pool.addAmount(new MultiResourceKey(List.of(ra.resource())), ra.amount());
        }
        for (final ResourceAmount ra : pattern.layout().byproducts()) {
            pool.addAmount(new MultiResourceKey(List.of(ra.resource())), ra.amount());
        }
        return pool;
    }
}
