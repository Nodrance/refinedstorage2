# LP Type Inventory

Scope: everything used as a type in the LP package Java sources, including LP-local types, external API types, Java/3rd-party types, and commonly used composed generic type expressions.

## LP-local types

- LpCraftingSolver
	- Files: LpCraftingSolver.java, LpPreviewCalculator.java, LpStepPlanCalculator.java
	- Use: Main LP orchestration (solve, deficits, cycle elimination).

- LpCraftingSolution
	- Files: LpCraftingSolution.java, LpCraftingSolver.java
	- Use: Recipe usage + final inventory result payload.

- LpDispatcherHelper
	- Files: LpDispatcherHelper.java, LpTaskDispatcher.java
	- Use: Converts LP step plans to TaskPlan forms.

- LpExecutionPlanner
	- Files: LpCraftingSolver.java, LpExecutionPlanner.java
	- Use: Converts recipe usage counts into executable order.

- LpExecutionPlanStep
	- Files: LpCraftingSolver.java, LpDispatcherHelper.java, LpExecutionPlanner.java, LpExecutionPlanStep.java, LpFuzzyExpander.java, LpPreviewCalculator.java, LpStepPlan.java, LpStepPlanCalculator.java, LpTaskDispatcher.java
	- Use: One recipe application step with iteration count.

- LpFuzzyExpander
	- Files: LpFuzzyExpander.java, LpPreviewCalculator.java, LpStepPlanCalculator.java
	- Use: Fuzzy recipe expansion and decode back to concrete resources.

- LpPatternRecipe
	- Files: LpCraftingSolution.java, LpCraftingSolver.java, LpExecutionPlanner.java, LpExecutionPlanStep.java, LpFuzzyExpander.java, LpPatternRecipe.java, LpRecipeAnalysis.java, LpRecipePriorityKey.java, LpStepPlanCalculator.java
	- Use: LP recipe representation (input/output/priority).

- LpPlanningHelper
	- Files: LpPlanningHelper.java
	- Use: LP relevance collection and gate helper.

- LpPreviewCalculator
	- Files: LpPreviewCalculator.java
	- Use: Top-level LP preview assembly.

- LpRecipeAnalysis
	- Files: LpCraftingSolver.java, LpExecutionPlanner.java, LpRecipeAnalysis.java
	- Use: Pruning, prioritization, cycle and deficit analysis.

- LpRecipePriorityKey
	- Files: LpRecipeAnalysis.java, LpRecipePriorityKey.java
	- Use: Lexicographic priority key for inherited recipe ordering.

- LpResourceSet
	- Files: LpCraftingSolution.java, LpCraftingSolver.java, LpExecutionPlanner.java, LpFuzzyExpander.java, LpPatternRecipe.java, LpPreviewCalculator.java, LpRecipeAnalysis.java, LpResourceSet.java, LpResourceSubset.java, LpStepPlanCalculator.java
	- Use: Mutable resource->amount map (supports negatives).

- LpResourceSubset
	- Files: LpFuzzyExpander.java, LpPreviewCalculator.java, LpResourceSubset.java
	- Use: Virtual ResourceKey grouping interchangeable resources.

- LpSolverOptions
	- Files: LpCraftingSolver.java, LpSolverOptions.java
	- Use: LP solve bounds and cycle-elimination branch limits.

- LpStepPlan
	- Files: LpStepPlan.java, LpStepPlanCalculator.java, LpTaskDispatcher.java
	- Use: Execution steps + cycle flag for dispatch mode.

- LpStepPlanCalculator
	- Files: LpStepPlanCalculator.java
	- Use: Top-level LP step plan / max amount utilities.

- LpTaskDispatcher
	- Files: LpTaskDispatcher.java
	- Use: TaskImpl subclass that dispatches LP step plans.

### LP nested/local helper record/class types

