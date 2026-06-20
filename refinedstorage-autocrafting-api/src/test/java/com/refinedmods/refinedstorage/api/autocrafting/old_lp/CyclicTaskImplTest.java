package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.AutocraftingUtil.storage;
import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.A;
import static com.refinedmods.refinedstorage.api.autocrafting.ResourceFixtures.C;
import static org.assertj.core.api.Assertions.assertThat;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.DesanitizedRecipe;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.DesanitizedRecipeApplicationPath;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.DesanitizedRecipeApplicationStep;
import com.refinedmods.refinedstorage.api.autocrafting.lp.task.CyclicTaskImpl;

class CyclicTaskImplTest {
    private static final ExternalPatternSinkProvider EMPTY_SINK_PROVIDER = pattern -> Collections.emptyList();

    @Test
    void shouldUsePeakResourceUsageForCyclicInitialRequirements() {
        final RootStorage storage = storage(new ResourceAmount(A, 1));
        final Pattern rootPattern = pattern()
            .ingredient(A, 2)
            .output(C, 1)
            .build();
        final DesanitizedRecipeApplicationPath path = new DesanitizedRecipeApplicationPath(
            java.util.List.of(new DesanitizedRecipeApplicationStep(
                new DesanitizedRecipe(
                    UUID.randomUUID(),
                    rootPattern.id(),
                    rootPattern.layout()
                ),
                1
            )),
            java.util.Map.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            java.util.List.of(),
            java.util.Map.of(A, 1L),
            true
        );

        final Task task = new CyclicTaskImpl(
            C,
            1,
            Actor.EMPTY,
            true,
            path,
            indexPatterns(Set.of(rootPattern)),
            rootPattern,
            pattern -> true
        );
        storage.addListener(task);

        task.step(storage, EMPTY_SINK_PROVIDER, StepBehavior.DEFAULT, TaskListener.EMPTY);

        assertThat(task.getState()).isEqualTo(TaskState.RUNNING);
        assertThat(storage.getAll()).isEmpty();
        assertThat(copyInternalStorage(task))
            .usingRecursiveFieldByFieldElementComparator()
            .containsExactly(new ResourceAmount(A, 1));
    }

    private static Collection<ResourceAmount> copyInternalStorage(final Task task) {
        return ((TaskImpl) task).createSnapshot().copyInternalStorage().copyState();
    }

    private static Map<UUID, Pattern> indexPatterns(final Set<Pattern> patterns) {
        final Map<UUID, Pattern> result = new java.util.HashMap<>();
        for (final Pattern pattern : patterns) {
            result.put(pattern.id(), pattern);
        }
        return result;
    }
}
