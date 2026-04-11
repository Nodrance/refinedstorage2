package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes sanitized LP data back to concrete resources by replacing {@link MultiResourceKey}
 * entries with concrete member allocations based on available storage.
 */
public final class RecipeDesanitizer {
	private static final Logger LOGGER = LoggerFactory.getLogger(RecipeDesanitizer.class);

	private RecipeDesanitizer() {
	}

	/**
	 * Converts an LP ResourcePool (which may contain MultiResourceKey entries) to a concrete
	 * Map<ResourceKey, Long> for external consumption.
	 * MultiResourceKey entries are expanded to their member ResourceKeys with fallback allocation.
	 * @param sanitizedPool resource pool potentially containing MultiResourceKey entries
	 * @param availableResources available storage for allocation fallback
	 * @return map with only concrete ResourceKey entries
	 */
	public static Map<ResourceKey, Long> convertResourcePoolToMap(
		final ResourcePool sanitizedPool,
		final ResourcePool availableResources
	) {
		Objects.requireNonNull(sanitizedPool, "sanitizedPool cannot be null");
		Objects.requireNonNull(availableResources, "availableResources cannot be null");
		return convertResourcePoolToMap(sanitizedPool, availableResources, CancellationToken.NONE);
	}

	/**
	 * Converts an LP ResourcePool (which may contain MultiResourceKey entries) to a concrete
	 * Map<ResourceKey, Long> for external consumption.
	 * @param sanitizedPool resource pool potentially containing MultiResourceKey entries
	 * @param availableResources available storage for allocation fallback
	 * @param cancellationToken cancellation token to stop conversion
	 * @return map with only concrete ResourceKey entries
	 */
	public static Map<ResourceKey, Long> convertResourcePoolToMap(
		final ResourcePool sanitizedPool,
		final ResourcePool availableResources,
		final CancellationToken cancellationToken
	) {
		Objects.requireNonNull(sanitizedPool, "sanitizedPool cannot be null");
		Objects.requireNonNull(availableResources, "availableResources cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");

		final Map<ResourceKey, Long> result = new LinkedHashMap<>();

		for (final java.util.Map.Entry<Object, Long> entry : sanitizedPool) {
			if (entry.getKey() instanceof ResourceKey resourceKey) {
				// Concrete ResourceKey: direct mapping
				result.put(resourceKey, entry.getValue());
			} else if (entry.getKey() instanceof MultiResourceKey multiKey) {
				// MultiResourceKey: allocate to members using fallback strategy
				final long needed = entry.getValue();
				allocateMultiKeyToConcreteResources(multiKey, needed, availableResources, result, cancellationToken);
			}
		}

		return result;
	}

	/**
	 * Allocates MultiResourceKey amounts to their concrete member ResourceKeys.
	 * Uses greedy member-by-member allocation.
	 */
	private static void allocateMultiKeyToConcreteResources(
		final MultiResourceKey multiKey,
		final long needed,
		final ResourcePool available,
		final Map<ResourceKey, Long> result,
		final CancellationToken cancellationToken
	) {
		long remaining = needed;
		for (final ResourceKey member : multiKey.members()) {
			if (remaining <= 0) {
				break;
			}
			final long allocated = allocateConcrete(multiKey, remaining, available, member);
			if (allocated > 0) {
				result.merge(member, allocated, Long::sum);
				remaining -= allocated;
			}
		}
	}

	/**
	 * Allocates a single member from a MultiResourceKey.
	 */
	private static long allocateConcrete(
		final MultiResourceKey multiKey,
		final long needed,
		final ResourcePool available,
		final ResourceKey member
	) {
		final long available_amount = available.getAmount(member);
		return Math.min(needed, Math.max(0L, available_amount));
	}

	/**
	 * Decodes a set of sanitized resource keys (which may include MultiResourceKey) to concrete ResourceKey set.
	 * MultiResourceKey entries are expanded to their member resources.
	 */
	public static java.util.Set<ResourceKey> decodeResourceKeys(final java.util.Collection<Object> sanitizedKeys) {
		Objects.requireNonNull(sanitizedKeys, "sanitizedKeys cannot be null");
		final java.util.Set<ResourceKey> result = new LinkedHashSet<>();
		for (final Object key : sanitizedKeys) {
			if (key instanceof ResourceKey resourceKey) {
				result.add(resourceKey);
			} else if (key instanceof MultiResourceKey multiKey) {
				result.addAll(multiKey.members());
			}
		}
		return result;
	}