- LpCraftingSolver.RecipeApplicationPlanResult: solution + plan + required base items.
- LpCraftingSolver.DeficitAnalysisResult: private deficit computation payload.
- LpCraftingSolver.CycleEliminationResult: optional plan + disabled recipes fallback.
- LpCraftingSolver.PlanningOutcome: max amount + optional plan + deficits + fallback IDs.
- LpCraftingSolver.FlowSearchModel: private LP model builder/solver wrapper.
- LpCraftingSolver.FlowSearchResult: private recipeValues/finalInventory pair.
- LpExecutionPlanner.Candidate: private search candidate for backsolve recursion.
- LpFuzzyExpander.IngredientPartition: amount + viable subsets for one ingredient.
- LpFuzzyExpander.IngredientGroup: merged partitions by identical subset choices.
- LpFuzzyExpander.FuzzyExpansionResult: expanded recipes + variant mapping + created subsets.
- LpDispatcherHelper.PatternPlanAccumulator: internal builder for TaskPlan.PatternPlan.
- LpRecipeAnalysis.ResourcePriorityEntry, TraversalState, RecipePriorityEntry: priority-propagation internals.
- LpRecipeAnalysis.CycleDetectionResult: loop membership + explicit cycle list.
- LpTaskDispatcher.DispatchedSubTask, SeededSubTask: active subtask tracking/seeding payloads.

## External Refined Storage API types

- com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken
	- Files: LpCraftingSolver.java, LpExecutionPlanner.java, LpPreviewCalculator.java, LpStepPlanCalculator.java
	- Use: Cooperative cancellation/time-budget checks.

- com.refinedmods.refinedstorage.api.autocrafting.Ingredient
	- Files: LpFuzzyExpander.java, LpPatternRecipe.java
	- Use: Pattern ingredient slots and options.

- com.refinedmods.refinedstorage.api.autocrafting.Pattern
	- Files: LpDispatcherHelper.java, LpFuzzyExpander.java, LpPatternRecipe.java, LpPlanningHelper.java, LpPreviewCalculator.java, LpStepPlanCalculator.java, LpTaskDispatcher.java
	- Use: Core crafting pattern entity.

- com.refinedmods.refinedstorage.api.autocrafting.PatternLayout
	- Files: LpFuzzyExpander.java
	- Use: Synthetic layout construction for fuzzy variants.

- com.refinedmods.refinedstorage.api.autocrafting.PatternRepository
	- Files: LpPlanningHelper.java
	- Use: Lookup of producer patterns per resource.

- com.refinedmods.refinedstorage.api.autocrafting.preview.Preview
	- Files: LpPreviewCalculator.java
	- Use: Final preview output object.

- com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewBuilder
	- Files: LpPreviewCalculator.java
	- Use: Incremental preview aggregation.

- com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewItem
	- Files: LpPreviewCalculator.java
	- Use: Per-resource preview rows; sorted by dependency.

- com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType
	- Files: LpPreviewCalculator.java
	- Use: Preview state enum (success/missing/not available/cancelled).

- com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus
	- Files: LpTaskDispatcher.java
	- Use: Runtime task status snapshots.

- com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatusBuilder
	- Files: LpTaskDispatcher.java
	- Use: Builds status payloads with progress/scheduling.

- com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider
	- Files: LpTaskDispatcher.java
	- Use: Sink/provider bridge for stepping subtasks.

- com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior
	- Files: LpTaskDispatcher.java
	- Use: Step execution behavior flags.

- com.refinedmods.refinedstorage.api.autocrafting.task.TaskId
	- Files: LpTaskDispatcher.java
	- Use: Active subtask identity keys.

- com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl
	- Files: LpTaskDispatcher.java
	- Use: Base task implementation and spawned subtasks.

- com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener
	- Files: LpTaskDispatcher.java
	- Use: Callback target while stepping tasks.

- com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan
	- Files: LpDispatcherHelper.java, LpTaskDispatcher.java
	- Use: Dispatchable plan representation (including TaskPlan.PatternPlan).

- com.refinedmods.refinedstorage.api.autocrafting.task.TaskSnapshot
	- Files: LpTaskDispatcher.java
	- Use: Rehydration/serialization-like snapshot state.

- com.refinedmods.refinedstorage.api.autocrafting.task.TaskState
	- Files: LpTaskDispatcher.java
	- Use: State machine for parent/subtasks.

