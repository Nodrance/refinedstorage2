package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collection;

import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.storage;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.A;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.B;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.C;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.X;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.Y;
import static org.assertj.core.api.Assertions.assertThat;

class LpPlanningHelperTest {
    @Test
    void shouldUseLpWhenFuzzyIngredientHasMultipleViableInputs() {
        final Pattern root = pattern().ingredient(1).input(A).input(B).end().output(X, 1).build();
        final PatternRepositoryImpl repository = repository(root);
        final RootStorage rootStorage = storage(new ResourceAmount(A, 1), new ResourceAmount(B, 1));

        final boolean shouldUseLp = LpPlanningHelper.shouldUseLPSystem(X, rootStorage, repository);

        // LP system now handles fuzzy recipes through subset expansion
        assertThat(shouldUseLp).isTrue();
    }

    @Test
    void shouldUseLpWhenOnlyOneFuzzyInputIsViable() {
        final Pattern root = pattern().ingredient(1).input(A).input(B).end().output(X, 1).build();
        final PatternRepositoryImpl repository = repository(root);
        final RootStorage rootStorage = storage(new ResourceAmount(A, 1));

        final boolean shouldUseLp = LpPlanningHelper.shouldUseLPSystem(X, rootStorage, repository);

        assertThat(shouldUseLp).isTrue();
    }

    @Test
    void shouldTreatCraftableInputAsViableAndCollectItsPatterns() {
        final Pattern root = pattern().ingredient(1).input(A).input(B).end().output(X, 1).build();
        final Pattern craftB = pattern().ingredient(C, 1).output(B, 1).build();
        final PatternRepositoryImpl repository = repository(root, craftB);

        final boolean shouldUseLp = LpPlanningHelper.shouldUseLPSystem(X, storage(), repository);
        final Collection<Pattern> relevant = LpPlanningHelper.collectRelevantPatternsForLp(X, storage(), repository);

        assertThat(shouldUseLp).isTrue();
        assertThat(relevant).containsExactlyInAnyOrder(root, craftB);
    }

    @Test
    void shouldCollectRelevantPatternsAcrossCyclesWithoutDuplicates() {
        final Pattern root = pattern().ingredient(1).input(A).input(B).end().output(X, 1).build();
        final Pattern craftAFromX = pattern().ingredient(X, 1).output(A, 1).build();
        final Pattern craftBFromY = pattern().ingredient(Y, 1).output(B, 1).build();
        final PatternRepositoryImpl repository = repository(root, craftAFromX, craftBFromY);

        final Collection<Pattern> relevant = LpPlanningHelper.collectRelevantPatternsForLp(X, storage(), repository);

        assertThat(relevant).containsExactlyInAnyOrder(root, craftAFromX, craftBFromY);
    }

    @Test
    void shouldUseLpWhenDeepSubPatternHasFuzzyIngredientWithMultipleViableOptions() {
        // Verifies that the LP system accepts deep sub-patterns with fuzzy ingredients
        // now that fuzzy expansion is supported.
        final Pattern craftA = pattern().ingredient(1).input(B).input(C).end().output(A, 1).build();
        final Pattern root = pattern().ingredient(A, 1).output(X, 1).build();
        final PatternRepositoryImpl repository = repository(root, craftA);
        final RootStorage rootStorage = storage(new ResourceAmount(B, 1), new ResourceAmount(C, 1));

        final boolean shouldUseLp = LpPlanningHelper.shouldUseLPSystem(X, rootStorage, repository);

        // LP system now handles fuzzy recipes through subset expansion
        assertThat(shouldUseLp).isTrue();
    }

    @Test
    void shouldUseLpWhenDeepSingleIngredientHasOnlyOneViableOption() {
        // Verifies that the LP system accepts deep fuzzy patterns reached through
        // single-ingredient traversal now that fuzzy expansion is supported.
        final Pattern craftA = pattern().ingredient(1).input(B).input(C).end().output(A, 1).build();
        final Pattern root = pattern().ingredient(1).input(A).input(Y).end().output(X, 1).build();
        final PatternRepositoryImpl repository = repository(root, craftA);
        // Only A is viable (in storage), and A's sub-pattern has [B,C] both in storage
        final RootStorage rootStorage = storage(
            new ResourceAmount(A, 1),
            new ResourceAmount(B, 1),
            new ResourceAmount(C, 1)
        );

        // LP system now handles fuzzy recipes through subset expansion
        final boolean shouldUseLp = LpPlanningHelper.shouldUseLPSystem(X, rootStorage, repository);

        assertThat(shouldUseLp).isTrue();
    }

    @Test
    void shouldCollectSubPatternReachedThroughSingleIngredientTraversal() {
        // Kills collectRelevantPatternsForLp line 90 (addLast removal).
        // If single-ingredient resource A is not added to traversal queue, craftA is never discovered.
        final Pattern craftA = pattern().ingredient(C, 1).output(A, 1).build();
        final Pattern root = pattern().ingredient(A, 1).output(X, 1).build();
        final PatternRepositoryImpl repository = repository(root, craftA);

        final Collection<Pattern> relevant = LpPlanningHelper.collectRelevantPatternsForLp(
            X, storage(), repository
        );

        assertThat(relevant).containsExactlyInAnyOrder(root, craftA);
    }

    @Test
    void shouldOnlyIncludeFuzzyInputThatIsCraftableInCollectedPatterns() {
        // Kills lambda$collectRelevantPatternsForLp$1 negated conditional.
        // With negation, the filter keeps only NON-viable inputs, so craftB would not be
        // queued (B is craftable = viable) and its pattern would not be collected.
        final Pattern craftB = pattern().ingredient(C, 1).output(B, 1).build();
        final Pattern root = pattern().ingredient(1).input(A).input(B).end().output(X, 1).build();
        final PatternRepositoryImpl repository = repository(root, craftB);

        final Collection<Pattern> relevant = LpPlanningHelper.collectRelevantPatternsForLp(
            X, storage(), repository  // no resources in storage; B is craftable
        );

        // craftB must be present because B is a viable fuzzy input with a sub-pattern
        assertThat(relevant).containsExactlyInAnyOrder(root, craftB);
    }

    private static PatternRepositoryImpl repository(final Pattern... patterns) {
        final PatternRepositoryImpl repository = new PatternRepositoryImpl();
        for (final Pattern pattern : patterns) {
            repository.add(pattern, 0);
        }
        return repository;
    }
}