	/**
	 * Converts an LP ResourcePool with sanitized keys to concrete Map<ResourceKey, Long>.
	 * This is the desanitization entry point for converting from LP types back to external types.
	 */
	public static java.util.Map<ResourceKey, Long> convertFromLpResourcePool(
		final ResourcePool lpResourcePool,
		final ResourcePool availableResources,
		final CancellationToken cancellationToken
	) {
		return convertResourcePoolToMap(lpResourcePool, availableResources, cancellationToken);
	}

	public static RecipeApplicationPath decodeRecipeApplicationPath(
		final RecipeApplicationPath path,
		final ResourcePool availableResources,
		final CancellationToken cancellationToken
	) {
		Objects.requireNonNull(path, "path cannot be null");
		Objects.requireNonNull(availableResources, "availableResources cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");

		final List<RecipeApplicationStep> decodedSteps = decodePlanSteps(
			path.steps(),
			availableResources,
			cancellationToken
		);

		final RecipeApplicationSet original = path.applicationSet();
		final ResourcePool decodedUsed = decodeSanitizedResources(
			original.usedResources(),
			availableResources,
			cancellationToken
		);
		final ResourcePool decodedMissing = decodeSanitizedResources(
			original.missingResources(),
			availableResources,
			cancellationToken
		);
		final ResourcePool decodedFinal = decodeSanitizedResources(
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
			new ArrayList<>(decodedRelevantResources)
		);

		return new RecipeApplicationPath(decodedSet, decodedSteps);
	}

	/**
	 * Decodes a sanitized resource pool by replacing {@link MultiResourceKey} entries
	 * with concrete member allocations from available storage.
	 */
	public static ResourcePool decodeSanitizedResources(
		final ResourcePool sanitizedResources,
		final ResourcePool availableResources
	) {
		return decodeSanitizedResources(sanitizedResources, availableResources, CancellationToken.NONE);
	}

