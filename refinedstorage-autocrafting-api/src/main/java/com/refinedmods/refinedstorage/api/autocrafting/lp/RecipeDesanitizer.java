package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
		LOGGER.info(
			"[LP] RecipeDesanitizer.decodeSanitizedResources start: sanitized={} available={}",
			summarizePool(sanitizedResources),
			summarizePool(availableResources)
		);

		for (final Map.Entry<ResourceKey, Long> entry : sanitizedResources) {
			throwIfCancelled(cancellationToken);
			final ResourceKey resource = entry.getKey();
			final long amount = entry.getValue();
			if (amount <= 0L) {
				continue;
			}

			if (resource instanceof MultiResourceKey multiResourceKey) {
				final Map<ResourceKey, Long> allocation = multiResourceKey.allocateConcrete(amount, remainingAvailable);
				final long allocatedTotal = sumMapValues(allocation);
				LOGGER.info(
					"[LP] RecipeDesanitizer.decodeSanitizedResources multi-key allocation: requestedResource={} requestedAmount={} allocated={} allocatedTotal={} unallocated={}",
					resource,
					amount,
					allocation,
					allocatedTotal,
					Math.max(0L, amount - allocatedTotal)
				);
				for (final Map.Entry<ResourceKey, Long> allocated : allocation.entrySet()) {
					decoded.addAmount(allocated.getKey(), allocated.getValue());
					remainingAvailable.subtractAmount(allocated.getKey(), allocated.getValue());
				}
			} else {
				LOGGER.info(
					"[LP] RecipeDesanitizer.decodeSanitizedResources direct resource copy: resource={} amount={}",
					resource,
					amount
				);
				decoded.addAmount(resource, amount);
			}
		}
		LOGGER.info(
			"[LP] RecipeDesanitizer.decodeSanitizedResources result: decoded={} remainingAvailable={}",
			summarizePool(decoded),
			summarizePool(remainingAvailable)
		);

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
		LOGGER.info(
			"[LP] RecipeDesanitizer.decodePlanSteps start: inputStepCount={} available={}",
			steps.size(),
			summarizePool(availableResources)
		);

		for (final RecipeApplicationStep step : steps) {
			throwIfCancelled(cancellationToken);
			final ConcreteRecipe recipe = step.recipe();
			if (!containsMultiResourceKey(recipe.input())) {
				LOGGER.info(
					"[LP] RecipeDesanitizer.decodePlanSteps keeping step unchanged: recipeId={} timesApplied={} input={} output={}",
					recipe.recipeId(),
					step.timesApplied(),
					summarizePool(recipe.input()),
					summarizePool(recipe.output())
				);
				decoded.add(step);
				continue;
			}
			LOGGER.info(
				"[LP] RecipeDesanitizer.decodePlanSteps decoding sanitized step: recipeId={} timesApplied={} sanitizedInput={} output={}",
				recipe.recipeId(),
				step.timesApplied(),
				summarizePool(recipe.input()),
				summarizePool(recipe.output())
			);

			decodeSanitizedStep(step, remainingAvailable, decoded, cancellationToken);
		}
		LOGGER.info(
			"[LP] RecipeDesanitizer.decodePlanSteps result: decodedStepCount={} remainingAvailable={}",
			decoded.size(),
			summarizePool(remainingAvailable)
		);

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

			final ResourcePool perIterationInput = decodeSingleIterationInput(recipe.input(), remainingAvailable);
			final long batchSize = computeBatchSize(iterationsLeft, perIterationInput, remainingAvailable);
			LOGGER.info(
				"[LP] RecipeDesanitizer.decodeSanitizedStep batch: recipeId={} iterationsLeft={} perIterationInput={} batchSize={} remainingAvailableBefore={}",
				recipe.recipeId(),
				iterationsLeft,
				summarizePool(perIterationInput),
				batchSize,
				summarizePool(remainingAvailable)
			);
			if (batchSize <= 0L) {
				LOGGER.info(
					"[LP] RecipeDesanitizer.decodeSanitizedStep stopping decode: recipeId={} batchSize={} iterationsLeft={} (insufficient concrete inputs)",
					recipe.recipeId(),
					batchSize,
					iterationsLeft
				);
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
			LOGGER.info(
				"[LP] RecipeDesanitizer.decodeSanitizedStep emitted decoded step: recipeId={} emittedIterations={} iterationsLeftAfter={} remainingAvailableAfter={}",
				recipe.recipeId(),
				batchSize,
				iterationsLeft,
				summarizePool(remainingAvailable)
			);
		}
	}

	private static ResourcePool decodeSingleIterationInput(
		final ResourcePool sanitizedInput,
		final ResourcePool remainingAvailable
	) {
		final ResourcePool perIterationInput = ResourcePool.empty();

		for (final Map.Entry<ResourceKey, Long> input : sanitizedInput) {
			final ResourceKey resource = input.getKey();
			final long perIteration = input.getValue();
			if (perIteration <= 0L) {
				continue;
			}

			if (resource instanceof MultiResourceKey multiResourceKey) {
				final Map<ResourceKey, Long> allocation = multiResourceKey.allocateConcrete(perIteration, remainingAvailable);
				for (final Map.Entry<ResourceKey, Long> allocated : allocation.entrySet()) {
					perIterationInput.addAmount(allocated.getKey(), allocated.getValue());
				}
			} else {
				perIterationInput.addAmount(resource, perIteration);
			}
		}

		return perIterationInput;
	}

	private static long computeBatchSize(
		final long iterationsLeft,
		final ResourcePool perIterationInput,
		final ResourcePool remainingAvailable
	) {
		long batchSize = iterationsLeft;
		for (final Map.Entry<ResourceKey, Long> entry : perIterationInput) {
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
		for (final Map.Entry<ResourceKey, Long> entry : perIterationInput) {
			final long toSubtract = entry.getValue() * batchSize;
			if (toSubtract > 0L) {
				remainingAvailable.subtractAmount(entry.getKey(), toSubtract);
			}
		}
	}

	private static boolean containsMultiResourceKey(final ResourcePool input) {
		for (final Map.Entry<ResourceKey, Long> entry : input) {
			if (entry.getKey() instanceof MultiResourceKey) {
				return true;
			}
		}
		return false;
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
		for (final Map.Entry<ResourceKey, Long> ignored : pool) {
			count++;
		}
		return count;
	}

	private static long sumPoolValues(final ResourcePool pool) {
		long total = 0L;
		for (final Map.Entry<ResourceKey, Long> entry : pool) {
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
}
