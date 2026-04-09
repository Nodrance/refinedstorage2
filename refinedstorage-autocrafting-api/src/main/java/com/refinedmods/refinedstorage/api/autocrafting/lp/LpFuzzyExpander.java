package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Expands fuzzy recipes (recipes with multi-option ingredient slots) into concrete
 * LP recipe variants by:
 * <ol>
 *   <li>Partitioning each fuzzy ingredient's options into subsets based on craftability</li>
 *   <li>Generating all allocation combinations across subsets (stars-and-bars)</li>
 *   <li>Creating concrete {@link LpPatternRecipe} variants for the LP solver</li>
 * </ol>
 * Also provides utilities to augment starting resources with subset amounts,
 * decode subset-based plans back to concrete resources, and decode plan steps
 * from variant recipes back to original patterns.
 */
public final class LpFuzzyExpander {
    static final int MAX_VARIANTS_PER_RECIPE = 256;

    private LpFuzzyExpander() {
    }

    /**
     * Expands patterns (potentially fuzzy) into concrete LP recipe variants.
     * Non-fuzzy patterns pass through as-is via {@link LpPatternRecipe#fromPattern}.
     * Fuzzy patterns are expanded into multiple variants, one per allocation combination
     * of their ingredient subsets.
        *
        * <p>Subsets are computed globally across all patterns to prevent overlapping:
     * resources that participate in exactly the same set of fuzzy ingredient slots
     * are grouped into one shared subset, ensuring the LP solver never double-counts.
     *
     * @param patterns           the patterns in the crafting tree
     * @param startingResources  resources currently available in storage
     * @param craftableResources resources producible by some recipe in the tree
     * @return expansion result with recipes, variant-to-source mappings, and created subsets
     */
    public static FuzzyExpansionResult expandFuzzyPatterns(
        final Collection<Pattern> patterns,
        final LpResourceSet startingResources,
        final Set<ResourceKey> craftableResources
    ) {
        Objects.requireNonNull(patterns, "patterns cannot be null");
        Objects.requireNonNull(startingResources, "startingResources cannot be null");
        Objects.requireNonNull(craftableResources, "craftableResources cannot be null");

        final List<Pattern> sorted = patterns.stream()
            .sorted(Comparator.comparing(Pattern::id))
            .toList();

        // Compute non-overlapping global subsets before expanding any individual pattern
        final Map<ResourceKey, ResourceKey> globalSubsets = computeGlobalSubsets(
            sorted, craftableResources, startingResources
        );

        final List<LpPatternRecipe> expandedRecipes = new ArrayList<>();
        final Map<UUID, UUID> variantToSourcePattern = new LinkedHashMap<>();
        final Set<LpResourceSubset> createdSubsets = new LinkedHashSet<>();

        for (int index = 0; index < sorted.size(); index++) {
            final Pattern pattern = sorted.get(index);
            final boolean hasFuzzy = pattern.layout().ingredients().stream()
                .anyMatch(ing -> ing.inputs().size() > 1);

            if (!hasFuzzy) {
                expandedRecipes.add(LpPatternRecipe.fromPattern(pattern, index));
                continue;
            }

            expandFuzzyPattern(
                pattern, index, craftableResources, globalSubsets,
                expandedRecipes, variantToSourcePattern, createdSubsets
            );
        }

        return new FuzzyExpansionResult(
            List.copyOf(expandedRecipes),
            Map.copyOf(variantToSourcePattern),
            Set.copyOf(createdSubsets)
        );
    }