	/**
	 * Decodes a sanitized resource pool by replacing {@link MultiResourceKey} entries
	 * with concrete member allocations from available storage.
	 */
	public static ResourcePool decodeSanitizedResources(
		final ResourcePool sanitizedResources,
		final ResourcePool availableResources,
		final CancellationToken cancellationToken
	) {
		Objects.requireNonNull(sanitizedResources, "sanitizedResources cannot be null");
		Objects.requireNonNull(availableResources, "availableResources cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
		throwIfCancelled(cancellationToken);

		final ResourcePool decoded = ResourcePool.empty();
		final ResourcePool remainingAvailable = availableResources.copy();
		// LOGGER.info(
		// 	"[LP] RecipeDesanitizer.decodeSanitizedResources start: sanitized={} available={}",
		// 	summarizePool(sanitizedResources),
		// 	summarizePool(availableResources)
		// );

		for (final Map.Entry<Object, Long> entry : sanitizedResources) {
			throwIfCancelled(cancellationToken);
			final Object resource = entry.getKey();
			final long amount = entry.getValue();
			if (amount <= 0L) {
				continue;
			}

			if (resource instanceof MultiResourceKey multiResourceKey) {
				final Map<ResourceKey, Long> allocation = allocateConcrete(multiResourceKey, amount, remainingAvailable);
				final long allocatedTotal = sumMapValues(allocation);
				// LOGGER.info(
				// 	"[LP] RecipeDesanitizer.decodeSanitizedResources multi-key allocation: requestedResource={} requestedAmount={} allocated={} allocatedTotal={} unallocated={}",
				// 	resource,
				// 	amount,
				// 	allocation,
				// 	allocatedTotal,
				// 	Math.max(0L, amount - allocatedTotal)
				// );
				for (final Map.Entry<ResourceKey, Long> allocated : allocation.entrySet()) {
					decoded.addAmount(allocated.getKey(), allocated.getValue());
					remainingAvailable.subtractAmount(allocated.getKey(), allocated.getValue());
				}
				if (allocatedTotal < amount) {
					final long unallocated = amount - allocatedTotal;
					final ResourceKey fallbackResource = fallbackResourceFor(multiResourceKey);
					decoded.addAmount(fallbackResource, unallocated);
					// LOGGER.info(
					// 	"[LP] RecipeDesanitizer.decodeSanitizedResources falling back unresolved multi-key amount to first option: multiKey={} fallbackResource={} unresolvedAmount={}",
					// 	resource,
					// 	fallbackResource,
					// 	unallocated
					// );
				}
			} else if (resource instanceof ResourceKey resourceKey) {
				// LOGGER.info(
				// 	"[LP] RecipeDesanitizer.decodeSanitizedResources direct resource copy: resource={} amount={}",
				// 	resource,
				// 	amount
				// );
				decoded.addAmount(resourceKey, amount);
			}
		}
		// LOGGER.info(
		// 	"[LP] RecipeDesanitizer.decodeSanitizedResources result: decoded={} remainingAvailable={}",
		// 	summarizePool(decoded),
		// 	summarizePool(remainingAvailable)
		// );

		return decoded;
	}

	/**
	 * Decodes sanitized plan steps by replacing {@link MultiResourceKey} inputs with concrete
	 * resources backed by storage. Steps are split when a per-iteration allocation cannot be
	 * repeated for all remaining iterations.
	 */
	public static List<RecipeApplicationStep> decodePlanSteps(
		final List<RecipeApplicationStep> steps,
		final ResourcePool availableResources
	) {
		return decodePlanSteps(steps, availableResources, CancellationToken.NONE);
	}

	/**
	 * Decodes sanitized plan steps by replacing {@link MultiResourceKey} inputs with concrete
	 * resources backed by storage. Steps are split when a per-iteration allocation cannot be
	 * repeated for all remaining iterations.
	 */
	public static List<RecipeApplicationStep> decodePlanSteps(
		final List<RecipeApplicationStep> steps,
		final ResourcePool availableResources,
		final CancellationToken cancellationToken
	) {
		Objects.requireNonNull(steps, "steps cannot be null");
		Objects.requireNonNull(availableResources, "availableResources cannot be null");
		Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
		throwIfCancelled(cancellationToken);

		final List<RecipeApplicationStep> decoded = new ArrayList<>();
		final ResourcePool remainingAvailable = availableResources.copy();
		// LOGGER.info(
		// 	"[LP] RecipeDesanitizer.decodePlanSteps start: inputStepCount={} available={}",
		// 	steps.size(),
		// 	summarizePool(availableResources)
		// );

		for (final RecipeApplicationStep step : steps) {
			throwIfCancelled(cancellationToken);
			final ConcreteRecipe recipe = step.recipe();
			if (!containsMultiResourceKey(recipe.input())) {
				// LOGGER.info(
				// 	"[LP] RecipeDesanitizer.decodePlanSteps keeping step unchanged: recipeId={} timesApplied={} input={} output={}",
				// 	recipe.recipeId(),
				// 	step.timesApplied(),
				// 	summarizePool(recipe.input()),
				// 	summarizePool(recipe.output())
				// );
				decoded.add(step);
				continue;
			}
			// LOGGER.info(
			// 	"[LP] RecipeDesanitizer.decodePlanSteps decoding sanitized step: recipeId={} timesApplied={} sanitizedInput={} output={}",
			// 	recipe.recipeId(),
			// 	step.timesApplied(),
			// 	summarizePool(recipe.input()),
			// 	summarizePool(recipe.output())
			// );

			decodeSanitizedStep(step, remainingAvailable, decoded, cancellationToken);
		}
		// LOGGER.info(
		// 	"[LP] RecipeDesanitizer.decodePlanSteps result: decodedStepCount={} remainingAvailable={}",
		// 	decoded.size(),
		// 	summarizePool(remainingAvailable)
		// );

		return List.copyOf(decoded);
	}

	private static void decodeSanitizedStep(
		final RecipeApplicationStep step,
		final ResourcePool remainingAvailable,
		final List<RecipeApplicationStep> decoded,
		final CancellationToken cancellationToken
	) {
		long iterationsLeft = step.timesApplied();
		final ConcreteRecipe recipe = step.recipe();

		while (iterationsLeft > 0L) {
			throwIfCancelled(cancellationToken);

			final DecodedIterationInput decodedInput = decodeSingleIterationInput(recipe.input(), remainingAvailable);
			if (!decodedInput.unresolvedInput().isEmpty()) {
				// LOGGER.info(
				// 	"[LP] RecipeDesanitizer.decodeSanitizedStep unresolved per-iteration input; stopping decode for recipeId={} unresolvedInput={} remainingAvailable={}",
				// 	recipe.recipeId(),
				// 	summarizePool(decodedInput.unresolvedInput()),
				// 	summarizePool(remainingAvailable)
				// );
				break;
			}
			final ResourcePool perIterationInput = decodedInput.input();
			final long batchSize = computeBatchSize(iterationsLeft, perIterationInput, remainingAvailable);
			// LOGGER.info(
			// 	"[LP] RecipeDesanitizer.decodeSanitizedStep batch: recipeId={} iterationsLeft={} perIterationInput={} batchSize={} remainingAvailableBefore={}",
			// 	recipe.recipeId(),
			// 	iterationsLeft,
			// 	summarizePool(perIterationInput),
			// 	batchSize,
			// 	summarizePool(remainingAvailable)
			// );
			if (batchSize <= 0L) {
				// LOGGER.info(
				// 	"[LP] RecipeDesanitizer.decodeSanitizedStep stopping decode: recipeId={} batchSize={} iterationsLeft={} (insufficient concrete inputs)",
				// 	recipe.recipeId(),
				// 	batchSize,
				// 	iterationsLeft
				// );
				break;
			}

			subtractBatchUsage(remainingAvailable, perIterationInput, batchSize);

			final ConcreteRecipe decodedRecipe = new ConcreteRecipe(
				recipe.recipeId(),
				recipe.sourcePatternId(),
				perIterationInput,
				recipe.output().copy(),
				recipe.priority()
			);
			decoded.add(new RecipeApplicationStep(decodedRecipe, batchSize));
			iterationsLeft -= batchSize;
			// LOGGER.info(
			// 	"[LP] RecipeDesanitizer.decodeSanitizedStep emitted decoded step: recipeId={} emittedIterations={} iterationsLeftAfter={} remainingAvailableAfter={}",
			// 	recipe.recipeId(),
			// 	batchSize,
			// 	iterationsLeft,
			// 	summarizePool(remainingAvailable)
			// );
		}
	}

	private static DecodedIterationInput decodeSingleIterationInput(
		final ResourcePool sanitizedInput,
		final ResourcePool remainingAvailable
	) {
		final ResourcePool perIterationInput = ResourcePool.empty();
		final ResourcePool unresolvedPerIterationInput = ResourcePool.empty();

		for (final Map.Entry<Object, Long> input : sanitizedInput) {
			final Object resource = input.getKey();
			final long perIteration = input.getValue();
			if (perIteration <= 0L) {
				continue;
			}

			if (resource instanceof MultiResourceKey multiResourceKey) {
				final Map<ResourceKey, Long> allocation = allocateConcrete(
					multiResourceKey,
					perIteration,
					remainingAvailable
				);
				final long allocatedTotal = sumMapValues(allocation);
				for (final Map.Entry<ResourceKey, Long> allocated : allocation.entrySet()) {
					perIterationInput.addAmount(allocated.getKey(), allocated.getValue());
				}
				if (allocatedTotal < perIteration) {
					final long unresolved = perIteration - allocatedTotal;
					final ResourceKey fallbackResource = fallbackResourceFor(multiResourceKey);
					perIterationInput.addAmount(fallbackResource, unresolved);
					// LOGGER.info(
					// 	"[LP] RecipeDesanitizer.decodeSingleIterationInput falling back unresolved multi-key amount to first option: multiKey={} fallbackResource={} unresolvedAmount={}",
					// 	resource,
					// 	fallbackResource,
					// 	unresolved
					// );
				}
			} else if (resource instanceof ResourceKey resourceKey) {
				perIterationInput.addAmount(resourceKey, perIteration);
			}
		}

		return new DecodedIterationInput(perIterationInput, unresolvedPerIterationInput);
	}

	private static long computeBatchSize(
		final long iterationsLeft,
		final ResourcePool perIterationInput,
		final ResourcePool remainingAvailable
	) {
		long batchSize = iterationsLeft;
		for (final Map.Entry<Object, Long> entry : perIterationInput) {
			final long neededPerIteration = entry.getValue();
			if (neededPerIteration <= 0L) {
				continue;
			}
			final long available = Math.max(0L, remainingAvailable.getAmount(entry.getKey()));
			batchSize = Math.min(batchSize, available / neededPerIteration);
		}
		return batchSize;
	}

	private static void subtractBatchUsage(
		final ResourcePool remainingAvailable,
		final ResourcePool perIterationInput,
		final long batchSize
	) {
		for (final Map.Entry<Object, Long> entry : perIterationInput) {
			final long toSubtract = entry.getValue() * batchSize;
			if (toSubtract > 0L) {
				remainingAvailable.subtractAmount(entry.getKey(), toSubtract);
			}
		}
	}

	private static boolean containsMultiResourceKey(final ResourcePool input) {
		for (final Map.Entry<Object, Long> entry : input) {
			if (entry.getKey() instanceof MultiResourceKey) {
				return true;
			}
		}
		return false;
	}

	private static Map<ResourceKey, Long> allocateConcrete(
		final MultiResourceKey multiResourceKey,
		final long totalNeeded,
		final ResourcePool available
	) {
		final Map<ResourceKey, Long> allocation = new LinkedHashMap<>();
		long remaining = totalNeeded;
		for (final ResourceKey member : multiResourceKey.members()) {
			if (remaining <= 0) {
				break;
			}
			final long memberAvailable = Math.max(0L, available.getAmount(member));
			final long use = Math.min(remaining, memberAvailable);
			if (use > 0) {
				allocation.put(member, use);
				remaining -= use;
			}
		}
		return allocation;
	}

	private static List<ResourceKey> decodeRelevantResourceKeys(final List<Object> relevantResourceKeys) {
		final LinkedHashSet<ResourceKey> decoded = new LinkedHashSet<>();
		for (final Object key : relevantResourceKeys) {
			if (key instanceof MultiResourceKey multiResourceKey) {
				decoded.addAll(multiResourceKey.members());
			} else if (key instanceof ResourceKey resourceKey) {
				decoded.add(resourceKey);
			}
		}
		return List.copyOf(decoded);
	}

	private static void throwIfCancelled(final CancellationToken cancellationToken) {
		if (cancellationToken.isCancelled()) {
			throw new java.util.concurrent.CancellationException("LP recipe desanitizer cancelled");
		}
	}

	private static String summarizePool(final ResourcePool pool) {
		return "{resources=" + countPoolEntries(pool) + ", total=" + sumPoolValues(pool) + ", values=" + pool + "}";
	}

	private static int countPoolEntries(final ResourcePool pool) {
		int count = 0;
		for (final Map.Entry<Object, Long> ignored : pool) {
			count++;
		}
		return count;
	}

	private static long sumPoolValues(final ResourcePool pool) {
		long total = 0L;
		for (final Map.Entry<Object, Long> entry : pool) {
			total += entry.getValue();
		}
		return total;
	}

	private static long sumMapValues(final Map<ResourceKey, Long> values) {
		long total = 0L;
		for (final long value : values.values()) {
			total += value;
		}
		return total;
	}

	private static ResourceKey fallbackResourceFor(final MultiResourceKey multiResourceKey) {
		if (!multiResourceKey.members().isEmpty()) {
			return multiResourceKey.members().getFirst();
		}
		throw new IllegalStateException("MultiResourceKey has no members");
	}

	private record DecodedIterationInput(ResourcePool input, ResourcePool unresolvedInput) {
		private DecodedIterationInput {
			input = input.copy();
			unresolvedInput = unresolvedInput.copy();
		}
	}
}
