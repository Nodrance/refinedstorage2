## Plan: Remove Desanitizer timesApplied Bottleneck

Replace per-application desanitization with batch-aware desanitization that computes maximal runs of identical concrete inputs. This keeps fuzzy-allocation correctness while reducing worst-case decode work from linear in timesApplied to linear in the number of allocation change points.

**Steps**
1. Phase 1 - Baseline and guardrails: document current behavior and invariants of RecipeDesanitizer.decodePlanStepsToDesanitized and decodeSanitizedResourcePoolToDesanitizedWithRemaining, including mutable-state expectations for remainingSanitizedStorage and cancellation behavior (depends on none).
2. Phase 2 - Batch-model design: define a deterministic batch transition model for one CraftingStep that identifies the next contiguous run length where decoded input stays identical, based on per-MultiResourceKey demand and remainingSanitizedStorage depletion order in allocateIntoDesanitizedMap (depends on 1).
3. Phase 2 - Batch-model design: introduce private helper(s) in RecipeDesanitizer to compute one batch at a time: decoded input for current state, max stable batch length, and storage updates for that batch count; keep arithmetic overflow-safe and preserve existing fallback semantics when storage is insufficient (depends on 2).
4. Phase 3 - Main-loop rewrite: refactor decodePlanStepsToDesanitized to replace the for index < timesApplied loop with a while remainingApplications > 0 loop that emits DesanitizedRecipeApplicationStep entries per computed batch; preserve ordering and existing output layout generation (depends on 3).
5. Phase 3 - Main-loop rewrite: preserve cancellation semantics by checking cancellation before each step and each computed batch; if very large batch lengths are possible, add bounded periodic cancellation checks inside any long-running helper iteration (parallel with 4).
6. Phase 4 - Correctness tests only: add focused unit tests in a new RecipeDesanitizerTest under lp/desanitization for: stable-input batching into one step, input-shift batching when a preferred resource depletes, and insufficient-storage fallback behavior; assert both timesApplied aggregation and concrete ingredient allocations (depends on 4 and 5).
7. Phase 4 - Integration confidence: extend an existing LP integration test (prefer PortedTaskPlanTest) with a high-timesApplied scenario that validates decoded plan correctness and that total per-pattern timesApplied remains unchanged after desanitization rewrite (depends on 6).

**Relevant files**
- refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/desanitization/RecipeDesanitizer.java - modify decodePlanStepsToDesanitized and add batch-transition helpers around decodeSanitizedResourcePoolToDesanitizedWithRemaining and allocateIntoDesanitizedMap semantics.
- refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/desanitization/DesanitizedRecipeApplicationStep.java - confirm unchanged contract expectations for aggregated timesApplied per emitted step.
- refinedstorage-autocrafting-api/src/test/java/com/refinedmods/refinedstorage/api/autocrafting/lp/desanitization/RecipeDesanitizerTest.java - new correctness-focused unit tests for batching transitions and edge cases.
- refinedstorage-autocrafting-api/src/test/java/com/refinedmods/refinedstorage/api/autocrafting/lp/PortedTaskPlanTest.java - integration assertion that rewritten desanitization preserves plan totals and output behavior.
- refinedstorage-autocrafting-api/src/main/java/com/refinedmods/refinedstorage/api/autocrafting/lp/CraftingOrchestrator.java - validate call-path assumptions and ensure no API contract changes are required.

**Verification**
1. Run targeted tests for desanitization and LP plan paths in refinedstorage-autocrafting-api, including the new RecipeDesanitizerTest and updated PortedTaskPlanTest.
2. Run full refinedstorage-autocrafting-api test suite to detect regressions in preview/task planning behavior impacted by desanitized steps.
3. Add one deterministic high-timesApplied case (correctness only) and verify completion under normal test timeout without changing public APIs.
4. Manually inspect that decoded step sequence still preserves original recipe order, cumulative timesApplied totals, and resource allocation fallback behavior.

**Decisions**
- Chosen direction: aggressive algorithmic optimization rather than incremental memoization.
- Test scope: correctness tests only for this pass (no benchmark harness in this change).
- Preserve existing public data contracts: no changes to DesanitizedRecipeApplicationPath or external orchestrator signatures.

**Out of scope/Followup**
- Introducing microbenchmark infrastructure or performance threshold assertions in CI.
- Broader LP performance fixes outside RecipeDesanitizer (for example sanitizer combinatorics, preview tree construction, or cycle analysis).
- Reworking desanitization architecture to defer conversion beyond current API boundary.

**Further Considerations**
1. If batch transition math becomes too complex for one change, land in two commits: first correctness-equivalent helper extraction, second algorithmic loop replacement.
2. If fuzzy allocation churn is more frequent than expected in real inputs, consider a follow-up hybrid approach that mixes run-length batching with limited memoization of short-lived states.
