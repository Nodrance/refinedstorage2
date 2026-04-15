package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes sanitized MRK-based LP data back to concrete resources.
 *
 * <p>Allocation greedily consumes concrete root storage for MRK members in member order.
 * Once concrete storage for a given MRK is exhausted, remaining demand falls back to the
 * first member of that MRK.
 */
public final class RecipeDesanitizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeDesanitizer.class);

    private RecipeDesanitizer() {
    }

    public static Map<ResourceKey, Long> copyConcreteStorage(final Map<ResourceKey, Long> concreteStorage) {
        Objects.requireNonNull(concreteStorage, "concreteStorage cannot be null");
        return new LinkedHashMap<>(concreteStorage);
    }

    public static ResourcePool decodeSanitizedResourcesToConcrete(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> remainingConcreteStorage
    ) {
        return decodeSanitizedResourcesToConcrete(sanitizedResources, remainingConcreteStorage, CancellationToken.NONE);
    }

    public static ResourcePool decodeSanitizedResourcesToConcrete(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> remainingConcreteStorage,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(remainingConcreteStorage, "remainingConcreteStorage cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final ResourcePool decoded = ResourcePool.empty();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            throwIfCancelled(cancellationToken);
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            allocateIntoPool(decoded, entry.getKey(), amount, remainingConcreteStorage);
        }

        LOGGER.info(
            "[LP] RecipeDesanitizer.decodeSanitizedResourcesToConcrete: "
                + "sanitized={} decoded={} remainingConcreteStorage={}",
            sanitizedResources,
            decoded,
            remainingConcreteStorage
        );
        return decoded;
    }

    public static ResourcePool convertToConcreteOutputs(final ResourcePool sanitizedResources) {
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

    /**
     * Decodes a sanitized MRK-based {@link ResourcePool} into a concrete pool whose keys are
     * single-member {@link MultiResourceKey}s.  For each MRK entry the requested amount is
     * satisfied greedily from {@code concreteStartingResources}, consuming each member in
     * member-list order.  Any remainder that cannot be resolved against concrete storage falls
     * back to the first member of the MRK (e.g. for missing resources).
     */
    public static ResourcePool decodeSanitizedResourcePool(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> concreteStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(concreteStartingResources, "concreteStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> remaining = new LinkedHashMap<>(concreteStartingResources);
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
        final Map<ResourceKey, Long> remainingConcreteStorage
    ) {
        long remaining = totalNeeded;
        for (final ResourceKey member : multiResourceKey.members()) {
            if (remaining <= 0L) {
                break;
            }
            final long available = Math.max(0L, remainingConcreteStorage.getOrDefault(member, 0L));
            final long used = Math.min(remaining, available);
            if (used > 0L) {
                decoded.addAmount(new MultiResourceKey(List.of(member)), used);
                remainingConcreteStorage.put(member, available - used);
                remaining -= used;
            }
        }
        if (remaining > 0L) {
            decoded.addAmount(new MultiResourceKey(List.of(multiResourceKey.members().getFirst())), remaining);
        }
    }

    public static List<RecipeApplicationStep> decodePlanSteps(
        final List<RecipeApplicationStep> steps,
        final Map<ResourceKey, Long> concreteStartingResources
    ) {
        return decodePlanSteps(steps, concreteStartingResources, CancellationToken.NONE);
    }

    public static List<RecipeApplicationStep> decodePlanSteps(
        final List<RecipeApplicationStep> steps,
        final Map<ResourceKey, Long> concreteStartingResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(concreteStartingResources, "concreteStartingResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final Map<ResourceKey, Long> remainingConcreteStorage = new LinkedHashMap<>(concreteStartingResources);
        final List<RecipeApplicationStep> decodedSteps = new java.util.ArrayList<>();
        for (final RecipeApplicationStep step : steps) {
            throwIfCancelled(cancellationToken);
            final ConcreteRecipe recipe = step.recipe();
            final ResourcePool concreteOutput = convertToConcreteOutputs(recipe.output());

            ResourcePool currentInput = null;
            long currentBatchTimesApplied = 0L;
            for (long index = 0L; index < step.timesApplied(); index++) {
                throwIfCancelled(cancellationToken);
                final ResourcePool concreteInput = decodeSanitizedResourcesToConcrete(
                    recipe.input(),
                    remainingConcreteStorage,
                    cancellationToken
                );
                if (currentInput != null && currentInput.asMap().equals(concreteInput.asMap())) {
                    currentBatchTimesApplied++;
                    continue;
                }
                if (currentInput != null) {
                    decodedSteps.add(new RecipeApplicationStep(
                        new ConcreteRecipe(
                            recipe.recipeId(),
                            recipe.sourcePatternId(),
                            currentInput,
                            concreteOutput,
                            recipe.priority()
                        ),
                        currentBatchTimesApplied
                    ));
                }
                currentInput = concreteInput;
                currentBatchTimesApplied = 1L;
            }

            if (currentInput != null) {
                decodedSteps.add(new RecipeApplicationStep(
                    new ConcreteRecipe(
                        recipe.recipeId(),
                        recipe.sourcePatternId(),
                        currentInput,
                        concreteOutput,
                        recipe.priority()
                    ),
                    currentBatchTimesApplied
                ));
            }
        }
        return List.copyOf(decodedSteps);
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
}
