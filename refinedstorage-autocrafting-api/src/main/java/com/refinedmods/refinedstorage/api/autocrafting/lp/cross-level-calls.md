# Cross-Level LP Calls

Scope: this list follows [tree.md](tree.md) and only includes LP calls that skip at least one tree level or reach a class outside the tree. I did not count self-calls, private helpers, or JDK/library calls.

## CraftingOrchestrator
- [initialize()](CraftingOrchestrator.java#L108) reaches into [SanitizedRecipeAnalyzer.collectRelevantResourceKeys()](calculation/SanitizedRecipeAnalyzer.java#L105), which is not one of CraftingOrchestrator's direct children.
- [findMaxCraftableAmount()](CraftingOrchestrator.java#L300) constructs and uses [LinearSolver](calculation/LinearSolver.java#L25) directly instead of going through CraftingSolver.
- [validateOverflowInputs()](CraftingOrchestrator.java#L611) reads [LinearSolver.Options.defaults()](calculation/LinearSolver.java#L25) directly, again bypassing CraftingSolver.

## CraftingSolver
- [canCraftTarget()](calculation/CraftingSolver.java#L214) calls [SanitizedRecipeAnalyzer.collectRelevantResourceKeys()](calculation/SanitizedRecipeAnalyzer.java#L105).
- [computeRequiredBaseItemsAndSolution()](calculation/CraftingSolver.java#L245) calls [SanitizedRecipeAnalyzer.selectTopPriorityRecipesPerOutputResource()](calculation/SanitizedRecipeAnalyzer.java#L127), [collectLeafResources()](calculation/SanitizedRecipeAnalyzer.java#L21), [snipLoopsOnTargetBranches()](calculation/SanitizedRecipeAnalyzer.java#L39), and [collectLoopEntryDeficitResourcesOnTargetBranches()](calculation/SanitizedRecipeAnalyzer.java#L164).
- [analyzeDeficitResources()](calculation/CraftingSolver.java#L302) calls [SanitizedRecipeAnalyzer.collectRelevantResourceKeys()](calculation/SanitizedRecipeAnalyzer.java#L105) and [detectRecipeCycles()](calculation/SanitizedRecipeAnalyzer.java#L59).
- [solveWithDisabledRecipes()](calculation/CraftingSolver.java#L624) calls [SanitizedRecipeAnalyzer.collectRelevantResourceKeys()](calculation/SanitizedRecipeAnalyzer.java#L105).

## ExecutionPlanner
- [buildExecutableSteps()](calculation/ExecutionPlanner.java#L113) calls [SanitizedRecipeAnalyzer.detectRecipeCycles()](calculation/SanitizedRecipeAnalyzer.java#L59), which is outside ExecutionPlanner's own subtree.

## CyclicTaskImpl
- The constructor in [CyclicTaskImpl.java](output/CyclicTaskImpl.java#L68) calls back up to [TaskDispatcher.createCyclicTaskPlan()](output/TaskDispatcher.java#L168) and [TaskDispatcher.requirePattern()](output/TaskDispatcher.java#L279), both of which are above CyclicTaskImpl in the tree.
- [toMergedPlan()](output/CyclicTaskImpl.java#L461) also calls [TaskDispatcher.requirePattern()](output/TaskDispatcher.java#L279) and [TaskDispatcher.translateLpStepToTaskPlan()](output/TaskDispatcher.java#L126).
- The constructor also calls [TaskImpl.createTaskPatternInternal()](output/CyclicTaskImpl.java#L93), which is outside the tree entirely.

## No Cross-Level Calls Found
- [RecipeSanitizer](startup/RecipeSanitizer.java) stays within its own helper surface.
- [RecipeDesanitizer](output/RecipeDesanitizer.java) stays within its own helper surface.
- [PreviewCalculator](output/PreviewCalculator.java) only uses its own helpers once the path is desanitized.
- [TaskDispatcher](output/TaskDispatcher.java) stays inside its own dispatcher/helpers for the tree-relevant calls.
- [CycleSafeBudgetPlanner](output/CycleSafeBudgetPlanner.java) only consumes data from CyclicTaskImpl and local utilities.
