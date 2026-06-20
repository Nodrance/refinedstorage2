# LP Review Findings

Scope: excludes the already-known low test coverage issue.

## Critical

### ExecutionPolisher.hasRecipeCycles always returns true (debug stub)
- Impacted behavior: every plan is treated as cyclic, forcing cyclic-specific handling even when no cycle exists.
- Why this is risky: changes execution path selection globally and can cause incorrect scheduling, extra overhead, and misleading diagnostics.
- Reproducible scenario outline:
  1. Build a plan with no recipe cycles.
  2. Evaluate cycle detection through ExecutionPolisher.
  3. Observe result is always true.
- Code references:
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/calculation/ExecutionPolisher.java:51
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/calculation/ExecutionPolisher.java:52

## High

### CyclicTaskImpl.stepPattern charges cycle budget on IDLE (no progress)
- Impacted behavior: cycle step budget is consumed even when a step makes no real progress.
- Why this is risky: can prematurely exhaust budget, fail valid execution paths, or report stalled progress as consumed work.
- Reproducible scenario outline:
  1. Trigger a cyclic task where stepPattern returns IDLE.
  2. Observe executedSteps increments before result handling.
  3. Confirm budget is charged despite no effective execution.
- Code references:
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/task/CyclicTaskImpl.java:343
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/task/CyclicTaskImpl.java:344
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/task/CyclicTaskImpl.java:280
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/task/CyclicTaskImpl.java:442

### RecipeDesanitizer.decodePlanStepsToDesanitized is O(timesApplied)
- Impacted behavior: desanitization loops once per timesApplied, so large LP counts create very large iteration counts.
- Why this is risky: high-count plans can degrade severely or hang, especially in preview/plan conversion.
- Reproducible scenario outline:
  1. Produce a plan step with very large timesApplied.
  2. Run decodePlanStepsToDesanitized.
  3. Observe runtime growth proportional to timesApplied and potential stall.
- Code references:
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/desanitization/RecipeDesanitizer.java:205
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/desanitization/RecipeDesanitizer.java:224

### TaskDispatcher may reject valid plans due to single root candidate selection
- Impacted behavior: if the selected root lacks a provider, dispatch can fail even when another valid producer root exists.
- Why this is risky: false negatives in task creation and avoidable crafting failures.
- Reproducible scenario outline:
  1. Create a case with multiple possible root producers for target output.
  2. Ensure first chosen root has no provider but another candidate does.
  3. Dispatch task and observe rejection instead of fallback to alternate root.
- Code references:
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/task/TaskDispatcher.java:71
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/task/TaskDispatcher.java:75
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/task/TaskDispatcher.java:112
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/task/TaskDispatcher.java:115

## Medium

### RecipeSanitizer fuzzy allocation generation is combinatorial and unbounded
- Impacted behavior: fuzzy allocation expansion uses stars-and-bars cross-product growth without hard bounds.
- Why this is risky: large fuzzy counts/options can explode memory and CPU, causing timeouts or OOM.
- Reproducible scenario outline:
  1. Define fuzzy inputs with high item count and many interchangeable options.
  2. Run sanitization.
  3. Observe rapid growth in generated allocation combinations and degraded performance.
- Code references:
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/sanitization/RecipeSanitizer.java:399
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/sanitization/RecipeSanitizer.java:458
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/sanitization/RecipeSanitizer.java:473
  - refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/sanitization/RecipeSanitizer.java:488
