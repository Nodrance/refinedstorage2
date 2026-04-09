package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.A;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.B;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.C;
import static org.assertj.core.api.Assertions.assertThat;

class LpFuzzyExpanderTest {
    @Test
    void shouldKeepFirstOptionWhenFuzzyIngredientWouldBeFullyPruned() {
        final Ingredient ingredient = new Ingredient(1, List.of(A, B));

        final LpFuzzyExpander.IngredientPartition partition = LpFuzzyExpander.partitionSingleIngredient(
            ingredient,
            Set.of(),
            Map.of()
        );

        assertThat(partition.amount()).isEqualTo(1);
        assertThat(partition.subsets()).containsExactly(A);
    }

    @Test
    void shouldKeepRecipeWhenFuzzyIngredientHasNoViableOptions() {
        final Pattern fuzzyPattern = pattern().ingredient(1).input(A).input(B).end().output(C, 1).build();

        final LpFuzzyExpander.FuzzyExpansionResult result = LpFuzzyExpander.expandFuzzyPatterns(
            List.of(fuzzyPattern),
            LpResourceSet.empty(),
            Set.of()
        );

        assertThat(result.expandedRecipes()).hasSize(1);
        final LpPatternRecipe expandedRecipe = result.expandedRecipes().getFirst();
        assertThat(expandedRecipe.output().getAmount(C)).isEqualTo(1);
        assertThat(expandedRecipe.input().resourceKeys()).containsExactly((ResourceKey) A);
        assertThat(expandedRecipe.input().getAmount(A)).isEqualTo(1);
    }
}
