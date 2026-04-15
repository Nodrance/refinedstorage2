package com.refinedmods.refinedstorage.api.autocrafting.lp;

final class LpNonApplicablePortNotes {
    private LpNonApplicablePortNotes() {
    }

    /*
        Legacy tests intentionally not ported because they do not target LP-specific behavior.

        Shared domain model tests:

        - IngredientTest
            Reason: validates Ingredient invariants and input selection semantics shared by both legacy and LP paths.
            LP status: no separate LP-only Ingredient type exists.

        - PatternTest
            Reason: validates Pattern and PatternLayout behavior shared by both systems.
            LP status: LP consumes Pattern instances but does not redefine their contract.

        - PatternRepositoryImplTest
            Reason: validates repository add/update/remove/priority behavior for the shared PatternRepositoryImpl.
            LP status: LP uses the same repository implementation rather than an LP-specific one.

        - calculation/AmountTest
            Reason: validates the legacy calculation.Amount value object.
            Non-ported methods:
                - testInvalidIterations
                - testInvalidAmountPerIteration
                - testMinimumValid
                - testTotal
            LP status: there is no LP counterpart to calculation.Amount; LP uses step counts, recipe values, and resource pools.

        Legacy helper/builder tests:

        - preview/PreviewBuilderTest
            Reason: validates the legacy test helper PreviewBuilder rather than production LP logic.
            Non-ported methods:
                - testDefaultState
                - testWithPatternWithCycle
                - testPreview
                - testToCraftMustBeLargerThanZero
                - testMissingMustBeLargerThanZero
                - testAvailableMustBeLargerThanZero
            LP status: LP preview tests can reuse the helper, but there is no distinct LP builder implementation to port against.

        - preview/TreePreviewBuilder / TreePreviewBuilder helper behavior
            Reason: same as PreviewBuilderTest; this is test scaffolding, not LP production code.
            LP status: reused as a helper only.

        Legacy task-execution tests:

        - task/TaskImplTest
            Reason: validates runtime task execution state transitions in TaskImpl.
            LP status: LP planning produces RecipeApplicationPath values, but actual runtime execution is still covered elsewhere via dispatcher/network integration tests rather than an LP-specific TaskImpl.

        Legacy-only expectations that remain commented out inside LP parity test files:

        - Legacy overflow expectations in craftability/task/preview tests
            Reason: LP does not expose overflow through the same public contract as the legacy calculator/listeners.

        - Legacy "pattern not found" preview/tree-preview exceptions
            Reason: LP preview entry points degrade to NOT_AVAILABLE-style results instead of throwing the same exception.

        - Legacy cycle-detected preview/tree-preview top-level results
            Reason: LP surfaces cycle information differently, primarily through outputs-of-cycle metadata and solve behavior.

        - Legacy TaskPlan ingredient-map shape assertions
            Reason: LP exposes RecipeApplicationPath/RecipeApplicationSet, not the same per-slot TaskPlan.PatternPlan structure.
    */
}