    private static void expandFuzzyPattern(
        final Pattern pattern,
        final int basePriority,
        final Set<ResourceKey> craftableResources,
        final Map<ResourceKey, ResourceKey> globalSubsets,
        final List<LpPatternRecipe> expandedRecipes,
        final Map<UUID, UUID> variantToSourcePattern,
        final Set<LpResourceSubset> createdSubsets
    ) {
        final List<IngredientPartition> partitions = partitionIngredients(
            pattern.layout().ingredients(), craftableResources, globalSubsets
        );

        // If any ingredient has no viable options at all, the entire recipe is unusable
        final boolean anyIngredientPruned = partitions.stream()
            .anyMatch(p -> p.subsets().isEmpty());
        if (anyIngredientPruned) {
            return;
        }

        // Collect created subsets for starting resource augmentation
        for (final IngredientPartition partition : partitions) {
            for (final ResourceKey subset : partition.subsets()) {
                if (subset instanceof LpResourceSubset sub) {
                    createdSubsets.add(sub);
                }
            }
        }

        final List<IngredientGroup> groups = groupIngredientsByPartition(partitions);
        if (groups.isEmpty()) {
            return;
        }

        final List<Map<ResourceKey, Long>> variantInputs = generateVariantInputs(groups);
        final LpResourceSet output = buildOutputSet(pattern);

        for (final Map<ResourceKey, Long> variantInput : variantInputs) {
            if (variantInput.isEmpty()) {
                continue;
            }

            final UUID variantId = UUID.randomUUID();

            final List<Ingredient> syntheticIngredients = new ArrayList<>();
            for (final Map.Entry<ResourceKey, Long> entry : variantInput.entrySet()) {
                syntheticIngredients.add(new Ingredient(entry.getValue(), List.of(entry.getKey())));
            }

            final PatternLayout syntheticLayout = new PatternLayout(
                syntheticIngredients,
                pattern.layout().outputs(),
                pattern.layout().byproducts(),
                pattern.layout().type()
            );
            final Pattern syntheticPattern = new Pattern(variantId, syntheticLayout);
            final LpResourceSet input = new LpResourceSet(variantInput);
            expandedRecipes.add(new LpPatternRecipe(syntheticPattern, input, output, basePriority, null));
            variantToSourcePattern.put(variantId, pattern.id());
        }
    }

