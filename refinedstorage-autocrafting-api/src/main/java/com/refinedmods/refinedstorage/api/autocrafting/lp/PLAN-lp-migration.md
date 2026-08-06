# Plan: LP Autocrafting Rewrite Migration

Migrate the autocrafting LP solver from `old_lp/` to the new `lp/` package described in
[map.md](map.md). The defining change is splitting the solver's single mixed result
(`CraftingApplication` / `CraftingSolution`, which carries both "what to craft" and "what's
missing") into two first-class products: a `SolvedCraftingProblem` (executable) and an
`UnsolvableCraftingProblem` (missing-items report). Approach is a **clean-room rewrite** driven by
the design map, with **minimal copying** — *except* for the handful of parity-critical algorithms
and pure data records, which are copied/renamed verbatim to keep the `Ported*` parity tests green
(see Decisions).

This document classifies every old_lp class as **Lift** (copy near-verbatim), **Port** (reimplement
faithfully, behavior-preserving, reference the original closely), or **Rewrite** (net-new shape and
flow), and sequences the work into phases.

## Lift / Port / Rewrite classification

Clean-room intent means most logic is rebuilt from the map. The exceptions below are deliberately
**Lifted** because reimplementing them from scratch is the single biggest risk to the hard parity
requirement — their behavior is defined by subtle, test-pinned quirks, not by clean specs.

**Lift (copy, rename only) — parity-critical or pure data**
- `sanitization/MultiResourceKey` → `lp/sanitization/SanitizedResourceKey`
- `sanitization/ResourcePool` → `lp/sanitization/SanitizedResourcePool`
- `sanitization/SanitizedRecipe` → `lp/sanitization/SanitizedRecipe`
- `sanitization/SanitizedRecipeResourceAnalyzer` (leaf/relevant-resource collection)
- `sanitization/SanitizedRecipePriorityAnalyzer` → feeds `lp/sanitization/Prioritizer` (parity-critical back-propagation)
- `calculation/SanitizedRecipeCycleAnalyzer` → feeds `lp/sanitization/CycleAnalyzer` (pure graph, also used by deficit second pass)
- `calculation/CraftingStep`, `calculation/CraftingProblem` (pure data; CraftingProblem becomes `lp/solver/CraftingProblem`)
- `desanitization/DesanitizedRecipe`, `DesanitizedRecipeApplicationStep` (pure data)
- `task/CycleSafeBudgetPlanner` (pure cycle-safety graph algorithm; no result-shape coupling)
- LP math kernels inside `calculation/LinearSolver` (constraint coefficients, lexicographic locking order, deficit-floor objective) → into `lp/solver/LpEngine`

**Port (reimplement faithfully against the new split, keep behavior)**
- `sanitization/RecipeSanitizer` (507 lines) → split across `Sanitizer`, `RelevancyFilter`,
  `IngredientAnalyzer`, `Defuzzer`. The MRK-index computation and stars-and-bars fuzzy expansion are
  parity-critical and should be lifted near-verbatim even though the surrounding glue is rebuilt.
- `desanitization/RecipeDesanitizer` (≈330 lines) → split into `SolveDesanitizer` (solved path) and
  `ImpossibleDesanitizer` (unsolved path). The greedy MRK→ResourceKey allocation (member-order, with
  the parity quirk of effectively taking the last member) is parity-critical and copied; the
  combined-extraction wrapper is the part that splits.
- `preview/PreviewCalculator` (≈700 lines) → `FlatPreviewBuilder` (from `SolvedCraftingProblem`),
  an uncraftable preview path (from `UnsolvableCraftingProblem`), and `TreePreviewBuilder`. Helpers
  (`computeCraftedAmounts`, `orderResourcesDesanitized`, byproduct filtering, reverse tree walk) lift
  cleanly; only the `missing.isEmpty()` branch decision is replaced by the type split.
- `task/TaskDispatcher` (≈400 lines) and `task/CyclicTaskImpl` (≈500 lines) → near-verbatim; they
  already operate only on a feasible/desanitized path. Main change is accepting the solved-path type
  instead of the combined `DesanitizedRecipeApplicationPath`. Note these have **no stubs yet** in
  `lp/task/` (only `CyclicTaskImpl` exists) — `TaskDispatcher` and `CycleSafeBudgetPlanner` need to be
  added.
- `calculation/LinearSolver` shell (≈620 lines) → `lp/solver/LpEngine`. Kernels lifted; the
  per-solve threading (`OjalgoSolveRunner`) is reworked per the existing
  [old_lp/calculation/PLAN-linear-solver-ownership-timeouts.md](../old_lp/calculation/PLAN-linear-solver-ownership-timeouts.md)
  (centralized solve session, independent loop-snipping budget).
- `calculation/ExecutionPlanner` (≈400 lines) and `calculation/ExecutionPolisher` (≈200 lines) →
  the backsolving recursion, step merging, and peak simulation are reusable *algorithms*, but their
  contracts change completely. The backsolve/ordering logic feeds `PotentialSolutionVerifier`; the
  peak-usage simulation becomes `MaxUseAnalyzer`.

**Rewrite (net-new shape and flow)**
- `calculation/CraftingApplication` → **delete**; split into `lp/solver/PotentialSolution` (recipe
  values, may borrow from the future), `lp/solver/solution/SolvedCraftingProblem`, and
  `lp/solver/defecit/UnsolvableCraftingProblem`.
- `calculation/CraftingSolution` → reshaped into `SolvedCraftingProblem` (no `missingResources`).
- `calculation/CraftingSolver` (≈800 lines) → `lp/solver/Solver`. The branch-exploration /
  cycle-elimination loop is transplantable, but the top-level flow is reorganized into the map's
  pipeline: `PotentialSolvabilityChecker` → `PotentialSolutionGenerator` → `PotentialSolutionVerifier`
  → (solved) `SolvedCraftingProblem` *or* (failed) `DefecitAnalyzer` → `DefecitOptimizer` →
  `UnsolvableCraftingProblem`. The deficit-fallback decision logic and output semantics are net-new.
- `calculation/ExecutionPlanner`'s "borrow-from-the-future" verification → `PotentialSolutionVerifier`.
  The map explicitly says this is **largely rewritten with a completely different output format**;
  treat it as the highest-risk rewrite.
- `sanitization/CraftingInitializer` (≈208 lines) → folded into the new `Sanitizer` entrypoint;
  pure orchestration glue, rebuilt (overflow validation logic is small enough to port).
- `CraftingOrchestrator` (≈600 lines) → `lp/Orchestrator`; entry-point signatures preserved for
  callsites, but `solve()` now branches solved vs unsolved instead of producing one path.
- New solver intermediates with no old equivalent: `lp/solver/UnsolvedCraftingProblem`,
  `lp/solver/defecit/DefecitAnalyzer`, `lp/solver/defecit/DefecitOptimizer`,
  `lp/solver/solution/MaxUseAnalyzer`.

## Steps

### Phase 0 — Foundation: data & parity-critical lifts
1. Copy + rename the pure data/util types into `lp/sanitization/` and `lp/solver/`: `SanitizedResourceKey`
   (was MultiResourceKey), `SanitizedResourcePool` (was ResourcePool), `SanitizedRecipe`, `CraftingStep`,
   `CraftingProblem`, `DesanitizedRecipe`, `DesanitizedRecipeApplicationStep`. Fill the existing empty
   stubs rather than creating new files where stubs exist.
2. Lift `SanitizedRecipeResourceAnalyzer`, `SanitizedRecipePriorityAnalyzer`, and
   `SanitizedRecipeCycleAnalyzer` as the engines behind `Prioritizer` and `CycleAnalyzer`, keeping
   their algorithms byte-for-byte where practical. *Parallel with step 1.*
3. Lift `CycleSafeBudgetPlanner` into `lp/task/` unchanged. *Parallel with steps 1–2.*

### Phase 1 — Sanitization pipeline
4. Implement `RelevancyFilter` from `RecipeSanitizer.collectRelevantPatterns` (BFS over pattern I/O).
5. Implement `IngredientAnalyzer` from `RecipeSanitizer.computeMultiResourceKeyIndex` (MRK merge by
   participation signature) — lift near-verbatim (parity-critical). *Depends on step 1.*
6. Implement `Defuzzer` from `RecipeSanitizer.trimFuzzyPatterns` + `generateAllocations` +
   `toSanitizedRecipes` (stars-and-bars expansion, deterministic variant IDs) — parity-critical. *Depends on step 5.*
7. Implement `CycleAnalyzer` over sanitized recipes (detection + loop-entry deficit resources). *Depends on step 2.*
8. Implement `Sanitizer` as the entrypoint that wires filter → analyze → defuzz → cycle → prioritize
   and builds the `CraftingProblem`, porting `CraftingInitializer`'s overflow validation. *Depends on steps 4–7.*

### Phase 2 — Solver core (LP engine)
9. Implement `LpEngine` from `LinearSolver`: lift the kernels (resource-balance constraints, recipe
   variables, lexicographic minimization, `maximize`, `minimizeTotalDeficitWithFloor`), and adopt the
   centralized solve-session/timeout model from the linear-solver-ownership PLAN. *Depends on Phase 0.*
10. Implement `PotentialSolvabilityChecker` from `LinearSolver.hasFeasibleSolution`/`maximize` and
    `CraftingSolver.canCraftWithoutMissingResources` — the early solved/unsolved branch gate. *Depends on step 9.*
11. Implement `PotentialSolutionGenerator` + `PotentialSolution` from `CraftingSolver`'s
    cycle-elimination branch exploration plus `LpEngine` lexicographic solve. Output is recipe
    values only (no ordering, may borrow from the future). *Depends on steps 9–10.*

### Phase 3 — Verification, solved output, peak usage (highest risk)
12. Rewrite `PotentialSolutionVerifier` (new output format) using the backsolving/ordering algorithm
    transplanted from `ExecutionPlanner` + `ExecutionPolisher` reordering. On success it yields an
    ordering + cycle metadata; on failure it hands off to the deficit branch. *Depends on Phase 2.*
13. Build `SolvedCraftingProblem` (steps, times applied, critical-item ordering, cycle flags) from a
    verified solution. *Depends on step 12.*
14. Implement `MaxUseAnalyzer` from `ExecutionPolisher.computePeakResourceUsage`, simulating only the
    cyclic portions to derive minimum peak/initial grab amounts. *Depends on step 13.*

### Phase 4 — Unsolvable branch
15. Implement `DefecitAnalyzer` (two-pass: leaf resources, then snip-all-loops) from
    `CraftingSolver.computeRequiredBaseItems` + `CycleAnalyzer` loop snipping. *Depends on Phase 2 + step 7.*
16. Implement `DefecitOptimizer` from `LinearSolver.minimizeTotalDeficitWithFloor` (no-worse-per-resource
    floor). *Depends on step 15 + step 9.*
17. Build `UnsolvableCraftingProblem` as the missing-items report. *Depends on steps 15–16.*
18. Implement `Solver` to drive the full pipeline and return solved-or-unsolvable. *Depends on Phases 2–4.*

### Phase 5 — Desanitization split
19. Implement `SolveDesanitizer` (solved path: MRK→ResourceKey, executable step plan, cycle ordering)
    by lifting `RecipeDesanitizer`'s allocation + step-batching. *Depends on Phase 3.*
20. Implement `ImpossibleDesanitizer` (unsolved path: map used items, then deficits, for preview). *Depends on Phase 4.*

### Phase 6 — Previews
21. Implement `FlatPreviewBuilder` from `SolvedCraftingProblem` (always SUCCESS), lifting
    `PreviewCalculator`'s amount/order/filter helpers. *Depends on step 19.*
22. Implement the uncraftable flat preview from `UnsolvableCraftingProblem` (MISSING_RESOURCES). *Depends on step 20.*
23. Implement `TreePreviewBuilder` from the reverse-step tree walk, plus the NOT_AVAILABLE fallback. *Depends on step 19.*

### Phase 7 — Task execution
24. Add `TaskDispatcher` and finish `CyclicTaskImpl` in `lp/task/`, ported near-verbatim to accept the
    solved-path type. Reuse the `CycleSafeBudgetPlanner` from Phase 0. *Depends on Phase 5 + step 3.*

### Phase 8 — Orchestrator wiring, callsite swap, parity
25. Implement `Orchestrator` exposing the preserved entry points (`solve`, `solveToStepPlan`,
    `solveAndCalculatePreview`, `solveAndCalculateTreePreview`, `findMaxCraftableAmount`), branching
    solved vs unsolved internally. *Depends on Phases 5–7.*
26. Repoint `AutocraftingNetworkComponentImpl` (and any other callers) from `old_lp.CraftingOrchestrator`
    to `lp.Orchestrator`. *Depends on step 25.*
27. Re-target the `Ported*` parity tests at the new package and get them green; then delete `old_lp/`. *Depends on step 26.*

## Relevant files

**New package (targets — most are empty stubs today)**
- `lp/Orchestrator.java` — entry points; from `old_lp/CraftingOrchestrator`.
- `lp/sanitization/{Sanitizer,RelevancyFilter,IngredientAnalyzer,Defuzzer,CycleAnalyzer,Prioritizer}.java`
- `lp/sanitization/{SanitizedRecipe,SanitizedResourceKey,SanitizedResourcePool}.java` — data lifts.
- `lp/solver/{Solver,CraftingProblem,PotentialSolvabilityChecker,PotentialSolutionGenerator,PotentialSolution,PotentialSolutionVerifier,LpEngine,UnsolvedCraftingProblem}.java`
- `lp/solver/solution/{SolvedCraftingProblem,MaxUseAnalyzer}.java`
- `lp/solver/defecit/{DefecitAnalyzer,DefecitOptimizer,UnsolvableCraftingProblem}.java`
- `lp/desanitization/{SolveDesanitizer,ImpossibleDesanitizer}.java`
- `lp/preview/{FlatPreviewBuilder,TreePreviewBuilder}.java`
- `lp/task/CyclicTaskImpl.java` (+ new `TaskDispatcher.java`, `CycleSafeBudgetPlanner.java`)

**Old package (reference / source of lifts)**
- `old_lp/sanitization/RecipeSanitizer.java` — MRK index + fuzzy expansion (parity-critical lifts).
- `old_lp/sanitization/{SanitizedRecipePriorityAnalyzer,SanitizedRecipeResourceAnalyzer,CraftingInitializer,MultiResourceKey,ResourcePool,SanitizedRecipe}.java`
- `old_lp/calculation/{CraftingSolver,LinearSolver,OjalgoSolveRunner,ExecutionPlanner,ExecutionPolisher,SanitizedRecipeCycleAnalyzer,CraftingApplication,CraftingSolution,CraftingProblem,CraftingStep}.java`
- `old_lp/calculation/PLAN-linear-solver-ownership-timeouts.md` — adopt for `LpEngine` threading.
- `old_lp/desanitization/{RecipeDesanitizer,DesanitizedRecipe,DesanitizedRecipeApplicationPath,DesanitizedRecipeApplicationStep}.java`
- `old_lp/preview/PreviewCalculator.java`, `old_lp/task/{TaskDispatcher,CyclicTaskImpl,CycleSafeBudgetPlanner}.java`

**Callsites**
- `refinedstorage-network/.../autocrafting/AutocraftingNetworkComponentImpl.java` — the only production
  caller (preview, tree preview, findMaxCraftableAmount, solveToStepPlan).
- `refinedstorage-autocrafting-api/src/test/.../old_lp/Ported*.java` — parity tests to re-target.

## Verification
1. After each phase, run the autocrafting-api module tests (Gradle) and fix regressions before moving on.
2. Keep `PortedCraftabilityTest`, `PortedPreviewTest`, `PortedTreePreviewTest`, `PortedTaskPlanTest`,
   `PortedTaskImplTest` green against the new package — these encode the hard parity requirement.
3. Add focused unit tests at each new boundary: `IngredientAnalyzer`/`Defuzzer` (MRK merge + fuzzy
   variant counts), `PotentialSolutionVerifier` (borrow-from-future rejection), `DefecitAnalyzer`
   (leaf vs in-loop missing items), `MaxUseAnalyzer` (cyclic peak grab).
4. Run `LpPerformanceScenariosTest` and compare against `performance-history/lp-performance-history.csv`
   to confirm no major regression from the new solve-session model.
5. Smoke-test the four `AutocraftingNetworkComponentImpl` entry points after the callsite swap.

## Decisions
- **Clean-room with surgical lifts.** Per direction, the orchestration/flow is rebuilt from the map.
  But parity is a hard requirement, so the parity-defining algorithms (priority back-propagation,
  MRK merge, fuzzy stars-and-bars expansion + deterministic variant IDs, greedy MRK→ResourceKey
  allocation incl. the "last member" quirk, cycle detection) are **lifted near-verbatim** rather than
  reinvented. Reimplementing these blind is the main parity risk; copying them is the mitigation.
- **Result split is the core change.** `CraftingApplication`/`CraftingSolution` are deleted in favor of
  `PotentialSolution` → `SolvedCraftingProblem` | `UnsolvableCraftingProblem`. No combined type survives.
- **Copy & rename data records** into the new package; old_lp keeps its own copies until deleted.
- **Entry-point signatures preserved** on `Orchestrator` so the network module swap is a one-line import change per callsite.
- **Adopt the linear-solver-ownership PLAN** for `LpEngine` threading rather than lifting per-solve
  `OjalgoSolveRunner` threads.

## Out of scope / Followup
- The "missing combinations" feature hinted in the old README (telling the user "10 planks, oak or
  birch" instead of resolving to one) — `UnsolvableCraftingProblem` should leave room for it, but it's not built here.
- Deleting `old_lp/` is the final step only after parity tests pass against `lp/`; keep it until then.
- The `PLAN-linear-solver-ownership-timeouts.md` refactor is adopted conceptually inside `LpEngine`;
  fully executing that separate plan's 9 steps is its own effort.

## Further Considerations
1. **Verifier output contract** is the riskiest unknown. Recommend defining `SolvedCraftingProblem`'s
   ordering/critical-item representation *before* writing `PotentialSolutionVerifier`, since both
   `SolveDesanitizer` and `MaxUseAnalyzer` consume it. Option A: explicit per-critical-item ordering
   constraints (richer, matches map). Option B: a flat globally-ordered step list (simpler, less
   info for cyclic peak analysis). Recommend A.
2. **TaskDispatcher/CycleSafeBudgetPlanner placement** — they're missing as stubs in `lp/task/`.
   Recommend adding them there (mirrors old_lp) rather than folding into `Orchestrator`.
3. **Phasing of parity tests** — recommend re-targeting the relevant `Ported*` test at the end of each
   phase that completes its surface (e.g., `PortedPreviewTest` after Phase 6) instead of all at once in
   Phase 8, to catch parity drift earlier.
