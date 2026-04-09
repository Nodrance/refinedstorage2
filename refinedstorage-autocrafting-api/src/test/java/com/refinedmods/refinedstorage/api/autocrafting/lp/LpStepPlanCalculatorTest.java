package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.CancelledCancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.A;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.B;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.C;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.D;
import static org.assertj.core.api.Assertions.assertThat;

class LpStepPlanCalculatorTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LpStepPlanCalculatorTest.class);

    @Test
    void shouldNotReportCyclesWhenNoStepsExist() {
        assertThat(LpStepPlanCalculator.hasRecipeCycles(List.of())).isFalse();
    }

    @Test
    void shouldNotReportCyclesForAcyclicDependencies() {
        final Pattern first = pattern().ingredient(A, 1).output(B, 1).build();
        final Pattern second = pattern().ingredient(B, 1).output(C, 1).build();
        final Pattern third = pattern().ingredient(C, 1).output(D, 1).build();

        final List<LpExecutionPlanStep> steps = List.of(
            step(first, 0, 1),
            step(second, 1, 1),
            step(third, 2, 1)
        );

        assertThat(LpStepPlanCalculator.hasRecipeCycles(steps)).isFalse();
    }

    @Test
    void shouldReportCyclesForMutuallyDependentPatterns() {
        final Pattern first = pattern().ingredient(B, 1).output(A, 1).build();
        final Pattern second = pattern().ingredient(A, 1).output(B, 1).build();

        final List<LpExecutionPlanStep> steps = List.of(
            step(first, 0, 1),
            step(second, 1, 1)
        );

        assertThat(LpStepPlanCalculator.hasRecipeCycles(steps)).isTrue();
    }

    @Test
    void shouldReportCyclesForSelfDependentPattern() {
        final Pattern selfDependent = pattern().ingredient(A, 1).output(A, 1).build();

        final List<LpExecutionPlanStep> steps = List.of(step(selfDependent, 0, 1));

        assertThat(LpStepPlanCalculator.hasRecipeCycles(steps)).isTrue();
    }

    @Test
    void shouldReportCyclesWhenDependencyIsProducedAsByproduct() {
        final Pattern first = pattern().ingredient(C, 1).output(A, 1).byproduct(B, 1).build();
        final Pattern second = pattern().ingredient(B, 1).output(C, 1).build();

        final List<LpExecutionPlanStep> steps = List.of(
            step(first, 0, 1),
            step(second, 1, 1)
        );

        assertThat(LpStepPlanCalculator.hasRecipeCycles(steps)).isTrue();
    }

    @Test
    void shouldReturnEmptyWhenCalculationIsCancelled() {
        final Optional<LpStepPlan> result = LpStepPlanCalculator.calculateSteps(
            List.of(pattern().ingredient(A, 1).output(B, 1).build()),
            LOGGER,
            new RootStorageImpl(),
            B,
            1,
            new CancellationToken() {
                @Override
                public boolean isCancelled() {
                    return true;
                }

                @Override
                public void cancel() {
                }
            }
        );

        assertThat(result).isEmpty();
    }

    @Test
    void shouldPlanSelfConsumingRecipeAsThreeOrderedSteps() {
        // Recipe A+B→2A is self-consuming: A is both an input and an output.
        // Starting with 1A and excess B, targeting 4 more A, the LP planner must break
        // the plan into steps that never consume more A than is currently on hand:
        //   step 1: apply once  (1A+1B → 2A)  — only 1A available
        //   step 2: apply twice (2A+2B → 4A)  — now 2A available
        //   step 3: apply once  (1A+1B → 2A)  — only 1 more iteration needed
        final Pattern selfConsuming = pattern().ingredient(A, 1).ingredient(B, 1).output(A, 2).build();
        final RootStorage storage = new RootStorageImpl();
        final StorageImpl source = new StorageImpl();
        source.insert(A, 1, Action.EXECUTE, Actor.EMPTY);
        source.insert(B, 10, Action.EXECUTE, Actor.EMPTY);
        storage.addSource(source);

        final Optional<LpStepPlan> result = LpStepPlanCalculator.calculateSteps(
            List.of(selfConsuming),
            LOGGER,
            storage,
            A,
            4,
            CancellationToken.NONE
        );

        assertThat(result).isPresent();
        final LpStepPlan plan = result.get();
        assertThat(plan.hasRecipeCycles()).isTrue();
        assertThat(plan.steps()).hasSize(3);
        assertThat(plan.steps().get(0).iterations()).isEqualTo(1);
        assertThat(plan.steps().get(1).iterations()).isEqualTo(2);
        assertThat(plan.steps().get(2).iterations()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyForFuzzyPatternWithNoViableInputs() {
        // Fuzzy pattern with no resources in storage and no sub-patterns:
        // all ingredient options are pruned, recipe is dropped, result is empty.
        final Pattern fuzzy = pattern().ingredient(1).input(A).input(B).end().output(C, 1).build();

        final Optional<LpStepPlan> result = LpStepPlanCalculator.calculateSteps(
            List.of(fuzzy),
            LOGGER,
            new RootStorageImpl(),
            C,
            1,
            CancellationToken.NONE
        );

        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleMixedFuzzyAndConcretePatterns() {
        // Fuzzy pattern with no viable inputs is pruned; concrete pattern remains but
        // has no starting resources, so no executable plan exists.
        final Pattern compatible = pattern().ingredient(A, 1).output(B, 1).build();
        final Pattern fuzzy = pattern().ingredient(1).input(A).input(B).end().output(C, 1).build();

        final Optional<LpStepPlan> result = LpStepPlanCalculator.calculateSteps(
            List.of(compatible, fuzzy),
            LOGGER,
            new RootStorageImpl(),
            B,
            1,
            CancellationToken.NONE
        );

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNoExecutablePlanExists() {
        final Pattern recipe = pattern().ingredient(A, 1).output(B, 1).build();

        final Optional<LpStepPlan> result = LpStepPlanCalculator.calculateSteps(
            List.of(recipe),
            LOGGER,
            new RootStorageImpl(),
            B,
            1,
            CancellationToken.NONE
        );

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnZeroMaxAmountWhenCalculationIsCancelled() {
        final long result = LpStepPlanCalculator.calculateMaxAmount(
            List.of(pattern().ingredient(A, 1).output(B, 1).build()),
            LOGGER,
            new RootStorageImpl(),
            B,
            1,
            new CancelledCancellationToken()
        );

        assertThat(result).isZero();
    }

    @Test
    void shouldReturnPlanWhenExecutablePlanExists() {
        final Pattern recipe = pattern().ingredient(A, 1).output(B, 1).build();
        final RootStorage rootStorage = new RootStorageImpl();
        final StorageImpl source = new StorageImpl();
        source.insert(A, 1, Action.EXECUTE, Actor.EMPTY);
        rootStorage.addSource(source);

        final Optional<LpStepPlan> result = LpStepPlanCalculator.calculateSteps(
            List.of(recipe),
            LOGGER,
            rootStorage,
            B,
            1,
            CancellationToken.NONE
        );

        assertThat(result).isPresent();
        assertThat(result.get().steps()).isNotEmpty();
    }

    private static LpExecutionPlanStep step(final Pattern pattern, final int index, final long amount) {
        return new LpExecutionPlanStep(LpPatternRecipe.fromPattern(pattern, index), amount);
    }
}