- com.refinedmods.refinedstorage.api.resource.ResourceAmount
	- Files: LpDispatcherHelper.java, LpFuzzyExpander.java, LpPatternRecipe.java, LpPreviewCalculator.java, LpResourceSet.java, LpTaskDispatcher.java
	- Use: Resource+amount pair for APIs/lists.

- com.refinedmods.refinedstorage.api.resource.ResourceKey
	- Files: LpCraftingSolution.java, LpCraftingSolver.java, LpDispatcherHelper.java, LpExecutionPlanner.java, LpFuzzyExpander.java, LpPatternRecipe.java, LpPlanningHelper.java, LpPreviewCalculator.java, LpRecipeAnalysis.java, LpResourceSet.java, LpResourceSubset.java, LpStepPlanCalculator.java, LpTaskDispatcher.java
	- Use: Resource identity key used everywhere in LP logic.

- com.refinedmods.refinedstorage.api.resource.list.MutableResourceList
	- Files: LpTaskDispatcher.java
	- Use: Buffered/internal storage accounting.

- com.refinedmods.refinedstorage.api.resource.list.MutableResourceListImpl
	- Files: LpTaskDispatcher.java
	- Use: MutableResourceList factory/concrete impl.

- com.refinedmods.refinedstorage.api.storage.Actor
	- Files: LpTaskDispatcher.java
	- Use: Actor attached to dispatched task.

- com.refinedmods.refinedstorage.api.storage.root.RootStorage
	- Files: LpPlanningHelper.java, LpPreviewCalculator.java, LpStepPlanCalculator.java, LpTaskDispatcher.java
	- Use: Current storage view for planning/dispatch decisions.

- com.refinedmods.refinedstorage.api.core.FieldsAndMethodsAreNonnullByDefault
	- Files: package-info.java
	- Use: Package-level nullness default annotation.

## Java/3rd-party library types

- java.util: ArrayDeque, ArrayList, Collection, Collections, Comparator, HashMap, HashSet, LinkedHashMap, LinkedHashSet, List, Map, Optional, Set, UUID, Objects, StringJoiner
	- Files: used across most LP files (see import map above)
	- Use: collections, ordering, optional returns, IDs, validation, and string formatting.

- java.util.concurrent: CancellationException, ExecutionException, FutureTask, TimeUnit, TimeoutException
	- Files: mostly LpCraftingSolver.java; CancellationException also in LpPreviewCalculator.java and LpStepPlanCalculator.java
	- Use: cancellation flow and helper-thread LP solve orchestration.

- java.util.function.Predicate
	- Files: LpTaskDispatcher.java
	- Use: provider availability predicate for dispatch.

- java.io.IOException, java.nio.file.Files, java.nio.file.Path, java.nio.charset.StandardCharsets
	- Files: LpCraftingSolver.java
	- Use: thread-dump file persistence.

- java.lang.management.ManagementFactory, java.lang.management.ThreadInfo
	- Files: LpCraftingSolver.java
	- Use: diagnostics thread dumps on timeout.

- java.time.Instant, java.time.format.DateTimeFormatter
	- Files: LpCraftingSolver.java
	- Use: timestamped diagnostics filenames.

- javax.annotation.ParametersAreNonnullByDefault
	- Files: package-info.java
	- Use: package-level parameter nullness default.

- org.ojalgo.optimisation.Expression, ExpressionsBasedModel, Optimisation, Variable
	- Files: LpCraftingSolver.java
	- Use: LP model/variable/constraint/objective types.

- org.slf4j.Logger, org.slf4j.LoggerFactory
	- Files: LpCraftingSolver.java, LpPreviewCalculator.java, LpStepPlanCalculator.java, LpTaskDispatcher.java
	- Use: structured logging.

## Implicit java.lang and primitive types used as types

- String, Object, ClassLoader, Thread, Throwable, StringBuilder
	- Files: primarily LpCraftingSolver.java; also Object/String in equals/toString overrides in LP model types.
	- Use: diagnostics strings, classloader/thread management, exception wrapping, and object contract methods.

- IllegalArgumentException, IllegalStateException
	- Files: LpCraftingSolver.java, LpExecutionPlanner.java, LpExecutionPlanStep.java, LpPatternRecipe.java, LpResourceSubset.java, LpSolverOptions.java
	- Use: validation and invariant failure signaling.

