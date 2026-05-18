package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.CancelledCancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.patterns;
import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.storage;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternFixtures.CRAFTING_TABLE_PATTERN;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternFixtures.OAK_PLANKS_PATTERN;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternFixtures.SPRUCE_PLANKS_PATTERN;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.CRAFTING_TABLE;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_LOG;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_PLANKS;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.SPRUCE_LOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortedTaskPlanTest {
    @Test
    void shouldNotPlanTaskWhenThereAreMissingResources() {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(OAK_PLANKS_PATTERN);

        final Optional<DesanitizedRecipeApplicationPath> optionalPlan = calculateCraftablePath(
            storage,
            patterns,
            OAK_PLANKS,
            1,
            CancellationToken.NONE
        );

        assertThat(optionalPlan).isEmpty();
    }

    @Test
    void shouldPlanTaskWithCorrectResourceAndAmount() {
        final RootStorage storage = storage(new ResourceAmount(OAK_LOG, 1));
        final PatternRepository patterns = patterns(OAK_PLANKS_PATTERN);

        final Optional<DesanitizedRecipeApplicationPath> optionalPlan = calculateCraftablePath(
            storage,
            patterns,
            OAK_PLANKS,
            1,
            CancellationToken.NONE
        );

        assertThat(optionalPlan).isPresent();
        final DesanitizedRecipeApplicationPath plan = optionalPlan.orElseThrow();
        assertThat(plan.steps()).hasSize(1);
        assertThat(totalTimesAppliedForPattern(plan, OAK_PLANKS_PATTERN)).isEqualTo(1L);
        assertThat(getAmount(plan.applicationSet().usedResources(), OAK_LOG)).isEqualTo(1L);
        assertThat(getAmount(plan.applicationSet().finalInventoryValues(), OAK_PLANKS)).isEqualTo(4L);
        assertThat(plan.applicationSet().missingResources().isEmpty()).isTrue();
    }

    @Test
    void shouldDetectPatternCycles() {
        final RootStorage storage = storage();
        final Pattern cycledPattern = pattern()
            .ingredient(OAK_LOG, 1)
            .output(OAK_PLANKS, 4)
            .build();
        final PatternRepository patterns = patterns(
            cycledPattern,
            pattern()
                .ingredient(OAK_PLANKS, 4)
                .output(OAK_LOG, 1)
                .build()
        );

        final Optional<DesanitizedRecipeApplicationPath> plan = calculateCraftablePath(
            storage,
            patterns,
            OAK_PLANKS,
            1,
            CancellationToken.NONE
        );

        assertThat(plan).isEmpty();
    }

    @Test
    void testPlanTaskWithIngredientsUsedFromRootStorageAndInternalStorageWithChildPattern() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_LOG, 1),
            new ResourceAmount(SPRUCE_LOG, 1),
            new ResourceAmount(OAK_PLANKS, 4)
        );
        final PatternRepository patterns = patterns(OAK_PLANKS_PATTERN, SPRUCE_PLANKS_PATTERN, CRAFTING_TABLE_PATTERN);

        final Optional<DesanitizedRecipeApplicationPath> optionalPlan = calculateCraftablePath(
            storage,
            patterns,
            CRAFTING_TABLE,
            3,
            CancellationToken.NONE
        );

        assertThat(optionalPlan).isPresent();
        final DesanitizedRecipeApplicationPath plan = optionalPlan.orElseThrow();
        assertThat(getAmount(plan.applicationSet().usedResources(), OAK_LOG)).isEqualTo(1L);
        assertThat(getAmount(plan.applicationSet().usedResources(), SPRUCE_LOG)).isEqualTo(1L);
        assertThat(getAmount(plan.applicationSet().usedResources(), OAK_PLANKS)).isGreaterThanOrEqualTo(4L);
        assertThat(totalTimesAppliedForPattern(plan, CRAFTING_TABLE_PATTERN)).isEqualTo(3L);
        assertThat(totalTimesAppliedForPattern(plan, OAK_PLANKS_PATTERN)).isEqualTo(1L);
        assertThat(totalTimesAppliedForPattern(plan, SPRUCE_PLANKS_PATTERN)).isEqualTo(1L);
        assertThat(plan.applicationSet().missingResources().isEmpty()).isTrue();
    }

    @Test
    void shouldNotModifyPlan() {
        final RootStorage storage = storage(
            new ResourceAmount(OAK_LOG, 1),
            new ResourceAmount(SPRUCE_LOG, 1),
            new ResourceAmount(OAK_PLANKS, 4)
        );
        final PatternRepository patterns = patterns(OAK_PLANKS_PATTERN, SPRUCE_PLANKS_PATTERN, CRAFTING_TABLE_PATTERN);

        final Optional<DesanitizedRecipeApplicationPath> optionalPlan = calculateCraftablePath(
            storage,
            patterns,
            CRAFTING_TABLE,
            3,
            CancellationToken.NONE
        );

        assertThat(optionalPlan).isPresent();
        final DesanitizedRecipeApplicationPath plan = optionalPlan.orElseThrow();

        assertThatThrownBy(() -> plan.steps().add(plan.steps().getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.applicationSet().recipeValues().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.applicationSet().relevantResourceKeys().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldPropagateCancelCorrectly() {
        final RootStorage storage = storage(new ResourceAmount(OAK_LOG, 1));
        final PatternRepository patterns = patterns(OAK_PLANKS_PATTERN);

        final Optional<DesanitizedRecipeApplicationPath> optionalPlan = calculateCraftablePath(
            storage,
            patterns,
            OAK_PLANKS,
            1,
            new CancelledCancellationToken()
        );

        assertThat(optionalPlan).isEmpty();
    }

    @Test
    void shouldDetectNumberOverflowInIngredient() {
        final RootStorage storage = storage();
        final PatternRepository patterns = patterns(
            pattern()
                .ingredient(OAK_LOG, Long.MAX_VALUE)
                .output(OAK_PLANKS, 1)
                .build()
        );

        final Optional<DesanitizedRecipeApplicationPath> optionalPlan = calculateCraftablePath(
            storage,
            patterns,
            OAK_PLANKS,
            2,
            CancellationToken.NONE
        );

        assertThat(optionalPlan).isEmpty();
    }

    // --- Helper methods below ---
    private static Optional<DesanitizedRecipeApplicationPath> calculateCraftablePath(
        final RootStorage storage,
        final PatternRepository patterns,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        return CraftingInitializer.solveToStepPlan(storage, patterns, resource, amount, cancellationToken)
            .filter(path -> path.applicationSet().missingResources().isEmpty());
    }

    private static long getAmount(final java.util.Map<ResourceKey, Long> resources, final ResourceKey resource) {
        return resources.getOrDefault(resource, 0L);
    }

    private static long totalTimesAppliedForPattern(final DesanitizedRecipeApplicationPath path, final Pattern pattern) {
        return path.steps().stream()
            .filter(step -> step.recipe().sourcePatternId().equals(pattern.id()))
            .mapToLong(step -> step.timesApplied())
            .sum();
    }
}