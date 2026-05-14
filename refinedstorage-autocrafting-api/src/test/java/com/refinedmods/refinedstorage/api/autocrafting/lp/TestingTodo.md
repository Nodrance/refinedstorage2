# CyclicTaskImpl Ported Test Failures - Updated Status

Current Test Status (Latest):

- 21 tests total
- Latest run: 17 passing / 4 failing
- Recent runs after queue refactor are stable at 4 failing in PortedTaskImplTest
- Effort to reach 100%: Estimated 2-3 additional weeks (requires full refactoring to unified activePatterns architecture)

## Session Progress

Started with: 5/21 passing (16 failures)
Best observed during this cycle: 17/21 passing (4 failures)
Latest observed: 17/21 passing (4 failures)
Net improvement: +12 tests passing, -12 failures

## Latest Attempt Findings (May 10, 2026)

- Reworked CyclicTaskImpl to one shared pattern iterator (TaskImpl-like stepping flow).
- Replaced boolean cycle-safe eligibility with integer step budgets:
  - `Integer.MAX_VALUE` for unlimited cycle-safe patterns
  - finite countdown budget for planner override patterns
- Implemented budget consumption on each actual step and forced queue recalculation when finite budget is exhausted.
- Result: focused PortedTaskImplTest improved from 13 failures baseline to 4 failures.

## Completed Fixes (Session)

✓ Fixed CyclicTaskImpl.cancel() - immediately transitions to RETURNING_INTERNAL_STORAGE state
✓ Fixed startup extraction state machine - READY -> EXTRACTING_INITIAL_RESOURCES -> RUNNING
✓ Fixed CyclicTaskImpl.shouldNotify() override - respects local cancelled field
✓ Fixed CyclicTaskImpl.updateState() access modifier - changed to protected override
✓ Updated comprehensive comments documenting architecture differences from TaskImpl

## Tier 1: Lifecycle and Extraction

- ✓ shouldCancelTaskAndReturnInternalStorage
- ✓ shouldCancelTaskAndReturnInternalStorageEvenIfInternalStorageContainsStillExpectedProcessingOutputs
- ✓ shouldCancelTaskAndReturnInternalStorageEvenIfInternalStorageContainsUsedToExpectedProcessingOutputs
- ⚠ shouldPartiallyReturnOutputsWithInternalPatterns (partial)
- ⚠ shouldPartiallyExtractAllResources (requires partial extraction refinement)

## Remaining Failures (Current Baseline: 4)


Current failing set (baseline):

- shouldCompleteTaskWithExternalPatternAsChildPatternOfInternalPattern (line 636)
- shouldCompleteTaskWithInternalPatterns (line 341/359 depending on run)
- shouldReportStatusCorrectlyForInternalPatterns (line 1164)
- shouldReportStatusCorrectlyForInternalAndExternalPatterns (line 1200)

Latest progress delta:

- removed fragment/chunk dispatch model from CyclicTaskImpl runtime stepping
- converged onto single shared pattern iterator with cycle-safe membership check inside iterator
- added integer eligibility budgets per pattern and planner override countdown logic
- remaining regressions are now concentrated in nested external-child coordination and status aggregation

---

## Tier 1: Low Effort (Completed)

Tier 1 alignment work is complete.

### Fixed tests

- shouldCancelTaskAndReturnInternalStorage
- shouldCancelTaskAndReturnInternalStorageEvenIfInternalStorageContainsStillExpectedProcessingOutputs
- shouldCancelTaskAndReturnInternalStorageEvenIfInternalStorageContainsUsedToExpectedProcessingOutputs

### Still unstable in Tier 1

- shouldPartiallyExtractAllResources (intermittent; extraction progress tracking still under investigation)
- TaskImpl vs CyclicTaskImpl difference: TaskImpl tracks startup extraction progress through its single linear work queue, while CyclicTaskImpl performs peak-resource pulling against a shared cyclic buffer and can report progress/non-progress differently when partial pulls happen across steps.

### Implemented alignment

- cancel() now transitions cyclic tasks to RETURNING_INTERNAL_STORAGE immediately, matching TaskImpl lifecycle expectations.
- Startup extraction now follows TaskImpl lifecycle shape:
  - enters EXTRACTING_INITIAL_RESOURCES once at start
  - remains in EXTRACTING_INITIAL_RESOURCES until startup requirements are satisfied
  - transitions to RUNNING only after extraction completion
- Startup-phase inserts are no longer intercepted in a way that bypasses extraction-phase accounting.

---