    /**
     * Computes non-overlapping subsets for non-craftable, in-storage resources across all patterns.
     * Resources that participate in exactly the same set of fuzzy ingredient slots are grouped
     * into the same {@link LpResourceSubset} to prevent the LP solver from double-counting.
     *
     * @param patterns           sorted list of patterns
     * @param craftableResources resources producible by some recipe
     * @param startingResources  resources currently in storage
     * @return mapping from each non-craftable resource to its subset representative
     */
    static Map<ResourceKey, ResourceKey> computeGlobalSubsets(
        final List<Pattern> patterns,
        final Set<ResourceKey> craftableResources,
        final LpResourceSet startingResources
    ) {
        final Map<ResourceKey, Set<String>> resourceParticipation = new LinkedHashMap<>();

        for (int pi = 0; pi < patterns.size(); pi++) {
            collectPatternParticipation(
                resourceParticipation,
                patterns.get(pi).layout().ingredients(),
                pi,
                craftableResources,
                startingResources
            );
        }

        final Map<Set<String>, List<ResourceKey>> groups = new LinkedHashMap<>();
        for (final var entry : resourceParticipation.entrySet()) {
            groups.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        final Map<ResourceKey, ResourceKey> resourceToSubset = new LinkedHashMap<>();
        for (final var group : groups.values()) {
            if (group.size() == 1) {
                resourceToSubset.put(group.getFirst(), group.getFirst());
            } else {
                final LpResourceSubset subset = new LpResourceSubset(group);
                for (final ResourceKey member : group) {
                    resourceToSubset.put(member, subset);
                }
            }
        }

        return resourceToSubset;
    }

    private static void collectPatternParticipation(
        final Map<ResourceKey, Set<String>> resourceParticipation,
        final List<Ingredient> ingredients,
        final int patternIndex,
        final Set<ResourceKey> craftableResources,
        final LpResourceSet startingResources
    ) {
        for (int ingredientIndex = 0; ingredientIndex < ingredients.size(); ingredientIndex++) {
            collectIngredientParticipation(
                resourceParticipation,
                ingredients.get(ingredientIndex),
                patternIndex,
                ingredientIndex,
                craftableResources,
                startingResources
            );
        }
    }

    private static void collectIngredientParticipation(
        final Map<ResourceKey, Set<String>> resourceParticipation,
        final Ingredient ingredient,
        final int patternIndex,
        final int ingredientIndex,
        final Set<ResourceKey> craftableResources,
        final LpResourceSet startingResources
    ) {
        if (ingredient.inputs().size() <= 1) {
            return;
        }

        final String slotKey = patternIndex + ":" + ingredientIndex;
        ingredient.inputs().stream()
            .filter(option -> isStorageOnlyOption(option, craftableResources, startingResources))
            .forEach(option -> resourceParticipation
                .computeIfAbsent(option, ignored -> new LinkedHashSet<>())
                .add(slotKey));
    }

    private static boolean isStorageOnlyOption(final ResourceKey option,
                                               final Set<ResourceKey> craftableResources,
                                               final LpResourceSet startingResources) {
        return !craftableResources.contains(option) && startingResources.getAmount(option) > 0;
    }

    /**
     * Partitions each ingredient's options into non-overlapping subsets using the
     * precomputed global subset mapping:
     * <ul>
     *   <li>Individually craftable resources become singletons</li>
     *   <li>Non-craftable, in-storage resources use their global subset representative</li>
     *   <li>Resources that are neither craftable nor in storage are pruned</li>
     * </ul>
     */
    static List<IngredientPartition> partitionIngredients(
        final List<Ingredient> ingredients,
        final Set<ResourceKey> craftableResources,
        final Map<ResourceKey, ResourceKey> globalSubsets
    ) {
        final List<IngredientPartition> result = new ArrayList<>();
        for (final Ingredient ingredient : ingredients) {
            result.add(partitionSingleIngredient(ingredient, craftableResources, globalSubsets));
        }
        return result;
    }

    static IngredientPartition partitionSingleIngredient(
        final Ingredient ingredient,
        final Set<ResourceKey> craftableResources,
        final Map<ResourceKey, ResourceKey> globalSubsets
    ) {
        if (ingredient.inputs().size() == 1) {
            return new IngredientPartition(
                ingredient.amount(),
                List.of(ingredient.inputs().getFirst())
            );
        }

        final List<ResourceKey> subsets = new ArrayList<>();
        final Set<ResourceKey> seen = new LinkedHashSet<>();

        for (final ResourceKey option : ingredient.inputs()) {
            if (craftableResources.contains(option)) {
                if (seen.add(option)) {
                    subsets.add(option);
                }
            } else {
                final ResourceKey subsetRep = globalSubsets.get(option);
                if (subsetRep != null && seen.add(subsetRep)) {
                    subsets.add(subsetRep);
                }
            }
        }

        // If no viable options remain after filtering, keep the first option as a fallback
        if (subsets.isEmpty() && !ingredient.inputs().isEmpty()) {
            subsets.add(ingredient.inputs().getFirst());
        }

        return new IngredientPartition(ingredient.amount(), subsets);
    }

    /**
     * Groups ingredients with identical subset lists, summing their amounts.
     * This reduces the combinatorial explosion by merging identical fuzzy slots.
     */
    static List<IngredientGroup> groupIngredientsByPartition(final List<IngredientPartition> partitions) {
        final Map<List<ResourceKey>, Long> grouped = new LinkedHashMap<>();
        for (final IngredientPartition partition : partitions) {
            if (!partition.subsets().isEmpty()) {
                grouped.merge(partition.subsets(), partition.amount(), Long::sum);
            }
        }
        final List<IngredientGroup> groups = new ArrayList<>();
        for (final var entry : grouped.entrySet()) {
            groups.add(new IngredientGroup(entry.getKey(), entry.getValue()));
        }
        return groups;
    }

    /**
     * Generates all variant input combinations across ingredient groups.
     * Each variant is a map from ResourceKey (or LpResourceSubset) to total amount.
     * The cross-product of stars-and-bars allocations per group produces the variants.
     */
    static List<Map<ResourceKey, Long>> generateVariantInputs(final List<IngredientGroup> groups) {
        List<Map<ResourceKey, Long>> variants = new ArrayList<>();
        variants.add(new LinkedHashMap<>());

        for (final IngredientGroup group : groups) {
            final List<Map<ResourceKey, Long>> groupAllocations = generateAllocations(
                group.subsets(), group.totalAmount()
            );
            final List<Map<ResourceKey, Long>> newVariants = new ArrayList<>();
            for (final Map<ResourceKey, Long> existing : variants) {
                if (appendCombinedVariants(existing, groupAllocations, newVariants)) {
                    return newVariants;
                }
            }
            variants = newVariants;
        }

        return variants;
    }

    private static boolean appendCombinedVariants(
        final Map<ResourceKey, Long> existing,
        final List<Map<ResourceKey, Long>> groupAllocations,
        final List<Map<ResourceKey, Long>> newVariants
    ) {
        for (final Map<ResourceKey, Long> allocation : groupAllocations) {
            newVariants.add(combineVariantInputs(existing, allocation));
            if (newVariants.size() > MAX_VARIANTS_PER_RECIPE) {
                return true;
            }
        }
        return false;
    }

    private static Map<ResourceKey, Long> combineVariantInputs(final Map<ResourceKey, Long> existing,
                                                               final Map<ResourceKey, Long> allocation) {
        final Map<ResourceKey, Long> combined = new LinkedHashMap<>(existing);
        allocation.forEach((resource, amount) -> combined.merge(resource, amount, Long::sum));
        return combined;
    }

    /**
     * Generates all ways to distribute totalAmount among the given subsets.
     * Stars-and-bars: non-negative integer solutions to s1 + s2 + ... + sk = totalAmount.
     */
    static List<Map<ResourceKey, Long>> generateAllocations(
        final List<ResourceKey> subsets,
        final long totalAmount
    ) {
        if (subsets.isEmpty()) {
            return List.of();
        }
        if (subsets.size() == 1) {
            return List.of(Map.of(subsets.getFirst(), totalAmount));
        }
        final List<Map<ResourceKey, Long>> allocations = new ArrayList<>();
        generateAllocationsRecursive(subsets, 0, totalAmount, new LinkedHashMap<>(), allocations);
        return allocations;
    }

    private static void generateAllocationsRecursive(
        final List<ResourceKey> subsets,
        final int index,
        final long remaining,
        final Map<ResourceKey, Long> current,
        final List<Map<ResourceKey, Long>> results
    ) {
        if (results.size() >= MAX_VARIANTS_PER_RECIPE) {
            return;
        }
        if (index == subsets.size() - 1) {
            // Last subset gets all remaining
            if (remaining > 0) {
                current.put(subsets.get(index), remaining);
            }
            results.add(new LinkedHashMap<>(current));
            current.remove(subsets.get(index));
            return;
        }
        for (long amount = 0; amount <= remaining; amount++) {
            if (amount > 0) {
                current.put(subsets.get(index), amount);
            }
            generateAllocationsRecursive(subsets, index + 1, remaining - amount, current, results);
            current.remove(subsets.get(index));
        }
    }

    /**
     * Collects all resources producible by any recipe output in the given patterns.
     */
    public static Set<ResourceKey> collectCraftableResources(final Collection<Pattern> patterns) {
        final Set<ResourceKey> craftable = new LinkedHashSet<>();
        for (final Pattern pattern : patterns) {
            for (final ResourceAmount output : pattern.layout().outputs()) {
                craftable.add(output.resource());
            }
        }
        return craftable;
    }

    /**
     * Augments starting resources with available amounts for each {@link LpResourceSubset}.
     * For each subset, the available amount is the sum of storage amounts of its members.
     *
     * @return a new LpResourceSet with subset entries added
     */
    public static LpResourceSet augmentStartingResources(
        final LpResourceSet startingResources,
        final Set<LpResourceSubset> subsets
    ) {
        final LpResourceSet augmented = startingResources.copy();
        for (final LpResourceSubset subset : subsets) {
            augmented.setAmount(subset, subset.availableAmount(startingResources));
        }
        return augmented;
    }

    /**
     * Decodes a fuzzy resource map by replacing {@link LpResourceSubset} entries
     * with concrete {@link ResourceKey} allocations from available storage.
     */
    public static Map<ResourceKey, Long> decodeFuzzyResources(
        final LpResourceSet planResult,
        final LpResourceSet availableResources
    ) {
        final Map<ResourceKey, Long> decoded = new LinkedHashMap<>();
        final LpResourceSet remainingAvailable = availableResources.copy();

        for (final var entry : planResult) {
            final ResourceKey resource = entry.getKey();
            final long amount = entry.getValue();

            if (amount <= 0) {
                continue;
            }

            if (resource instanceof LpResourceSubset subset) {
                final Map<ResourceKey, Long> allocation = subset.allocateConcrete(amount, remainingAvailable);
                for (final var alloc : allocation.entrySet()) {
                    decoded.merge(alloc.getKey(), alloc.getValue(), Long::sum);
                    remainingAvailable.subtractAmount(alloc.getKey(), alloc.getValue());
                }
            } else {
                decoded.merge(resource, amount, Long::sum);
            }
        }

        return decoded;
    }

    /**
     * Decodes plan steps by mapping variant recipe IDs back to source patterns
     * and resolving {@link LpResourceSubset} inputs to concrete resources.
      *
      * <p>When a step has subset inputs that can't be evenly divided across iterations
     * (because members have unequal availability), the step is split into sub-steps,
     * each with a uniform per-iteration allocation.
     *
     * @param steps                  the plan steps (may reference fuzzy variant recipes)
     * @param variantToSourcePattern mapping from variant pattern UUID to original pattern UUID
     * @param patternsById           mapping from pattern UUID to Pattern
     * @param availableResources     resources available for concrete allocation
     * @return decoded plan steps with original patterns and concrete inputs
     */
    public static List<LpExecutionPlanStep> decodePlanSteps(
        final List<LpExecutionPlanStep> steps,
        final Map<UUID, UUID> variantToSourcePattern,
        final Map<UUID, Pattern> patternsById,
        final LpResourceSet availableResources
    ) {
        final List<LpExecutionPlanStep> decoded = new ArrayList<>();
        final LpResourceSet remainingAvailable = availableResources.copy();

        for (final LpExecutionPlanStep step : steps) {
            final LpPatternRecipe recipe = step.recipe();
            final UUID sourcePatternId = variantToSourcePattern.get(recipe.uniqueId());

            if (sourcePatternId == null) {
                decoded.add(step);
                continue;
            }

            final Pattern sourcePattern = patternsById.get(sourcePatternId);
            if (sourcePattern == null) {
                decoded.add(step);
                continue;
            }

            // Check if any input is a subset
            boolean hasSubset = false;
            for (final var entry : recipe.input()) {
                if (entry.getKey() instanceof LpResourceSubset) {
                    hasSubset = true;
                    break;
                }
            }

            if (!hasSubset) {
                // No subsets: just remap to source pattern, pass input through
                final LpPatternRecipe decodedRecipe = new LpPatternRecipe(
                    sourcePattern, recipe.input().copy(), recipe.output(),
                    recipe.basePriority(), recipe.effectivePriority()
                );
                decoded.add(new LpExecutionPlanStep(decodedRecipe, step.iterations()));
                continue;
            }

            // Split into sub-steps, each with a uniform per-iteration concrete allocation
            decodeSubsetStep(step, recipe, sourcePattern, remainingAvailable, decoded);
        }

        return decoded;
    }

    private static void decodeSubsetStep(
        final LpExecutionPlanStep step,
        final LpPatternRecipe recipe,
        final Pattern sourcePattern,
        final LpResourceSet remainingAvailable,
        final List<LpExecutionPlanStep> decoded
    ) {
        long iterationsLeft = step.iterations();

        while (iterationsLeft > 0) {
            // Allocate exactly one iteration's worth of each input
            final LpResourceSet perIterationInput = new LpResourceSet();
            for (final var entry : recipe.input()) {
                final ResourceKey resource = entry.getKey();
                final long perIteration = entry.getValue();

                if (resource instanceof LpResourceSubset subset) {
                    final Map<ResourceKey, Long> allocation =
                        subset.allocateConcrete(perIteration, remainingAvailable);
                    for (final var alloc : allocation.entrySet()) {
                        perIterationInput.addAmount(alloc.getKey(), alloc.getValue());
                    }
                } else {
                    perIterationInput.addAmount(resource, perIteration);
                }
            }

            // Determine how many consecutive iterations can use this same allocation
            long batchSize = iterationsLeft;
            for (final var entry : perIterationInput) {
                final long available = remainingAvailable.getAmount(entry.getKey());
                final long maxFromThis = entry.getValue() > 0 ? available / entry.getValue() : Long.MAX_VALUE;
                batchSize = Math.min(batchSize, maxFromThis);
            }
            if (batchSize <= 0) {
                break;
            }

            // Subtract total usage from available
            for (final var entry : perIterationInput) {
                remainingAvailable.subtractAmount(entry.getKey(), entry.getValue() * batchSize);
            }

            final LpPatternRecipe decodedRecipe = new LpPatternRecipe(
                sourcePattern, perIterationInput, recipe.output(),
                recipe.basePriority(), recipe.effectivePriority()
            );
            decoded.add(new LpExecutionPlanStep(decodedRecipe, batchSize));
            iterationsLeft -= batchSize;
        }
    }

    static LpResourceSet buildOutputSet(final Pattern pattern) {
        final LpResourceSet output = new LpResourceSet();
        for (final ResourceAmount ra : pattern.layout().outputs()) {
            output.addAmount(ra.resource(), ra.amount());
        }
        for (final ResourceAmount ra : pattern.layout().byproducts()) {
            output.addAmount(ra.resource(), ra.amount());
        }
        return output;
    }

    record IngredientPartition(long amount, List<ResourceKey> subsets) {
        IngredientPartition {
            subsets = List.copyOf(subsets);
        }
    }

    record IngredientGroup(List<ResourceKey> subsets, long totalAmount) {
        IngredientGroup {
            subsets = List.copyOf(subsets);
        }
    }

    /**
     * Result of expanding fuzzy patterns into concrete LP recipe variants.
     *
     * @param expandedRecipes        all recipes (non-fuzzy pass-through + expanded fuzzy variants)
     * @param variantToSourcePattern mapping from variant pattern UUID to original fuzzy pattern UUID
     * @param createdSubsets         all {@link LpResourceSubset}s created during expansion
     */
    public record FuzzyExpansionResult(
        List<LpPatternRecipe> expandedRecipes,
        Map<UUID, UUID> variantToSourcePattern,
        Set<LpResourceSubset> createdSubsets
    ) {
        public FuzzyExpansionResult {
            expandedRecipes = List.copyOf(expandedRecipes);
            variantToSourcePattern = Map.copyOf(variantToSourcePattern);
            createdSubsets = Set.copyOf(createdSubsets);
        }
    }
}