- Primitive types: boolean, int, long, double
	- Files: all LP .java files
	- Use: flags, loop indexes, counts/amounts, and progress math.

## Composed generic type expressions used in code

These are the main parameterized types that appear explicitly (implicitly inferred vars end up in these same shapes):

- Map<ResourceKey, Long>
	- Files: LpResourceSet.java, LpResourceSubset.java, LpPatternRecipe.java, LpDispatcherHelper.java, LpFuzzyExpander.java, LpPreviewCalculator.java, LpTaskDispatcher.java
	- Use: inventory/resource counts and per-step requirements.

- Map<UUID, Long>
	- Files: LpCraftingSolution.java, LpExecutionPlanner.java, LpCraftingSolver.java
	- Use: recipe usage counts keyed by recipe UUID.

- Map<UUID, Boolean>
	- Files: LpRecipeAnalysis.java, LpExecutionPlanner.java
	- Use: recipe loop-membership flags.

- Map<UUID, Integer>
	- Files: LpRecipeAnalysis.java
	- Use: computed effective priorities.

- Map<UUID, Variable>
	- Files: LpCraftingSolver.java
	- Use: LP decision variable lookup by recipe ID.

- Map<UUID, Pattern>
	- Files: LpFuzzyExpander.java, LpPreviewCalculator.java, LpStepPlanCalculator.java
	- Use: pattern lookup tables during fuzzy decode.

- Map<UUID, UUID>
	- Files: LpFuzzyExpander.java, LpPreviewCalculator.java, LpStepPlanCalculator.java
	- Use: fuzzy variant pattern -> source pattern mapping.

- Map<ResourceKey, Set<String>> and Map<Set<String>, List<ResourceKey>>
	- Files: LpFuzzyExpander.java
	- Use: fuzzy-slot participation signatures and subset grouping.

- Map<ResourceKey, List<LpPatternRecipe>>
	- Files: LpRecipeAnalysis.java
	- Use: reverse index of output resource -> producing recipes.

- Map<Pattern, Set<ResourceKey>> and Map<Pattern, Set<Pattern>>
	- Files: LpStepPlanCalculator.java
	- Use: produced/consumed resources and dependency graph for cycle checks.

- Map<TaskId, DispatchedSubTask>
	- Files: LpTaskDispatcher.java
	- Use: active dispatched subtask tracking.

- Optional<List<LpExecutionPlanStep>>
	- Files: LpExecutionPlanner.java, LpCraftingSolver.java, LpStepPlanCalculator.java
	- Use: executable plan result that may fail.

- Optional<LpCraftingSolution>, Optional<LpStepPlan>, Optional<TaskPlan>, Optional<TaskId>, Optional<RecipeApplicationPlanResult>
	- Files: LpCraftingSolver.java, LpStepPlanCalculator.java, LpDispatcherHelper.java, LpTaskDispatcher.java
	- Use: optional solve/plan/dispatch outcomes.

- List<LpPatternRecipe>, List<LpExecutionPlanStep>, List<List<LpPatternRecipe>>, List<List<Integer>>
	- Files: LpCraftingSolver.java, LpExecutionPlanner.java, LpFuzzyExpander.java, LpRecipeAnalysis.java, LpStepPlanCalculator.java
	- Use: recipe collections, plans, and cycle representations.

- Set<ResourceKey>, Set<UUID>, Set<List<UUID>>, Set<List<Integer>>, Set<LpResourceSubset>
	- Files: LpCraftingSolver.java, LpFuzzyExpander.java, LpPlanningHelper.java, LpPreviewCalculator.java, LpRecipeAnalysis.java, LpStepPlanCalculator.java
	- Use: relevant resources, disabled IDs, visited state, cycle canonicalization, and fuzzy subset registry.

- Iterable<Map.Entry<ResourceKey, Long>> and Iterator<Map.Entry<ResourceKey, Long>>
	- Files: LpResourceSet.java
	- Use: iteration over resource set entries.

- TaskPlan.PatternPlan and Map<Pattern, TaskPlan.PatternPlan>
	- Files: LpDispatcherHelper.java, LpTaskDispatcher.java
	- Use: per-pattern plan structure for dispatchable task plans.
