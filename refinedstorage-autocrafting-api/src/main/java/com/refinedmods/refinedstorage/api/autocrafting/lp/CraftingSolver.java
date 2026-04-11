package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CraftingSolver {
	private static final Logger LOGGER = LoggerFactory.getLogger(CraftingSolver.class);

	private final LinearSolver.Options options;
	private final CancellationToken cancellationToken;

	public CraftingSolver() {
		this(LinearSolver.Options.defaults(), CancellationToken.NONE);
	}

	public CraftingSolver(final CancellationToken cancellationToken) {
		this(LinearSolver.Options.defaults(), cancellationToken);
	}

	public CraftingSolver(final LinearSolver.Options options) {
		this(options, CancellationToken.NONE);
	}

	public CraftingSolver(final LinearSolver.Options options, final CancellationToken cancellationToken) {
		this.options = Objects.requireNonNull(options, "options cannot be null");
		this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
	}

	public Optional<RecipeApplicationPath> solve(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final ResourcePool target
	) {
		validateInputs(recipes, startingResources, target);
		try {
			throwIfCancelled();
			final boolean canCraftTarget = canCraftTarget(recipes, startingResources, target);
			LOGGER.info(
				"[LP] solve: initial feasibility check result for target {} is {}",
				target,
				canCraftTarget
			);
			throwIfCancelled();

			if (!canCraftTarget) {
				final DeficitAnalysisResult deficitAnalysis =
					computeRequiredBaseItemsAndSolution(recipes, startingResources, target);
				LOGGER.info(
					"[LP] solve: target requires base items (resourceCount={}, totalAmount={})",
					deficitAnalysis.requiredBaseItems(),
					totalAmount(deficitAnalysis.requiredBaseItems())
				);
				throwIfCancelled();
				return buildRecipeApplicationPath(recipes, startingResources, deficitAnalysis);
			}

			final CycleEliminationResult cycleEliminationResult =
				findRecipeApplicationPlanViaCycleEliminationInternal(recipes, startingResources, target);
			throwIfCancelled();
			if (cycleEliminationResult.recipeApplicationResult().isPresent()) {
				LOGGER.info("[LP] solve: found executable recipe application path without fallback deficit analysis");
				return cycleEliminationResult.recipeApplicationResult();
			}

			final Set<UUID> disabledRecipeIds = cycleEliminationResult.fallbackDisabledRecipeIds();
			final List<ConcreteRecipe> reducedRecipes = recipes.stream()
				.filter(recipe -> !disabledRecipeIds.contains(recipe.recipeId()))
				.toList();
			LOGGER.info(
				"[LP] solve: cycle elimination fallback disabled {} recipes; retrying with {} recipes",
				disabledRecipeIds.size(),
				reducedRecipes.size()
			);
			final DeficitAnalysisResult deficitAnalysis =
				computeRequiredBaseItemsAndSolution(reducedRecipes, startingResources, target);
			LOGGER.info(
				"[LP] solve: fallback deficit analysis requires base items {}",
				deficitAnalysis.requiredBaseItems()
			);
			throwIfCancelled();
			return buildRecipeApplicationPath(reducedRecipes, startingResources, deficitAnalysis);
		} catch (final CancellationException e) {
			LOGGER.info("[LP] solve() cancelled.");
			return Optional.empty();
		}
	}

	public ResourcePool computeRequiredBaseItems(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final ResourcePool target
	) {
		validateInputs(recipes, startingResources, target);
		try {
			throwIfCancelled();
			final ResourcePool required = computeRequiredBaseItemsAndSolution(recipes, startingResources, target)
				.requiredBaseItems();
			LOGGER.info(
				"[LP] computeRequiredBaseItems: target={} required={}",
				target,
				required
			);
			return required;
		} catch (final CancellationException e) {
			LOGGER.info("[LP] computeRequiredBaseItems() cancelled.");
			return ResourcePool.empty();
		}
	}

	private CycleEliminationResult findRecipeApplicationPlanViaCycleElimination(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final ResourcePool target
	) {
		validateInputs(recipes, startingResources, target);
		try {
			throwIfCancelled();
			return findRecipeApplicationPlanViaCycleEliminationInternal(recipes, startingResources, target);
		} catch (final CancellationException e) {
			LOGGER.info("[LP] findRecipeApplicationPlanViaCycleElimination() cancelled.");
			return new CycleEliminationResult(Optional.empty(), Set.of());
		}
	}

	private boolean canCraftTarget(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final ResourcePool target
	) {
		validateInputs(recipes, startingResources, target);
		if (target.isEmpty()) {
			return true;
		}

		final Set<ResourceKey> relevantResources = new LinkedHashSet<>(
			toConcreteResourceKeys(RecipeAnalyzer.collectRelevantResourceKeys(recipes))
		);
		relevantResources.addAll(toConcreteResourceKeys(target.resourceKeys()));

		return new LinearSolver(
			recipes,
			relevantResources,
			startingResources,
			target,
			Set.of(),
			Set.of(),
			options,
			cancellationToken
		).hasFeasibleSolution();
	}

	private DeficitAnalysisResult computeRequiredBaseItemsAndSolution(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final ResourcePool target
	) {
		validateInputs(recipes, startingResources, target);
		throwIfCancelled();

		final List<ConcreteRecipe> selectedRecipes =
			RecipeAnalyzer.selectTopPriorityRecipesPerOutputResource(recipes);
		final Set<ResourceKey> relevantResources = toConcreteResourceKeys(
			RecipeAnalyzer.collectRelevantResourceKeys(selectedRecipes)
		);
		relevantResources.addAll(toConcreteResourceKeys(target.resourceKeys()));

		final Set<ResourceKey> deficitResources = new LinkedHashSet<>(
			RecipeAnalyzer.collectLeafResources(selectedRecipes)
		);

		if (selectedRecipes.isEmpty()) {
			final ResourcePool required = new ResourcePool();
			for (final ResourceKey resource : deficitResources) {
				throwIfCancelled();
				final long needed = Math.max(0L, target.getAmount(resource) - startingResources.getAmount(resource));
				if (needed > 0) {
					required.addAmount(resource, needed);
				}
			}
			return new DeficitAnalysisResult(required, Optional.empty());
		}

		final Set<ResourceKey> unconstrainedResources = new LinkedHashSet<>(deficitResources);

		final LinearSolver.Result result = new LinearSolver(
			selectedRecipes,
			relevantResources,
			startingResources,
			target,
			unconstrainedResources,
			Set.of(),
			options,
			cancellationToken
		).lexicographicMinimum();

		final ResourcePool required = new ResourcePool();
		for (final ResourceKey resource : deficitResources) {
			throwIfCancelled();
			final long finalInventory = result == null
				? startingResources.getAmount(resource)
				: result.finalInventoryValues().getAmount(resource);
			final long needed = Math.max(0L, target.getAmount(resource) - finalInventory);
			if (needed > 0) {
				required.addAmount(resource, needed);
			}
		}

		return new DeficitAnalysisResult(required, Optional.ofNullable(result));
	}

	private Optional<RecipeApplicationPath> buildRecipeApplicationPath(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final DeficitAnalysisResult deficitAnalysis
	) {
		if (deficitAnalysis.solution().isEmpty()) {
			return Optional.empty();
		}

		final LinearSolver.Result solution = deficitAnalysis.solution().get();
		final Map<UUID, ConcreteRecipe> recipeById = new LinkedHashMap<>();
		for (final ConcreteRecipe recipe : recipes) {
			recipeById.put(recipe.recipeId(), recipe);
		}

		final Map<ConcreteRecipe, Long> valuesByRecipe = new LinkedHashMap<>();
		for (final Map.Entry<UUID, Long> entry : solution.recipeValues().entrySet()) {
			final ConcreteRecipe recipe = recipeById.get(entry.getKey());
			if (recipe != null && entry.getValue() > 0L) {
				valuesByRecipe.put(recipe, entry.getValue());
			}
		}

		final RecipeApplicationSet applicationSet = new RecipeApplicationSet(
			recipes,
			valuesByRecipe,
			computeUsedResources(valuesByRecipe),
			solution.finalInventoryValues(),
			deficitAnalysis.requiredBaseItems(),
			new java.util.ArrayList<>(RecipeAnalyzer.collectRelevantResourceKeys(recipes).stream()
				.sorted(Comparator.comparing(Object::toString))
				.toList())
		);

		return ExecutionPlanner.buildRecipeApplicationPathFromApplicationSet(
			applicationSet,
			startingResources,
			cancellationToken
		);
	}

	private CycleEliminationResult findRecipeApplicationPlanViaCycleEliminationInternal(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final ResourcePool target
	) {
		final ArrayDeque<Set<UUID>> attempts = new ArrayDeque<>();
		attempts.push(Set.of());

		final Set<List<UUID>> visited = new LinkedHashSet<>();
		visited.add(List.of());

		Set<UUID> bestFallbackDisabledRecipeIds = Set.of();
		int exploredBranches = 0;

		while (!attempts.isEmpty() && exploredBranches < options.maxCycleEliminationBranches()) {
			throwIfCancelled();
			final Set<UUID> disabledRecipeIds = attempts.pop();
			exploredBranches++;

			final Optional<LinearSolver.Result> solution = solveWithDisabledRecipes(
				recipes,
				startingResources,
				target,
				disabledRecipeIds
			);
			if (solution.isEmpty()) {
				bestFallbackDisabledRecipeIds = keepLargerSet(bestFallbackDisabledRecipeIds, disabledRecipeIds);
				continue;
			}

			final DeficitAnalysisResult noDeficits =
				new DeficitAnalysisResult(ResourcePool.empty(), Optional.of(solution.get()));
			final Optional<RecipeApplicationPath> candidatePath =
				buildRecipeApplicationPath(recipes, startingResources, noDeficits);
			if (candidatePath.isPresent()) {
				return new CycleEliminationResult(candidatePath, Set.of());
			}

			final List<ConcreteRecipe> usedRecipes = recipes.stream()
				.filter(recipe -> solution.get().recipeValues().getOrDefault(recipe.recipeId(), 0L) > 0)
				.toList();
			final RecipeAnalyzer.CycleDetectionResult cycleDetectionResult =
				RecipeAnalyzer.detectRecipeCycles(usedRecipes);
			if (cycleDetectionResult.cycles().isEmpty()) {
				bestFallbackDisabledRecipeIds = keepLargerSet(bestFallbackDisabledRecipeIds, disabledRecipeIds);
				continue;
			}

			enqueueCycleBreakAttempts(cycleDetectionResult.cycles(), solution.get(), disabledRecipeIds, visited, attempts);
		}

		return new CycleEliminationResult(Optional.empty(), Set.copyOf(bestFallbackDisabledRecipeIds));
	}

	private Optional<LinearSolver.Result> solveWithDisabledRecipes(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final ResourcePool target,
		final Set<UUID> disabledRecipeIds
	) {
		throwIfCancelled();
		final Set<ResourceKey> relevantResources = new LinkedHashSet<>(
			toConcreteResourceKeys(RecipeAnalyzer.collectRelevantResourceKeys(recipes))
		);
		relevantResources.addAll(toConcreteResourceKeys(target.resourceKeys()));

		final LinearSolver.Result result = new LinearSolver(
			recipes,
			relevantResources,
			startingResources,
			target,
			Set.of(),
			disabledRecipeIds,
			options,
			cancellationToken
		).lexicographicMinimum();
		return Optional.ofNullable(result);
	}

	private static ResourcePool computeUsedResources(final Map<ConcreteRecipe, Long> recipeValues) {
		final ResourcePool used = new ResourcePool();
		for (final Map.Entry<ConcreteRecipe, Long> entry : recipeValues.entrySet()) {
			final ConcreteRecipe recipe = entry.getKey();
			final long times = entry.getValue();
			for (final Map.Entry<Object, Long> input : recipe.input()) {
				if (input.getKey() instanceof ResourceKey resourceKey) {
					used.addAmount(resourceKey, input.getValue() * times);
				}
			}
		}
		return used;
	}

	private static int countResources(final ResourcePool pool) {
		int count = 0;
		for (final Map.Entry<Object, Long> entry : pool) {
			if (entry.getValue() > 0L) {
				count++;
			}
		}
		return count;
	}

	private static long totalAmount(final ResourcePool pool) {
		long total = 0L;
		for (final Map.Entry<Object, Long> entry : pool) {
			if (entry.getValue() > 0L) {
				total += entry.getValue();
			}
		}
		return total;
	}

	private static Set<ResourceKey> toConcreteResourceKeys(final Collection<Object> resources) {
		final Set<ResourceKey> result = new LinkedHashSet<>();
		for (final Object resource : resources) {
			if (resource instanceof ResourceKey resourceKey) {
				result.add(resourceKey);
			} else if (resource instanceof MultiResourceKey multiResourceKey) {
				result.addAll(multiResourceKey.members());
			}
		}
		return result;
	}

	private static void validateInputs(
		final List<ConcreteRecipe> recipes,
		final ResourcePool startingResources,
		final ResourcePool target
	) {
		Objects.requireNonNull(recipes, "recipes cannot be null");
		Objects.requireNonNull(startingResources, "startingResources cannot be null");
		Objects.requireNonNull(target, "target cannot be null");
	}

	private void throwIfCancelled() {
		if (cancellationToken.isCancelled()) {
			throw new CancellationException("LP solver cancelled");
		}
	}

	private static Set<UUID> keepLargerSet(final Set<UUID> currentBest, final Set<UUID> candidate) {
		if (candidate.size() > currentBest.size()) {
			return Set.copyOf(candidate);
		}
		return currentBest;
	}

	private static void enqueueCycleBreakAttempts(
		final List<List<UUID>> cycles,
		final LinearSolver.Result solution,
		final Set<UUID> disabledRecipeIds,
		final Set<List<UUID>> visited,
		final ArrayDeque<Set<UUID>> attempts
	) {
		for (final List<UUID> cycle : cycles) {
			final Optional<UUID> recipeToDisable = cycle.stream()
				.max(Comparator.comparingLong(id -> solution.recipeValues().getOrDefault(id, 0L)));
			if (recipeToDisable.isEmpty()) {
				continue;
			}
			addAttemptIfUnseen(disabledRecipeIds, recipeToDisable.get(), visited, attempts);
		}
	}

	private static void addAttemptIfUnseen(
		final Set<UUID> disabledRecipeIds,
		final UUID recipeIdToDisable,
		final Set<List<UUID>> visited,
		final ArrayDeque<Set<UUID>> attempts
	) {
		if (disabledRecipeIds.contains(recipeIdToDisable)) {
			return;
		}

		final Set<UUID> nextDisabledRecipeIds = new LinkedHashSet<>(disabledRecipeIds);
		nextDisabledRecipeIds.add(recipeIdToDisable);
		final List<UUID> key = nextDisabledRecipeIds.stream().sorted().toList();
		if (visited.add(key)) {
			attempts.push(Set.copyOf(nextDisabledRecipeIds));
		}
	}

	private record CycleEliminationResult(
		Optional<RecipeApplicationPath> recipeApplicationResult,
		Set<UUID> fallbackDisabledRecipeIds
	) {
		private CycleEliminationResult {
			Objects.requireNonNull(recipeApplicationResult, "recipeApplicationResult cannot be null");
			Objects.requireNonNull(fallbackDisabledRecipeIds, "fallbackDisabledRecipeIds cannot be null");
			fallbackDisabledRecipeIds = Set.copyOf(fallbackDisabledRecipeIds);
		}
	}

	private record DeficitAnalysisResult(ResourcePool requiredBaseItems, Optional<LinearSolver.Result> solution) {
		private DeficitAnalysisResult {
			Objects.requireNonNull(requiredBaseItems, "requiredBaseItems cannot be null");
			Objects.requireNonNull(solution, "solution cannot be null");
			requiredBaseItems = requiredBaseItems.copy();
		}
	}
}
