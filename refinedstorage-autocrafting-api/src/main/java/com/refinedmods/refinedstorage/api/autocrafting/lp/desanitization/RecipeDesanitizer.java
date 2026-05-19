package com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.MultiResourceKey;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.RecipeApplicationPath;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.RecipeApplicationSet;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.RecipeApplicationStep;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.ResourcePool;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.SanitizedRecipe;

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
        final RecipeApplicationSet applicationSet = path.applicationSet();
        final Map<ResourceKey, Long> decodedUsedResources = decodeSanitizedResourcePoolToDesanitized(
            applicationSet.usedResources(),
            sanitizedStartingResources,
            cancellationToken
        );
        final Map<ResourceKey, Long> decodedFinalInventoryValues = decodeSanitizedResourcesToDesanitizedRaw(
            applicationSet.finalInventoryValues(),
            cancellationToken
        );
        final Map<ResourceKey, Long> decodedMissingResources = decodeSanitizedResourcesToDesanitized(
            applicationSet.missingResources(),
            cancellationToken
        );
        final List<ResourceKey> decodedRelevantResourceKeys = decodeRelevantResourceKeysToDesanitized(
            applicationSet.relevantResourceKeys(),
            cancellationToken
        );
        final Set<ResourceKey> relevantResourceKeys = new LinkedHashSet<>(decodedRelevantResourceKeys);
        relevantResourceKeys.addAll(decodedMissingResources.keySet());
        final Map<ResourceKey, Long> decodedPeakUsage = decodeSanitizedResourcePoolToDesanitized(
            path.peakResourceUsage(),
            sanitizedStartingResources,
            cancellationToken
        );

        return new DesanitizedRecipeApplicationPath(
            decodedSteps,
            decodedUsedResources,
            decodedFinalInventoryValues,
            decodedMissingResources,
            List.copyOf(relevantResourceKeys),
            decodedPeakUsage,
            path.hasCycles()
        );
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
                            buildDesanitizedLayout(currentInput, sanitizedOutput)
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
                        buildDesanitizedLayout(currentInput, sanitizedOutput)
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

    // Builds a PatternLayout from desanitized input/output maps.
    // Each input entry becomes a single-resource Ingredient; each output entry becomes a ResourceAmount.
    private static PatternLayout buildDesanitizedLayout(
        final Map<ResourceKey, Long> input,
        final Map<ResourceKey, Long> output
    ) {
        final List<Ingredient> ingredients = new ArrayList<>(input.size());
        for (final Map.Entry<ResourceKey, Long> entry : input.entrySet()) {
            ingredients.add(new Ingredient(entry.getValue(), List.of(entry.getKey())));
        }
        final List<ResourceAmount> outputs = new ArrayList<>(output.size());
        for (final Map.Entry<ResourceKey, Long> entry : output.entrySet()) {
            outputs.add(new ResourceAmount(entry.getKey(), entry.getValue()));
        }
        return PatternLayout.internal(ingredients, outputs, List.of());
    }

    private static List<ResourceKey> decodeRelevantResourceKeysToDesanitized(
        final List<MultiResourceKey> relevantResourceKeys,
        final CancellationToken cancellationToken
    ) {
        final Set<ResourceKey> desanitizedRelevantKeys = new LinkedHashSet<>();
        for (final MultiResourceKey mrk : relevantResourceKeys) {
            throwIfCancelled(cancellationToken);
            desanitizedRelevantKeys.addAll(mrk.members());
        }
        return List.copyOf(new ArrayList<>(desanitizedRelevantKeys));
    }
}
