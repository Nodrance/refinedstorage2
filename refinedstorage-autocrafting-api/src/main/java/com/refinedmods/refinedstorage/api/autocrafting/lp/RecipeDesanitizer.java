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
 * Allocation greedily consumes concrete root storage for MRK members in member order.
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

    public static LpResourceSet decodeSanitizedResourcesToConcrete(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> remainingConcreteStorage
    ) {
        return decodeSanitizedResourcesToConcrete(sanitizedResources, remainingConcreteStorage, CancellationToken.NONE);
    }

    public static LpResourceSet decodeSanitizedResourcesToConcrete(
        final ResourcePool sanitizedResources,
        final Map<ResourceKey, Long> remainingConcreteStorage,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
        Objects.requireNonNull(remainingConcreteStorage, "remainingConcreteStorage cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        final LpResourceSet decoded = new LpResourceSet();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            throwIfCancelled(cancellationToken);
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            allocateInto(decoded, entry.getKey(), amount, remainingConcreteStorage);
        }

        LOGGER.info(
            "[LP] RecipeDesanitizer.decodeSanitizedResourcesToConcrete: sanitized={} decoded={} remainingConcreteStorage={}",
            sanitizedResources,
            decoded,
            remainingConcreteStorage
        );
        return decoded;
    }

    public static LpResourceSet convertToConcreteOutputs(final ResourcePool sanitizedResources) {
        Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");

        final LpResourceSet decoded = new LpResourceSet();
        for (final Map.Entry<MultiResourceKey, Long> entry : sanitizedResources) {
            final long amount = entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            decoded.addAmount(firstMemberOf(entry.getKey()), amount);
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

    public static List<RecipeApplicationStep> decodePlanSteps(
        final List<RecipeApplicationStep> steps,
        final ResourcePool availableResources
    ) {
        return decodePlanSteps(steps, availableResources, CancellationToken.NONE);
    }

    public static List<RecipeApplicationStep> decodePlanSteps(
        final List<RecipeApplicationStep> steps,
        final ResourcePool availableResources,
        final CancellationToken cancellationToken
    ) {
        Objects.requireNonNull(steps, "steps cannot be null");
        Objects.requireNonNull(availableResources, "availableResources cannot be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        throwIfCancelled(cancellationToken);

        return List.copyOf(steps);
    }

    private static void allocateInto(
        final LpResourceSet decoded,
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
                decoded.addAmount(member, used);
                remainingConcreteStorage.put(member, available - used);
                remaining -= used;
            }
        }

        if (remaining > 0L) {
            decoded.addAmount(firstMemberOf(multiResourceKey), remaining);
        }
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
