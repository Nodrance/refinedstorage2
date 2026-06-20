## Plan: Move ojalgo Model Ownership to LinearSolver with Centralized Timeout Execution

### TL;DR
Refactor LP solving so `LinearSolver` owns the full ojalgo model lifecycle (build, mutate, solve, extract), while `CraftingSolver` remains orchestration-only for loop snipping, branch exploration, fallback deficit flow, and execution planning. Replace per-solve thread creation with a centralized solve runner/session model that preserves hard timeout behavior for long or non-cooperative solver calls and keeps loop-snipping timeout budget independent from overall solve timeout.

**Chosen direction**
- Full API shift into `LinearSolver`: yes
- Single refactor (not staged dual APIs): yes
- Timeout mechanism redesign now: yes

**Constraints to preserve**
- Lexicographic semantics and recipe-priority compatibility behavior.
- Deficit semantics (`minimizeTotalDeficitWithFloor`) and no-worse-per-resource floor behavior.
- Existing cycle fallback behavior when loop-snipping budget is exhausted.
- Independent cancellation budgets: main solve budget vs loop-snipping budget.

**Steps**
1. Introduce a unified timeout/deadline primitive in LP calculation layer.
Dependency: none.
Parallelizable: no.
Outcome: deterministic budget checks with child budgets for sub-phases.

2. Add centralized solve runner for non-cooperative ojalgo solves.
Dependency: step 1.
Parallelizable: no.
Outcome: one dedicated worker/session execution path per solver session, replacing per-solve thread spawn.

3. Refactor `LinearSolver` into a model-owning session.
Dependency: steps 1-2.
Parallelizable: partial (internal extraction can be parallel with tests).
Outcome: base model created once per session with reusable variables/constraints/objective rewiring.

4. Rewrite `lexicographicMinimum` to reuse one model session.
Dependency: step 3.
Parallelizable: no.
Outcome: eliminate repeated full model rebuilds across lock passes while preserving lock-order behavior.

5. Move remaining LP responsibilities into `LinearSolver` APIs.
Dependency: steps 3-4.
Parallelizable: limited.
Outcome: feasibility/deficit/maximize/disabled-recipe variants all use same session-owned LP engine.

6. Simplify `CraftingSolver` to orchestration-only responsibilities.
Dependency: steps 4-5.
Parallelizable: no.
Outcome: `CraftingSolver` wires cycle exploration + fallback + execution planning, without owning LP internals.

7. Integrate independent cancellation flows.
Dependency: steps 2-6.
Parallelizable: no.
Outcome: loop-snipping timeout can expire without killing main solve fallback path, mirroring current behavior.

8. Remove deprecated per-solve timeout wrappers and dead code.
Dependency: steps 2-7.
Parallelizable: no.
Outcome: less overhead and simpler cancellation flow.

9. Run parity + performance verification and finalize.
Dependency: all prior steps.
Parallelizable: tests can run in parallel where practical.
Outcome: behavior parity maintained and item #3 performance bottleneck reduced.

**Relevant files**
- `refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/calculation/LinearSolver.java`
Symbols to refactor: `lexicographicMinimum`, `solveWithObjective`, `solveWithObjectiveInternal`, `createModelWithDiagnostics`, `awaitNonCooperativeTask`, objective/constraint builders.

- `refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/calculation/CraftingSolver.java`
Symbols to simplify: `solve`, `analyzeDeficitResources`, `optimizeDeficitResources`, `solveWithDisabledRecipes`, cycle-elimination wiring.

- `refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/CraftingOrchestrator.java`
Symbols to keep aligned: loop-snipping token/budget creation and solver entry wiring.

- `refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/calculation/ExecutionPlanner.java`
No major refactor expected; verify integration contracts remain unchanged.

- `refinedstorage-autocrafting-api/src/test/java/com/refinedmods/refinedstorage/api/autocrafting/lp/CraftingSolverCancellationTest.java`
Must continue to pass loop-snipping-timeout fallback behavior.

- `refinedstorage-autocrafting-api/src/test/java/com/refinedmods/refinedstorage/api/autocrafting/lp/PortedPreviewTest.java`
- `refinedstorage-autocrafting-api/src/test/java/com/refinedmods/refinedstorage/api/autocrafting/lp/PortedTaskPlanTest.java`
- `refinedstorage-autocrafting-api/src/test/java/com/refinedmods/refinedstorage/api/autocrafting/lp/LpPerformanceScenariosTest.java`
Behavior and performance parity validation.

**Verification**
1. Compile and unit test LP module.
- `./gradlew :refinedstorage-autocrafting-api:compileJava`
- `./gradlew :refinedstorage-autocrafting-api:test`

2. Run parity-critical suites.
- `./gradlew :refinedstorage-autocrafting-api:test --tests com.refinedmods.refinedstorage.api.autocrafting.lp.CraftingSolverCancellationTest`
- `./gradlew :refinedstorage-autocrafting-api:test --tests com.refinedmods.refinedstorage.api.autocrafting.lp.PortedPreviewTest`
- `./gradlew :refinedstorage-autocrafting-api:test --tests com.refinedmods.refinedstorage.api.autocrafting.lp.PortedTaskPlanTest`

3. Run performance suite and compare timing/allocation profile against baseline.
- `./gradlew :refinedstorage-autocrafting-api:test --tests com.refinedmods.refinedstorage.api.autocrafting.lp.LpPerformanceScenariosTest`

4. Manual behavior checks.
- Loop-snipping deadline expiry still triggers deficit fallback path.
- Main cancellation still aborts full solve when expected.
- No thread leak after repeated solve/cancel cycles.

**Decisions**
- Prefer session-owned model reuse over repeated model recreation.
- Preserve deterministic lexicographic locking order and compatibility behavior.
- Keep two-budget model (main + loop-snipping) explicit and test-covered.
- If model mutation safety in ojalgo proves unreliable, fallback to session-level rebuild strategy without changing external API shape.

**Risk controls and rollback checkpoints**
- Checkpoint A (after timeout runner + token integration): all cancellation tests pass before lexicographic rewrite.
- Checkpoint B (after lexicographic rewrite): ported behavior suites pass before deleting old code.
- Checkpoint C (after cleanup): performance scenarios pass and no stuck solver threads observed.
- Rollback path: retain previous solve path behind temporary internal flag until Checkpoint B completes, then remove.

**Out of scope/Followup**
- No redesign of `ExecutionPlanner` backtracking heuristics in this pass.
- No fuzzy expansion optimization changes in this pass.
- Optional followup: additional coefficient caching and sparse-matrix optimization once this architectural shift lands.
