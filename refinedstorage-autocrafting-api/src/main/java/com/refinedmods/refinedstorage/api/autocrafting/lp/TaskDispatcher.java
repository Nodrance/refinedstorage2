package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatusBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.task.AbstractTaskPattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.PatternStepResult;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskSnapshot;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceListImpl;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TaskDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDispatcher.class);

    private TaskDispatcher() {
    }

    public static Optional<TaskId> addTask(final ResourceKey resource,
                                           final long amount,
                                           final Actor actor,
                                           final RecipeApplicationPath path,
                                           final Collection<Pattern> patterns,
                                           final boolean notify,
                                           final SingleStepTaskCreator singleStepTaskCreator,
                                           final PatternTaskSubmitter patternTaskSubmitter,
                                           final Predicate<Pattern> hasProvider) {
        final Map<UUID, Pattern> patternsById = indexPatterns(patterns);

        if (path.steps().size() == 1) {
            LOGGER.info("Translating single-step path to task plan for resource {} and amount {}", resource, amount);
            final TaskPlan singleStepPlan = translateLpStepToTaskPlan(
                resource,
                amount,
                path.steps().getFirst(),
                patternsById,
                true
            );
            return Optional.of(singleStepTaskCreator.create(actor, singleStepPlan, notify));
        }

        final Pattern rootPattern = findRootPattern(resource, path.steps(), patternsById);
        if (rootPattern == null) {
            return Optional.empty();
        }
        if (!hasProvider.test(rootPattern)) {
            return Optional.empty();
        }

        if (!path.hasCycles()) {
            LOGGER.info("Translating acyclic path to task plan for resource {} and amount {}", resource, amount);
            final TaskPlan plan = toAcyclicPlan(resource, amount, path.steps(), patternsById, rootPattern);
            return Optional.of(singleStepTaskCreator.create(actor, plan, notify));
        }
        
        final Task cyclicTask = new CyclicTaskImpl(
            resource,
            amount,
            actor,
            notify,
            path,
            patternsById,
            rootPattern,
            hasProvider
        );
            if (!patternTaskSubmitter.submit(rootPattern, cyclicTask)) {
            return Optional.empty();
        }
        LOGGER.info("Translating cyclic path to task plan for resource {} and amount {}", resource, amount);
        return Optional.of(cyclicTask.getId());
    }

    @FunctionalInterface
    public interface SingleStepTaskCreator {
        TaskId create(Actor actor, TaskPlan plan, boolean notify);
    }

    @FunctionalInterface
    public interface PatternTaskSubmitter {
        boolean submit(Pattern pattern, Task task);
    }

    private static Pattern findRootPattern(final ResourceKey resource,
                                           final List<RecipeApplicationStep> steps,
                                           final Map<UUID, Pattern> patternsById) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            final Pattern pattern = patternsById.get(steps.get(index).recipe().sourcePatternId());
            if (pattern == null) {
                continue;
            }
            final boolean producesResource = pattern.layout().outputs().stream()
                .anyMatch(output -> output.resource().equals(resource));
            if (producesResource) {
                return pattern;
            }
        }
        return null;
    }

    static TaskPlan translateLpStepToTaskPlan(final ResourceKey requestedResource,
                                             final long requestedAmount,
                                             final RecipeApplicationStep step,
                                             final Map<UUID, Pattern> patternsById,
                                             final boolean root) {
        final Pattern pattern = requirePattern(step.recipe(), patternsById);
        final long iterations = step.timesApplied();

        final Map<ResourceKey, Long> availablePerIteration = new LinkedHashMap<>();
        for (final var entry : step.recipe().input()) {
            availablePerIteration.put(RecipeDesanitizer.toSanitizedResourceKey(entry.getKey()), entry.getValue());
        }

        final Map<Integer, Map<ResourceKey, Long>> ingredients = new LinkedHashMap<>();
        final List<ResourceAmount> initialRequirements = new ArrayList<>();
        for (int ingredientIndex = 0; ingredientIndex < pattern.layout().ingredients().size(); ingredientIndex++) {
            final var ingredient = pattern.layout().ingredients().get(ingredientIndex);
            ResourceKey resource = ingredient.inputs().getFirst();
            for (final ResourceKey option : ingredient.inputs()) {
                final long available = availablePerIteration.getOrDefault(option, 0L);
                if (available >= ingredient.amount()) {
                    resource = option;
                    availablePerIteration.put(option, available - ingredient.amount());
                    break;
                }
            }
            final long totalAmount = ingredient.amount() * iterations;
            ingredients.put(ingredientIndex, Map.of(resource, totalAmount));
            initialRequirements.add(new ResourceAmount(resource, totalAmount));
        }

        final Map<Pattern, TaskPlan.PatternPlan> patterns = Map.of(
            pattern,
            new TaskPlan.PatternPlan(root, iterations, Map.copyOf(ingredients))
        );

        final ResourceKey outputResource = pattern.layout().outputs().isEmpty()
            ? requestedResource
            : pattern.layout().outputs().getFirst().resource();
        final long outputAmount = pattern.layout().outputs().isEmpty()
            ? iterations
            : pattern.layout().outputs().getFirst().amount() * iterations;
        final long taskAmount = requestedAmount > 0 ? requestedAmount : outputAmount;

        return new TaskPlan(
            outputResource,
            taskAmount,
            pattern,
            patterns,
            List.copyOf(initialRequirements)
        );
    }

    static TaskPlan createCyclicTaskPlan(final ResourceKey resource,
                                        final long amount,
                                        final Pattern rootPattern) {
        return new TaskPlan(
            resource,
            amount,
            rootPattern,
            Map.of(rootPattern, new TaskPlan.PatternPlan(true, 1, Map.of())),
            List.of()
        );
    }

    private static TaskPlan toAcyclicPlan(final ResourceKey requestedResource,
                                          final long requestedAmount,
                                          final List<RecipeApplicationStep> steps,
                                          final Map<UUID, Pattern> patternsById,
                                          final Pattern rootPattern) {
        final Map<Pattern, MutablePatternPlan> mutablePlans = new LinkedHashMap<>();

        for (final RecipeApplicationStep step : steps) {
            final Pattern stepPattern = requirePattern(step.recipe(), patternsById);
            final boolean root = stepPattern.equals(rootPattern);
            final TaskPlan stepPlan = translateLpStepToTaskPlan(requestedResource, -1, step, patternsById, root);
            final var entry = stepPlan.patterns().entrySet().iterator().next();

            final Pattern pattern = entry.getKey();
            final TaskPlan.PatternPlan patternPlan = entry.getValue();
            final MutablePatternPlan mutable = mutablePlans.computeIfAbsent(pattern, ignored ->
                new MutablePatternPlan(false, 0, new LinkedHashMap<>()));

            mutable.root = mutable.root || patternPlan.root();
            mutable.iterations += patternPlan.iterations();

            for (final var ingredientEntry : patternPlan.ingredients().entrySet()) {
                final int ingredientIndex = ingredientEntry.getKey();
                final Map<ResourceKey, Long> targetByResource = mutable.ingredients
                    .computeIfAbsent(ingredientIndex, ignored -> new LinkedHashMap<>());
                for (final var possibility : ingredientEntry.getValue().entrySet()) {
                    targetByResource.merge(possibility.getKey(), possibility.getValue(), Long::sum);
                }
            }
        }

        final Map<Pattern, TaskPlan.PatternPlan> patterns = new LinkedHashMap<>();
        final Map<ResourceKey, Long> totalInputs = new LinkedHashMap<>();
        final Map<ResourceKey, Long> totalProduced = new LinkedHashMap<>();

        for (final var entry : mutablePlans.entrySet()) {
            final Pattern pattern = entry.getKey();
            final MutablePatternPlan mutable = entry.getValue();
            final Map<Integer, Map<ResourceKey, Long>> immutableIngredients = new LinkedHashMap<>();

            for (final var ingredientEntry : mutable.ingredients.entrySet()) {
                immutableIngredients.put(ingredientEntry.getKey(), Map.copyOf(ingredientEntry.getValue()));
                ingredientEntry.getValue().forEach((resource, amount) ->
                    totalInputs.merge(resource, amount, Long::sum));
            }

            final long totalIterations = mutable.iterations;
            pattern.layout().outputs().forEach(output ->
                totalProduced.merge(output.resource(), output.amount() * totalIterations, Long::sum));
            pattern.layout().byproducts().forEach(byproduct ->
                totalProduced.merge(byproduct.resource(), byproduct.amount() * totalIterations, Long::sum));

            patterns.put(pattern, new TaskPlan.PatternPlan(
                mutable.root,
                totalIterations,
                Map.copyOf(immutableIngredients)
            ));
        }

        final List<ResourceAmount> initialRequirements = new ArrayList<>();
        totalInputs.forEach((resource, needed) -> {
            final long produced = totalProduced.getOrDefault(resource, 0L);
            final long requiredFromStorage = Math.max(0L, needed - produced);
            if (requiredFromStorage > 0L) {
                initialRequirements.add(new ResourceAmount(resource, requiredFromStorage));
            }
        });

        return new TaskPlan(
            requestedResource,
            requestedAmount,
            rootPattern,
            Map.copyOf(patterns),
            List.copyOf(initialRequirements)
        );
    }

    private static final class MutablePatternPlan {
        private boolean root;
        private long iterations;
        private final Map<Integer, Map<ResourceKey, Long>> ingredients;

        private MutablePatternPlan(final boolean root,
                                   final long iterations,
                                   final Map<Integer, Map<ResourceKey, Long>> ingredients) {
            this.root = root;
            this.iterations = iterations;
            this.ingredients = ingredients;
        }
    }

    private static Map<UUID, Pattern> indexPatterns(final Collection<Pattern> patterns) {
        final Map<UUID, Pattern> patternsById = new LinkedHashMap<>();
        for (final Pattern pattern : patterns) {
            patternsById.put(pattern.id(), pattern);
        }
        return patternsById;
    }

    static Pattern requirePattern(final SanitizedRecipe recipe, final Map<UUID, Pattern> patternsById) {
        final Pattern pattern = patternsById.get(recipe.sourcePatternId());
        if (pattern != null) {
            return pattern;
        }
        throw new IllegalStateException("Missing pattern for recipe " + recipe.recipeId());
    }
}
