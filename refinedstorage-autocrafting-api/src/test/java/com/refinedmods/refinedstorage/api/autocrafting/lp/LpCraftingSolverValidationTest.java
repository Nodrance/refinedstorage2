package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.CancelledCancellationToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.A;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LpCraftingSolverValidationTest {
    @Test
    void constructorShouldValidateOptions() {
        // Tests that the solver constructor validates and rejects null options.
        assertThatThrownBy(() -> new LpCraftingSolver((LpSolverOptions) null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("options cannot be null");
        assertThatThrownBy(() -> new LpCraftingSolver(LpSolverOptions.defaults(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("cancellationToken cannot be null");
    }

    @Test
    void publicApisShouldValidateNullArguments() {
        // Tests that all public solver methods validate their arguments and reject null values.
        final LpCraftingSolver solver = new LpCraftingSolver();
        final List<LpPatternRecipe> recipes = List.of(recipe());
        final LpResourceSet resources = new LpResourceSet();

        assertThatThrownBy(() -> solver.solve(null, resources, resources))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> solver.solve(recipes, null, resources))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> solver.solve(recipes, resources, null))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> solver.computeRequiredBaseItems(null, resources, resources))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("recipes cannot be null");
        assertThatThrownBy(() -> solver.findRecipeApplicationPlanViaCycleElimination(null, resources, resources))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("recipes cannot be null");
    }

    @Test
    void recipeApplicationPlanResultRecordShouldValidateAndCopy() {
        // Tests that RecipeApplicationPlanResult validates inputs and creates defensive copies of mutable collections.
        final LpPatternRecipe recipe = recipe();
        final LpCraftingSolution solution = new LpCraftingSolution(Map.of(), LpResourceSet.empty(), List.of(A));
        final List<LpExecutionPlanStep> mutablePlan =
            new java.util.ArrayList<>(List.of(new LpExecutionPlanStep(recipe, 1)));

        final LpCraftingSolver.RecipeApplicationPlanResult result =
            new LpCraftingSolver.RecipeApplicationPlanResult(solution, mutablePlan, LpResourceSet.empty());
        mutablePlan.clear();

        assertThat(result.plan()).hasSize(1);

        assertThatThrownBy(() -> new LpCraftingSolver.RecipeApplicationPlanResult(null, List.of(),
            LpResourceSet.empty()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("solution cannot be null");
        assertThatThrownBy(() -> new LpCraftingSolver.RecipeApplicationPlanResult(solution, null,
            LpResourceSet.empty()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LpCraftingSolver.RecipeApplicationPlanResult(solution, List.of(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("requiredBaseItems cannot be null");
    }

    @Test
    void cycleEliminationAndPlanningOutcomeRecordsShouldValidateAndCopy() {
        // Tests that result record types validate inputs and create defensive copies of disabled recipe IDs.
        final LpCraftingSolver.CycleEliminationResult cycle = new LpCraftingSolver.CycleEliminationResult(
            Optional.empty(),
            Set.of(UUID.randomUUID())
        );
        assertThat(cycle.fallbackDisabledRecipeIds()).hasSize(1);

        assertThatThrownBy(() -> new LpCraftingSolver.CycleEliminationResult(null, Set.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("recipeApplicationResult cannot be null");
        assertThatThrownBy(() -> new LpCraftingSolver.CycleEliminationResult(Optional.empty(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("fallbackDisabledRecipeIds cannot be null");

        final java.util.Set<UUID> mutable = new java.util.LinkedHashSet<>();
        mutable.add(UUID.randomUUID());
        final LpCraftingSolver.PlanningOutcome outcome = new LpCraftingSolver.PlanningOutcome(
            1,
            Optional.empty(),
            new LpResourceSet(),
            mutable
        );
        mutable.clear();

        assertThat(outcome.fallbackDisabledRecipeIds()).hasSize(1);

        assertThatThrownBy(() -> new LpCraftingSolver.PlanningOutcome(1, null, new LpResourceSet(), Set.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("recipeApplicationResult cannot be null");
        assertThatThrownBy(() -> new LpCraftingSolver.PlanningOutcome(1, Optional.empty(), null, Set.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("requiredBaseItems cannot be null");
        assertThatThrownBy(() -> new LpCraftingSolver.PlanningOutcome(1, Optional.empty(), new LpResourceSet(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("fallbackDisabledRecipeIds cannot be null");
    }

    @Test
    void solveShouldReturnRecipeApplicationPlanWhenBaseItemsAreMissing() {
        // Ensures deficit analysis still surfaces a recipe-application plan.
        // Example: 4 logs -> 16 planks even when logs are currently missing.
        final LpPatternRecipe recipe = LpPatternRecipe.fromPattern(
            pattern().ingredient(A, 1).output(B, 4).build(),
            0
        );
        final LpResourceSet starting = new LpResourceSet();
        final LpResourceSet target = new LpResourceSet();
        target.setAmount(B, 16);

        final LpCraftingSolver solver = new LpCraftingSolver();
        final LpCraftingSolver.PlanningOutcome outcome = solver.solve(
            List.of(recipe),
            starting,
            target
        );

        assertThat(outcome.recipeApplicationResult()).isPresent();
        assertThat(outcome.requiredBaseItems().getAmount(A)).isEqualTo(4);

        final LpCraftingSolver.RecipeApplicationPlanResult planResult =
            outcome.recipeApplicationResult().get();
        assertThat(planResult.requiredBaseItems().getAmount(A)).isEqualTo(4);
        assertThat(planResult.plan()).hasSize(1);
        assertThat(planResult.plan().getFirst().recipe().uniqueId()).isEqualTo(recipe.uniqueId());
        assertThat(planResult.plan().getFirst().iterations()).isEqualTo(4);
    }

    @Test
    void solveShouldReturnCancelledOutcomeWhenTokenIsCancelled() {
        final LpCraftingSolver solver = new LpCraftingSolver(new CancelledCancellationToken());
        final LpPatternRecipe recipe = recipe();
        final LpResourceSet starting = new LpResourceSet();
        final LpResourceSet target = new LpResourceSet();
        target.setAmount(B, 1);

        final LpCraftingSolver.PlanningOutcome outcome = solver.solve(List.of(recipe), starting, target);

        assertThat(outcome.maxCraftableAmount()).isZero();
        assertThat(outcome.recipeApplicationResult()).isEmpty();
        assertThat(outcome.requiredBaseItems().isEmpty()).isTrue();
        assertThat(outcome.fallbackDisabledRecipeIds()).isEmpty();
    }

    @Test
    void computeRequiredBaseItemsShouldReturnEmptyWhenTokenIsCancelled() {
        final LpCraftingSolver solver = new LpCraftingSolver(new CancelledCancellationToken());

        assertThat(solver.computeRequiredBaseItems(List.of(recipe()), new LpResourceSet(), new LpResourceSet())
            .isEmpty()).isTrue();
    }

    private static LpPatternRecipe recipe() {
        return LpPatternRecipe.fromPattern(pattern().ingredient(A, 1).output(B, 1).build(), 0);
    }
}
