package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Turns MRKs back into single ResourceKeys
// This is used to turn the sanitized recipes and resource pools that the crafting solver works with
// into specific Patterns and ResourceKeys.
// Acts as the translation gateway from LP to the rest of the code
public final class RecipeDesanitizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeDesanitizer.class);

    private RecipeDesanitizer() {
    }

    public static Map<ResourceKey, Long> copySanitizedStorage(final Map<ResourceKey, Long> sanitizedStorage) {
        Objects.requireNonNull(sanitizedStorage, "sanitizedStorage cannot be null");
        return new LinkedHashMap<>(sanitizedStorage);
    }

    public static ResourcePool decodeSanitizedResourcesToSanitized(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> remainingSanitizedStorage
    ) {
        return decodeSanitizedResourcesToSanitized(sanitizedResources, remainingSanitizedStorage, CancellationToken.NONE);
    }

    public static ResourcePool decodeSanitizedResourcesToSanitized(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> remainingSanitizedStorage,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(remainingSanitizedStorage, "remainingSanitizedStorage cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final ResourcePool decoded = ResourcePool.empty();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            throwIfCancelled(cancellationToken);
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            allocateIntoPool(decoded, entry.getKey(), amount, remainingSanitizedStorage);
        }

        // LOGGER.debug(
        //     "[LP] RecipeDesanitizer.decodeSanitizedResourcesToSanitized: "
        //         + "sanitized={} decoded={} remainingSanitizedStorage={}",
        //     sanitizedResources,
        //     decoded,
        //     remainingSanitizedStorage
        // );
        return decoded;
    }

    public static ResourcePool convertToSanitizedOutputs(final ResourcePool sanitizedResources) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");

        final ResourcePool decoded = ResourcePool.empty();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            decoded.addAmount(new MultiResourceKey(List.of(firstMemberOf(entry.getKey()))), amount);
        }
        return decoded;
    }

    public static ResourcePool decodeSanitizedResources(
        final ResourcePool sanitizedResources,
        final ResourcePool availableResources
    ) {
        return decodeSanitizedResources(sanitizedResources, availableResources, CancellationToken.NONE);
    }

    public static ResourcePool decodeSanitizedResources(
        final ResourcePool sanitizedResources,
        final ResourcePool availableResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(availableResources, "availableResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        return sanitizedResources.copy();
    }

    // Decodes a sanitized MRK-based ResourcePool into a pool whose keys are
    // single-member MultiResourceKeys. For each MRK entry the requested amount is
    // satisfied greedily from sanitizedStartingResources, consuming each member in
    // member-list order. Any remainder that cannot be resolved against sanitized storage falls
    // back to the first member of the MRK (e.g. for missing resources).

    public static ResourcePool decodeSanitizedResourcePool(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> sanitizedStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> remaining = new LinkedHashMap<>(sanitizedStartingResources);
        final ResourcePool decoded = ResourcePool.empty();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            throwIfCancelled(cancellationToken);
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            allocateIntoPool(decoded, entry.getKey(), amount, remaining);
        }
        return decoded;
    }

    private static void allocateIntoPool(
        final ResourcePool decoded,
        final MultiResourceKey multiResourceKey,
        final long totalNeeded,
        final Map<ResourceKey, Long> remainingSanitizedStorage
    ) {
        long remaining = totalNeeded;
        for (final ResourceKey member : multiResourceKey.members()) {
            if (remaining <= 0L) {
                break;
            }
            final long available = Math.max(0L, remainingSanitizedStorage.getOrDefault(member, 0L));
            final long used = Math.min(remaining, available);
            if (used > 0L) {
                decoded.addAmount(new MultiResourceKey(List.of(member)), used);
                remainingSanitizedStorage.put(member, available - used);
                remaining -= used;
            }
        }
        if (remaining > 0L) {
            decoded.addAmount(new MultiResourceKey(List.of(multiResourceKey.members().getFirst())), remaining);
        }
    }

    public static List<RecipeApplicationStep> decodePlanSteps(
        final List<RecipeApplicationStep> steps,
        final Map<ResourceKey, Long> sanitizedStartingResources
    ) {
        return decodePlanSteps(steps, sanitizedStartingResources, CancellationToken.NONE);
    }

    public static List<RecipeApplicationStep> decodePlanSteps(
        final List<RecipeApplicationStep> steps,
        final Map<ResourceKey, Long> sanitizedStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> remainingSanitizedStorage = new LinkedHashMap<>(sanitizedStartingResources);
        final List<RecipeApplicationStep> decodedSteps = new java.util.ArrayList<>();
        for (final RecipeApplicationStep step : steps) {
            throwIfCancelled(cancellationToken);
            final SanitizedRecipe recipe = step.recipe();
            final ResourcePool sanitizedOutput = convertToSanitizedOutputs(recipe.output());

            ResourcePool currentInput = null;
            long currentBatchTimesApplied = 0L;
            for (long index = 0L; index < step.timesApplied(); index++) {
                throwIfCancelled(cancellationToken);
                final ResourcePool sanitizedInput = decodeSanitizedResourcesToSanitized(
                    recipe.input(),
                    remainingSanitizedStorage,
                    cancellationToken
                );
                if (currentInput != null && currentInput.asMap().equals(sanitizedInput.asMap())) {
                    currentBatchTimesApplied++;
                    continue;
                }
                if (currentInput != null) {
                    decodedSteps.add(new RecipeApplicationStep(
                        new SanitizedRecipe(
                            recipe.recipeId(),
                            recipe.sourcePatternId(),
                            currentInput,
                            sanitizedOutput,
                            recipe.priority(),
                            recipe.insertionOrder()
                        ),
                        currentBatchTimesApplied
                    ));
                }
                currentInput = sanitizedInput;
                currentBatchTimesApplied = 1L;
            }

            if (currentInput != null) {
                decodedSteps.add(new RecipeApplicationStep(
                    new SanitizedRecipe(
                        recipe.recipeId(),
                        recipe.sourcePatternId(),
                        currentInput,
                        sanitizedOutput,
                        recipe.priority(),
                        recipe.insertionOrder()
                    ),
                    currentBatchTimesApplied
                ));
            }
        }
        return List.copyOf(decodedSteps);
    }

    // Desanitizes an entire RecipeApplicationSet into concrete MRK(single member) resources.
    public static RecipeApplicationSet decodeRecipeApplicationSet(
        final RecipeApplicationSet applicationSet,
        final Map<ResourceKey, Long> sanitizedStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(applicationSet, "applicationSet cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final ResourcePool decodedUsed = decodeSanitizedResourcePool(
            applicationSet.usedResources(),
            sanitizedStartingResources,
            cancellationToken
        );
        final ResourcePool decodedMissing = decodeSanitizedResources(
            applicationSet.missingResources(),
            applicationSet.usedResources(),
            cancellationToken
        );
        final ResourcePool decodedFinal = decodeSanitizedResources(
            applicationSet.finalInventoryValues(),
            applicationSet.usedResources(),
            cancellationToken
        );

        return new RecipeApplicationSet(
            applicationSet.recipes(),
            applicationSet.recipeValues(),
            decodedUsed,
            decodedFinal,
            decodedMissing,
            applicationSet.relevantResourceKeys()
        );
    }

    // Desanitizes an entire RecipeApplicationPath into concrete steps/set/peak usage.
    public static RecipeApplicationPath decodeRecipeApplicationPath(
        final RecipeApplicationPath path,
        final Map<ResourceKey, Long> sanitizedStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final DesanitizedRecipeApplicationPath desanitizedPath = decodeRecipeApplicationPathToDesanitized(
            path,
            sanitizedStartingResources,
            cancellationToken
        );

        return toRecipeApplicationPath(desanitizedPath);
    }

    public static RecipeApplicationPath toRecipeApplicationPath(
        final DesanitizedRecipeApplicationPath desanitizedPath
    ) {
        Objects.requireNonNull(desanitizedPath, "desanitizedPath cannot be null");

        final List<RecipeApplicationStep> decodedSteps = desanitizedPath.steps().stream()
            .map(step -> new RecipeApplicationStep(
                new SanitizedRecipe(
                    step.recipe().recipeId(),
                    step.recipe().sourcePatternId(),
                    toResourcePool(step.recipe().input()),
                    toResourcePool(step.recipe().output()),
                    step.recipe().priority(),
                    step.recipe().insertionOrder()
                ),
                step.timesApplied()
            ))
            .toList();

        final List<SanitizedRecipe> recipes = desanitizedPath.applicationSet().recipes().stream()
            .map(recipe -> new SanitizedRecipe(
                recipe.recipeId(),
                recipe.sourcePatternId(),
                toResourcePool(recipe.input()),
                toResourcePool(recipe.output()),
                recipe.priority(),
                recipe.insertionOrder()
            ))
            .toList();

        final Map<java.util.UUID, SanitizedRecipe> recipesById = recipes.stream().collect(java.util.stream.Collectors.toMap(
            SanitizedRecipe::recipeId,
            recipe -> recipe,
            (a, b) -> a,
            LinkedHashMap::new
        ));

        final Map<SanitizedRecipe, Long> recipeValues = new LinkedHashMap<>();
        desanitizedPath.applicationSet().recipeValues().forEach((recipe, amount) -> {
            final SanitizedRecipe sanitizedRecipe = recipesById.get(recipe.recipeId());
            if (sanitizedRecipe != null) {
                recipeValues.put(sanitizedRecipe, amount);
            }
        });

        final RecipeApplicationSet decodedSet = new RecipeApplicationSet(
            recipes,
            recipeValues,
            toResourcePool(desanitizedPath.applicationSet().usedResources()),
            toResourcePool(desanitizedPath.applicationSet().finalInventoryValues()),
            toResourcePool(desanitizedPath.applicationSet().missingResources()),
            desanitizedPath.applicationSet().relevantResourceKeys().stream()
                .map(key -> new MultiResourceKey(List.of(key)))
                .toList()
        );

        final ResourcePool decodedPeakUsage = toResourcePool(desanitizedPath.peakResourceUsage());

        return new RecipeApplicationPath(decodedSet, decodedSteps, decodedPeakUsage, desanitizedPath.hasCycles());
    }

    public static DesanitizedRecipeApplicationPath decodeRecipeApplicationPathToDesanitized(
        final RecipeApplicationPath path,
        final Map<ResourceKey, Long> sanitizedStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final List<DesanitizedRecipeApplicationStep> decodedSteps = decodePlanStepsToDesanitized(
            path.steps(),
            sanitizedStartingResources,
            cancellationToken
        );
        final DesanitizedRecipeApplicationSet decodedSet = convertToDesanitized(
            path.applicationSet(),
            sanitizedStartingResources,
            cancellationToken
        );
        final Map<ResourceKey, Long> decodedPeakUsage = decodeSanitizedResourcePoolToDesanitized(
            path.peakResourceUsage(),
            sanitizedStartingResources,
            cancellationToken
        );

        return new DesanitizedRecipeApplicationPath(decodedSet, decodedSteps, decodedPeakUsage, path.hasCycles());
    }

    private static ResourcePool toResourcePool(final Map<ResourceKey, Long> resources) {
        final ResourcePool result = ResourcePool.empty();
        for (final var entry : resources.entrySet()) {
            if (entry.getValue() != 0L) {
                result.addAmount(new MultiResourceKey(List.of(entry.getKey())), entry.getValue());
            }
        }
        return result;
    }

    // Converts an LP MultiResourceKey to a single RS ResourceKey by extracting the first member.
    // Used throughout LP code to convert from MRK-based resource references to single ResourceKey references.
    public static ResourceKey toSanitizedResourceKey(final MultiResourceKey resourceKey) {
        Objects.requireNonNull(resourceKey, "resourceKey cannot be null");
        if (!resourceKey.members().isEmpty()) {
            return resourceKey.members().getFirst();
        }
        throw new IllegalStateException("MultiResourceKey has no members: " + resourceKey);
    }

    private static ResourceKey firstMemberOf(final MultiResourceKey multiResourceKey) {
        if (!multiResourceKey.members().isEmpty()) {
            return multiResourceKey.members().getFirst();
        }
        throw new IllegalStateException("MultiResourceKey has no members: " + multiResourceKey);
    }

    private static void throwIfCancelled(final CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("LP recipe desanitizer cancelled");
        }
    }

    // ===== New desanitized variants that return Map<ResourceKey, Long> =====

    // Decodes a sanitized MRK-based ResourcePool into a map of desanitized resources.
    // For each MRK entry, the requested amount is satisfied greedily from sanitizedStartingResources,
    // consuming each member in member-list order. Any remainder that cannot be resolved against
    // sanitized storage falls back to the first member of the MRK.
    public static Map<ResourceKey, Long> decodeSanitizedResourcePoolToDesanitized(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> sanitizedStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> remaining = new LinkedHashMap<>(sanitizedStartingResources);
        final Map<ResourceKey, Long> decoded = new LinkedHashMap<>();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            throwIfCancelled(cancellationToken);
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            allocateIntoDesanitizedMap(decoded, entry.getKey(), amount, remaining);
        }
        return decoded;
    }

    // Decodes sanitized resources into desanitized form, ignoring available resources.
    public static Map<ResourceKey, Long> decodeSanitizedResourcesToDesanitized(
        final ResourcePool sanitizedResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> decoded = new LinkedHashMap<>();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            throwIfCancelled(cancellationToken);
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            final ResourceKey firstMember = firstMemberOf(entry.getKey());
            decoded.merge(firstMember, amount, Long::sum);
        }
        return decoded;
    }

    public static Map<ResourceKey, Long> decodeSanitizedResourcesToDesanitizedRaw(
        final ResourcePool sanitizedResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> decoded = new LinkedHashMap<>();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            throwIfCancelled(cancellationToken);
            final long amount = entry.getValue();
            if (amount == 0L) {
                continue;
            }
            final ResourceKey firstMember = firstMemberOf(entry.getKey());
            decoded.merge(firstMember, amount, Long::sum);
        }
        return decoded;
    }

    private static void allocateIntoDesanitizedMap(
        final Map<ResourceKey, Long> decoded,
        final MultiResourceKey multiResourceKey,
        final long totalNeeded,
        final Map<ResourceKey, Long> remainingSanitizedStorage
    ) {
        long remaining = totalNeeded;
        for (final ResourceKey member : multiResourceKey.members()) {
            if (remaining <= 0L) {
                break;
            }
            final long available = Math.max(0L, remainingSanitizedStorage.getOrDefault(member, 0L));
            final long used = Math.min(remaining, available);
            if (used > 0L) {
                decoded.merge(member, used, Long::sum);
                remainingSanitizedStorage.put(member, available - used);
                remaining -= used;
            }
        }
        if (remaining > 0L) {
            decoded.merge(multiResourceKey.members().getFirst(), remaining, Long::sum);
        }
    }

    // Decodes a list of sanitized recipe application steps into desanitized steps using only ResourceKey resources.
    public static List<DesanitizedRecipeApplicationStep> decodePlanStepsToDesanitized(
        final List<RecipeApplicationStep> steps,
        final Map<ResourceKey, Long> sanitizedStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> remainingSanitizedStorage = new LinkedHashMap<>(sanitizedStartingResources);
        final List<DesanitizedRecipeApplicationStep> decodedSteps = new java.util.ArrayList<>();
        for (final RecipeApplicationStep step : steps) {
            throwIfCancelled(cancellationToken);
            final SanitizedRecipe recipe = step.recipe();
            final Map<ResourceKey, Long> sanitizedOutput = convertToDesanitizedOutputs(recipe.output());

            Map<ResourceKey, Long> currentInput = null;
            long currentBatchTimesApplied = 0L;
            for (long index = 0L; index < step.timesApplied(); index++) {
                throwIfCancelled(cancellationToken);
                final Map<ResourceKey, Long> sanitizedInput = decodeSanitizedResourcePoolToDesanitizedWithRemaining(
                    recipe.input(),
                    remainingSanitizedStorage,
                    cancellationToken
                );
                if (currentInput != null && currentInput.equals(sanitizedInput)) {
                    currentBatchTimesApplied++;
                    continue;
                }
                if (currentInput != null) {
                    decodedSteps.add(new DesanitizedRecipeApplicationStep(
                        new DesanitizedRecipe(
                            recipe.recipeId(),
                            recipe.sourcePatternId(),
                            currentInput,
                            sanitizedOutput,
                            recipe.priority(),
                            recipe.insertionOrder()
                        ),
                        currentBatchTimesApplied
                    ));
                }
                currentInput = sanitizedInput;
                currentBatchTimesApplied = 1L;
            }

            if (currentInput != null) {
                decodedSteps.add(new DesanitizedRecipeApplicationStep(
                    new DesanitizedRecipe(
                        recipe.recipeId(),
                        recipe.sourcePatternId(),
                        currentInput,
                        sanitizedOutput,
                        recipe.priority(),
                        recipe.insertionOrder()
                    ),
                    currentBatchTimesApplied
                ));
            }
        }
        return List.copyOf(decodedSteps);
    }

    private static Map<ResourceKey, Long> decodeSanitizedResourcePoolToDesanitizedWithRemaining(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> remainingSanitizedStorage,
        final CancellationToken cancellationToken
    ) {
        final Map<ResourceKey, Long> decoded = new LinkedHashMap<>();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            throwIfCancelled(cancellationToken);
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            allocateIntoDesanitizedMap(decoded, entry.getKey(), amount, remainingSanitizedStorage);
        }
        return decoded;
    }

    // Converts a sanitized output ResourcePool to a desanitized map, taking only the first member of each MRK.
    private static Map<ResourceKey, Long> convertToDesanitizedOutputs(final ResourcePool sanitizedResources) {
        final Map<ResourceKey, Long> decoded = new LinkedHashMap<>();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            decoded.merge(firstMemberOf(entry.getKey()), amount, Long::sum);
        }
        return decoded;
    }

    // Converts a RecipeApplicationSet (which uses MRKs and ResourcePools) to a
    // DesanitizedRecipeApplicationSet (which uses only ResourceKey and Map<ResourceKey, Long>).
    public static DesanitizedRecipeApplicationSet convertToDesanitized(
        final RecipeApplicationSet applicationSet,
        final Map<ResourceKey, Long> sanitizedStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(applicationSet, "applicationSet cannot be null");
        Objects.requireNonNull(sanitizedStartingResources, "sanitizedStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        // Convert recipes from SanitizedRecipe to DesanitizedRecipe
        final java.util.Map<SanitizedRecipe, DesanitizedRecipe> recipeMapping = new LinkedHashMap<>();
        final java.util.List<DesanitizedRecipe> desanitizedRecipes = new java.util.ArrayList<>();
        
        for (final SanitizedRecipe recipe : applicationSet.recipes()) {
            throwIfCancelled(cancellationToken);
            final Map<ResourceKey, Long> input = convertToDesanitizedOutputs(recipe.input());
            final Map<ResourceKey, Long> output = convertToDesanitizedOutputs(recipe.output());
            final DesanitizedRecipe desanitized = new DesanitizedRecipe(
                recipe.recipeId(),
                recipe.sourcePatternId(),
                input,
                output,
                recipe.priority(),
                recipe.insertionOrder()
            );
            recipeMapping.put(recipe, desanitized);
            desanitizedRecipes.add(desanitized);
        }

        // Convert recipe values map
        final Map<DesanitizedRecipe, Long> desanitizedRecipeValues = new LinkedHashMap<>();
        for (final Map.Entry<SanitizedRecipe, Long> entry : applicationSet.recipeValues().entrySet()) {
            throwIfCancelled(cancellationToken);
            final DesanitizedRecipe desanitized = recipeMapping.get(entry.getKey());
            if (desanitized != null) {
                desanitizedRecipeValues.put(desanitized, entry.getValue());
            }
        }

        // Convert resource maps
        final Map<ResourceKey, Long> usedResources = decodeSanitizedResourcePoolToDesanitized(
            applicationSet.usedResources(),
            sanitizedStartingResources,
            cancellationToken
        );
        final Map<ResourceKey, Long> finalInventory = decodeSanitizedResourcesToDesanitizedRaw(
            applicationSet.finalInventoryValues(),
            cancellationToken
        );
        final Map<ResourceKey, Long> missingResources = decodeSanitizedResourcesToDesanitized(
            applicationSet.missingResources(),
            cancellationToken
        );

        // Convert relevant resource keys - extract first member of each MRK
        final java.util.List<ResourceKey> desanitizedRelevantKeys = new java.util.ArrayList<>();
        for (final MultiResourceKey mrk : applicationSet.relevantResourceKeys()) {
            throwIfCancelled(cancellationToken);
            if (!mrk.members().isEmpty()) {
                desanitizedRelevantKeys.add(mrk.members().getFirst());
            }
        }

        return new DesanitizedRecipeApplicationSet(
            desanitizedRecipes,
            desanitizedRecipeValues,
            usedResources,
            finalInventory,
            missingResources,
            desanitizedRelevantKeys
        );
    }
}