## Tier 2: Medium Effort - Execution Sequencing and Completion (1 test)

### shouldCompleteTaskWithInternalPatterns

- Line: 341/359
- Root cause: Completion timing/storage behavior differs for internal cyclic execution.
- TaskImpl vs CyclicTaskImpl difference: TaskImpl executes all currently active patterns each tick, while CyclicTaskImpl now executes only cycle-safe/budget-eligible patterns. This can delay specific internal producer/consumer transitions and leave intermediate storage states one tick behind TaskImpl expectations.
- Fix scope: tighten budget consumption/recalculation ordering so internal completion and internal-storage assertions match TaskImpl tick boundaries.

---

## Tier 3: High Effort - External Pattern Behavior (1 test)

### shouldCompleteTaskWithExternalPatternAsChildPatternOfInternalPattern

- Line: 636
- Root cause: Cyclic path execution does not yet fully emulate nested external pattern behavior.
- TaskImpl vs CyclicTaskImpl difference: TaskImpl naturally keeps parent-child progression aligned because all active patterns are iterated each tick; CyclicTaskImpl eligibility budgets can postpone either parent or child stepping, so cross-pattern readiness propagation lags by one or more ticks.
- Fix scope: Extend cyclic dispatch model for mixed internal/external hierarchies.

---

## Tier 4: Very High Effort - Status Model and Sink Reporting (2 tests)

### shouldReportStatusCorrectlyForInternalPatterns

- Line: 1164
- Root cause: Status fields do not yet match expected internal progress model.
- TaskImpl vs CyclicTaskImpl difference: TaskImpl status/progress weighting is based on complete pattern lifecycle accounting (active + completed pattern weights). CyclicTaskImpl currently builds status from active pattern appendStatus calls plus a simplified progress ratio, so some internal processing/stored transitions and weighted completion percentages do not fully bubble up.
- Fix scope: Align extracting/stored/processing reporting semantics.

### shouldReportStatusCorrectlyForInternalAndExternalPatterns

- Line: 1200
- Root cause: Combined status for mixed internal/external paths diverges.
- TaskImpl vs CyclicTaskImpl difference: TaskImpl aggregates mixed internal/external progress in one pass with stable weight semantics. CyclicTaskImpl now gates stepping by eligibility budget, but status aggregation is still effectively active-only and does not carry full completed-pattern weighting/state transitions across both internal and external branches.
- Fix scope: Build unified status accounting across cyclic/external execution.

---

## Summary by Effort (Current)

| Tier | Category | Count | Est. Dev Days | Notes |
|------|----------|-------|---------------|-------|
| 1 | State transitions and startup extraction | 0 remaining (4 completed) | Completed | Lifecycle parity with TaskImpl extraction/cancel path established |
| 2 | Execution sequencing and completion | 1 | 1-2 | Mostly aligned; one internal completion boundary mismatch remains |
| 3 | External pattern behavior | 1 | 1-3 | Nested external child synchronization remains |
| 4 | Status and sink reporting | 2 | 2-4 | Weighted/combined status aggregation still diverges |

Estimated remaining alignment effort: about 2-3 weeks for full parity with current ported expectations.

---

## Recommended next implementation order

1. Finish Tier 2 execution sequencing/completion behaviors.
2. Address Tier 4 sink/state reporting gaps that block observability confidence.
3. Complete Tier 3 external pattern behavior parity.

This ordering keeps execution correctness ahead of reporting/external edge behavior while still preserving short feedback loops.

---

## Current Follow-Up Plan

The major runtime refactor is already complete:

1. CyclicTaskImpl now uses a single shared pattern iterator (TaskImpl-like stepping shape).
2. Fragment/chunk dispatch runtime has been removed from stepping flow.
3. Cycle safety uses integer eligibility budgets:
  - unlimited eligibility: `Integer.MAX_VALUE`
  - planner override eligibility: finite countdown consumed as steps execute

What remains to reach parity:

1. Internal completion boundary alignment:
  - ensure budget consumption and queue recalculation happen at the exact tick boundaries expected by TaskImpl tests.
2. Nested external child synchronization:
  - ensure parent-child readiness propagation does not lag when one side is budget-gated.
3. Status aggregation parity:
  - include full active + completed weighting semantics so internal and mixed internal/external status match TaskImpl expectations.

Validation gates (current):

1. Keep running focused `PortedTaskImplTest` after each change.
2. Maintain or improve current baseline: 17 passing / 4 failing.
3. When focused suite is green, run full module tests and update this file.
