package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.LinearSolver;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.MultiResourceKey;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.ResourcePool;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.SanitizedRecipe;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_LOG;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.OAK_PLANKS;
import static org.assertj.core.api.Assertions.assertThat;

class LinearSolverLexicographicTest {
    @Test
    void shouldPreferHigherPriorityRecipeWhenOutputsAreEquivalent() {
        final SanitizedRecipe lowPriorityRecipe = recipe(
            pool(OAK_LOG, 1),
            pool(OAK_PLANKS, 4),
            0,
            0
        );
        final SanitizedRecipe highPriorityRecipe = recipe(
            pool(OAK_LOG, 1),
            pool(OAK_PLANKS, 4),
            100,
            1
        );

        final LinearSolver.Result result = solver(
            List.of(lowPriorityRecipe, highPriorityRecipe),
            Set.of(),
            pool(OAK_LOG, 1),
            pool(OAK_PLANKS, 4)
        ).lexicographicMinimum();

        assertThat(result).isNotNull();
        assertThat(result.recipeValues()).containsEntry(highPriorityRecipe.recipeId(), 1L);
        assertThat(result.recipeValues()).doesNotContainKey(lowPriorityRecipe.recipeId());
    }

    @Test
    void shouldHonorDisabledRecipesInLexicographicMinimum() {
        final SanitizedRecipe lowPriorityRecipe = recipe(
            pool(OAK_LOG, 1),
            pool(OAK_PLANKS, 4),
            0,
            0
        );
        final SanitizedRecipe highPriorityRecipe = recipe(
            pool(OAK_LOG, 1),
            pool(OAK_PLANKS, 4),
            100,
            1
        );

        final LinearSolver.Result result = solver(
            List.of(lowPriorityRecipe, highPriorityRecipe),
            Set.of(highPriorityRecipe.recipeId()),
            pool(OAK_LOG, 1),
            pool(OAK_PLANKS, 4)
        ).lexicographicMinimum();

        assertThat(result).isNotNull();
        assertThat(result.recipeValues()).containsEntry(lowPriorityRecipe.recipeId(), 1L);
        assertThat(result.recipeValues()).doesNotContainKey(highPriorityRecipe.recipeId());
    }

    private static LinearSolver solver(
        final List<SanitizedRecipe> recipes,
        final Set<UUID> disabledRecipeIds,
        final ResourcePool startingResources,
        final ResourcePool target
    ) {
        final Set<MultiResourceKey> relevantResources = new LinkedHashSet<>();
        relevantResources.addAll(startingResources.resourceKeys());
        relevantResources.addAll(target.resourceKeys());
        for (final SanitizedRecipe recipe : recipes) {
            relevantResources.addAll(recipe.input().resourceKeys());
            relevantResources.addAll(recipe.output().resourceKeys());
        }

        return new LinearSolver(
            recipes,
            relevantResources,
            startingResources,
            target,
            Set.of(),
            disabledRecipeIds,
            LinearSolver.Options.defaults(),
            CancellationToken.NONE
        );
    }

    private static SanitizedRecipe recipe(
        final ResourcePool input,
        final ResourcePool output,
        final long priority,
        final long insertionOrder
    ) {
        return new SanitizedRecipe(
            UUID.randomUUID(),
            UUID.randomUUID(),
            input,
            output,
            priority,
            insertionOrder
        );
    }

    private static ResourcePool pool(final ResourceKey resource, final long amount) {
        final ResourcePool pool = ResourcePool.empty();
        pool.setAmount(key(resource), amount);
        return pool;
    }

    private static MultiResourceKey key(final ResourceKey resource) {
        return new MultiResourceKey(List.of(resource));
    }
}
