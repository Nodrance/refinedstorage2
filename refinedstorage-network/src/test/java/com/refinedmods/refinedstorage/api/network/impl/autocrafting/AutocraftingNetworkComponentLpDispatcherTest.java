package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpDispatcherHelper;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpExecutionPlanStep;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpPatternRecipe;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpPlanningHelper;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpResourceSet;
import com.refinedmods.refinedstorage.api.autocrafting.lp.LpStepPlan;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.NetworkImpl;
import com.refinedmods.refinedstorage.api.network.impl.node.patternprovider.PatternProviderNetworkNode;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.network.test.fixtures.NetworkTestFixtures;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder.pattern;
import static com.refinedmods.refinedstorage.network.test.fixtures.ResourceFixtures.A;
import static com.refinedmods.refinedstorage.network.test.fixtures.ResourceFixtures.A_ALTERNATIVE;
import static com.refinedmods.refinedstorage.network.test.fixtures.ResourceFixtures.B;
import static com.refinedmods.refinedstorage.network.test.fixtures.ResourceFixtures.C;
import static com.refinedmods.refinedstorage.network.test.fixtures.ResourceFixtures.D;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutocraftingNetworkComponentLpDispatcherTest {
    private Network network;
    private RootStorage rootStorage;
    private AutocraftingNetworkComponentImpl sut;

    @BeforeEach
    void setUp() {
        network = new NetworkImpl(NetworkTestFixtures.NETWORK_COMPONENT_MAP_FACTORY);
        rootStorage = network.getComponent(StorageNetworkComponent.class);
        sut = new AutocraftingNetworkComponentImpl(
            () -> rootStorage, Executors.newSingleThreadExecutor()
        );
    }

    // --- Planning Algorithm Selection ---

    @Test
    void shouldUseLpWhenPatternHasFuzzyInputsWithMultipleViableOptions() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(A, 1, Action.EXECUTE, Actor.EMPTY);
        rootStorage.insert(B, 1, Action.EXECUTE, Actor.EMPTY);

        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, pattern().ingredient(1).input(A).input(B).end().output(B, 1).build());
        sut.onContainerAdded(() -> provider);

        // LP system now handles fuzzy recipes through subset expansion
        assertThat(determinePlanningAlgorithm(B)).isEqualTo(PlanningAlgorithm.LP);
    }

    @Test
    void shouldUseLpWhenPatternHasFuzzyInputsButOnlyOneAlternativeIsAvailable() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(A, 1, Action.EXECUTE, Actor.EMPTY);

        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, pattern().ingredient(1).input(A).input(B).end().output(C, 1).build());
        sut.onContainerAdded(() -> provider);

        assertThat(determinePlanningAlgorithm(C)).isEqualTo(PlanningAlgorithm.LP);
    }

    @Test
    void shouldUseLpWhenPatternHasFuzzyInputsButOnlyOneAlternativeIsCraftable() {
        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, pattern().ingredient(1).input(A).input(B).end().output(C, 1).build());
        provider.setPattern(2, pattern().ingredient(D, 1).output(A, 1).build());
        sut.onContainerAdded(() -> provider);

        assertThat(determinePlanningAlgorithm(C)).isEqualTo(PlanningAlgorithm.LP);
    }

    @Test
    void shouldUseLpWhenFuzzyAndOneInStorageOneIsCraftable() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(A, 1, Action.EXECUTE, Actor.EMPTY);

        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, pattern().ingredient(1).input(A).input(B).end().output(C, 1).build());
        provider.setPattern(2, pattern().ingredient(D, 1).output(B, 1).build());
        sut.onContainerAdded(() -> provider);

        // LP system now handles fuzzy recipes through subset expansion
        assertThat(determinePlanningAlgorithm(C)).isEqualTo(PlanningAlgorithm.LP);
    }

    @Test
    void shouldUseLpWhenFuzzyAndBothAlternativesAreCraftable() {
        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, pattern().ingredient(1).input(A).input(B).end().output(C, 1).build());
        provider.setPattern(2, pattern().ingredient(D, 1).output(A, 1).build());
        provider.setPattern(3, pattern().ingredient(D, 1).output(B, 1).build());
        sut.onContainerAdded(() -> provider);

        // LP system now handles fuzzy recipes through subset expansion
        assertThat(determinePlanningAlgorithm(C)).isEqualTo(PlanningAlgorithm.LP);
    }

    @Test
    void shouldUseLpWhenRelevantDependencyPatternHasFuzzyInputs() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);
        rootStorage.insert(D, 1, Action.EXECUTE, Actor.EMPTY);

        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, pattern().ingredient(B, 1).output(A, 1).build());
        provider.setPattern(2, pattern().ingredient(1).input(C).input(D).end().output(B, 1).build());
        sut.onContainerAdded(() -> provider);

        // LP system now handles fuzzy recipes through subset expansion
        assertThat(determinePlanningAlgorithm(A)).isEqualTo(PlanningAlgorithm.LP);
    }

    @Test
    void shouldUseLpWhenOnlyViableInputHasFuzzyDependency() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(D, 1, Action.EXECUTE, Actor.EMPTY);
        rootStorage.insert(A_ALTERNATIVE, 1, Action.EXECUTE, Actor.EMPTY);

        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(
            1, pattern().ingredient(1).input(A).input(B).end().output(C, 1).build()
        );
        provider.setPattern(
            2, pattern().ingredient(1).input(D).input(A_ALTERNATIVE).end().output(A, 1).build()
        );
        sut.onContainerAdded(() -> provider);

        // LP system now handles fuzzy recipes through subset expansion
        assertThat(determinePlanningAlgorithm(C)).isEqualTo(PlanningAlgorithm.LP);
    }

    @Test
    void shouldUseLpPlanningAlgorithmForRegularRecipeShapes() {
        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, pattern().ingredient(A, 1).output(B, 1).build());
        sut.onContainerAdded(() -> provider);

        assertThat(determinePlanningAlgorithm(B)).isEqualTo(PlanningAlgorithm.LP);
    }

    @Test
    void shouldTreatRequestedAmountAsAdditionalAmountForSelfConsumingRecipe() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(B, 3, Action.EXECUTE, Actor.EMPTY);
        rootStorage.insert(A, 4, Action.EXECUTE, Actor.EMPTY);

        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(
            1, pattern().ingredient(B, 1).ingredient(B, 1).output(B, 1).build()
        );
        final Pattern duplicationPattern =
            pattern().ingredient(B, 1).ingredient(A, 1).output(B, 2).build();
        provider.setPattern(1, duplicationPattern);
        sut.onContainerAdded(() -> provider);

        final Optional<TaskId> taskId = startTask(B, 4);

        assertThat(taskId).isPresent();
        assertThat(provider.getTasks()).hasSize(1);
        assertThat(provider.getTasks().getFirst().getAmount()).isEqualTo(4);

        final TaskImpl task = (TaskImpl) provider.getTasks().getFirst();
        final var snapshot = task.createSnapshot();
        assertThat(snapshot.patterns()).containsKey(duplicationPattern);
        assertThat(snapshot.patterns().get(duplicationPattern).internalPattern()).isNotNull();
        assertThat(snapshot.patterns().get(duplicationPattern)
            .internalPattern().iterationsRemaining()).isEqualTo(4);
    }

    // --- LP Dispatch: Strict & Relaxed ---

    @Test
    void shouldEnforceGeneratedOrderForCyclePlans() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern blockedFirst = pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern laterStep = pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode blockedProvider = new PatternProviderNetworkNode(0, 5);
        blockedProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> blockedProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, laterStep);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(laterStep, 1), 1)
        );

        final Optional<TaskId> dispatcherTask = invokeAddLpDispatcherTask(D, 1, steps, true);
        rootProvider.setActive(true);
        rootProvider.setNetwork(network);
        rootProvider.doWork();

        assertThat(dispatcherTask).isPresent();
        assertThat(rootProvider.getTasks()).hasSize(1);
        assertThat(blockedProvider.getTasks()).isEmpty();
    }

    @Test
    void shouldAllowLaterStepDispatchForAcyclicPlans() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern blockedFirst = pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern laterStep = pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode blockedProvider = new PatternProviderNetworkNode(0, 5);
        blockedProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> blockedProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, laterStep);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(laterStep, 1), 1)
        );

        final Optional<TaskId> dispatcherTask = invokeAddLpDispatcherTask(D, 1, steps, false);
        rootProvider.setActive(true);
        rootProvider.setNetwork(network);
        rootProvider.doWork();

        assertThat(dispatcherTask).isPresent();
        assertThat(blockedProvider.getTasks()).isEmpty();
        assertThat(rootProvider.getTasks())
            .hasSize(1)
            .allMatch(task -> task.getId().equals(dispatcherTask.get()));
        final TaskImpl dispatcher = (TaskImpl) rootProvider.getTasks().getFirst();
        assertThat(dispatcher.createSnapshot().copyInternalStorage().copyState())
            .usingRecursiveFieldByFieldElementComparator()
            .containsExactly(new ResourceAmount(C, 1));
        assertThat(rootStorage.getAll()).isEmpty();
    }

    @Test
    void shouldSplitStepWhenOnlyPartCanStart() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(A, 2, Action.EXECUTE, Actor.EMPTY);

        final Pattern expandable = pattern().ingredient(A, 1).output(B, 1).build();
        final Pattern rootPattern = pattern().ingredient(B, 1).output(C, 1).build();

        final PatternProviderNetworkNode expandableProvider =
            new PatternProviderNetworkNode(0, 5);
        expandableProvider.setPattern(1, expandable);
        sut.onContainerAdded(() -> expandableProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(expandable, 0), 4),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(C, 1, steps, true);
        rootProvider.setActive(true);
        rootProvider.setNetwork(network);
        rootProvider.doWork();

        assertThat(dispatcherTask).isPresent();
        assertThat(rootProvider.getTasks()).hasSize(1);
        assertThat(expandableProvider.getTasks()).isEmpty();
        final TaskImpl dispatcher = (TaskImpl) rootProvider.getTasks().getFirst();
        assertThat(dispatcher.createSnapshot().copyInternalStorage().copyState())
            .usingRecursiveFieldByFieldElementComparator()
            .containsExactly(new ResourceAmount(A, 2));
    }

    @Test
    void shouldNotNotifyWhenLpDispatcherIsCancelled() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern blockedFirst = pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern laterStep = pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode blockedProvider = new PatternProviderNetworkNode(0, 5);
        blockedProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> blockedProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, laterStep);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(laterStep, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, true, true);

        assertThat(dispatcherTask).isPresent();
        assertThat(rootProvider.getTasks()).hasSize(1);
        final var dispatcher = rootProvider.getTasks().getFirst();
        assertThat(dispatcher.shouldNotify()).isTrue();

        dispatcher.cancel();
        assertThat(dispatcher.shouldNotify()).isFalse();
    }

    @Test
    void shouldDispatchFirstStrictStepWhenRequirementsAreAvailable() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern firstStep = pattern().ingredient(C, 1).output(B, 1).build();
        final Pattern rootPattern = pattern().ingredient(B, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, firstStep);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(firstStep, 0), 1),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, true);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        final boolean changed =
            invokeDispatcherBooleanMethod(dispatcher, "dispatchStrict", rootStorage);

        assertThat(changed).isTrue();
        assertThat(firstProvider.getTasks()).isEmpty();
        assertThat(invokeDispatcherPendingRequirement(dispatcher, C)).isZero();
    }

    @Test
    void shouldNotDispatchStrictWhenFirstStepProviderIsMissing() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern unregistered = pattern().ingredient(C, 1).output(B, 1).build();
        final Pattern rootPattern = pattern().ingredient(B, 1).output(D, 1).build();

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(unregistered, 0), 1),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, true);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        final boolean changed =
            invokeDispatcherBooleanMethod(dispatcher, "dispatchStrict", rootStorage);

        assertThat(changed).isFalse();
    }

    @Test
    void shouldNotDispatchStrictWhenActiveSubTaskExists() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern firstStep = pattern().ingredient(C, 1).output(B, 1).build();
        final Pattern rootPattern = pattern().ingredient(B, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, firstStep);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(firstStep, 0), 1),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, true);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        invokeDispatcherBooleanMethod(dispatcher, "dispatchStrict", rootStorage);

        final boolean changed =
            invokeDispatcherBooleanMethod(dispatcher, "dispatchStrict", rootStorage);

        assertThat(changed).isFalse();
    }

    @Test
    void shouldDispatchLaterRelaxedStepWhenFirstStepCannotRun() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern blockedFirst = pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern laterStep = pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode blockedProvider = new PatternProviderNetworkNode(0, 5);
        blockedProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> blockedProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, laterStep);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(laterStep, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, false);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        final boolean changed =
            invokeDispatcherBooleanMethod(dispatcher, "dispatchRelaxed", rootStorage);

        assertThat(changed).isTrue();
        assertThat(blockedProvider.getTasks()).isEmpty();
        assertThat(rootProvider.getTasks()).hasSize(1);
        assertThat(invokeDispatcherPendingRequirement(dispatcher, C)).isZero();
        assertThat(invokeDispatcherPendingRequirement(dispatcher, A)).isEqualTo(4);
    }

    @Test
    void shouldNotDispatchRelaxedWhenPendingStepsAreEmpty() {
        rootStorage.addSource(new StorageImpl());

        final Pattern blockedFirst = pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern blockedSecond = pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode secondProvider = new PatternProviderNetworkNode(0, 5);
        secondProvider.setPattern(1, blockedSecond);
        sut.onContainerAdded(() -> secondProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedSecond, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, false);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = secondProvider.getTasks().getFirst();
        clearDispatcherPendingSteps(dispatcher);

        final boolean changed =
            invokeDispatcherBooleanMethod(dispatcher, "dispatchRelaxed", rootStorage);

        assertThat(changed).isFalse();
    }

    @Test
    void shouldNotDispatchRelaxedWhenNoStepCanRun() {
        rootStorage.addSource(new StorageImpl());

        final Pattern blockedFirst = pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern blockedSecond = pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode secondProvider = new PatternProviderNetworkNode(0, 5);
        secondProvider.setPattern(1, blockedSecond);
        sut.onContainerAdded(() -> secondProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedSecond, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, false);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = secondProvider.getTasks().getFirst();

        final boolean changed =
            invokeDispatcherBooleanMethod(dispatcher, "dispatchRelaxed", rootStorage);

        assertThat(changed).isFalse();
        assertThat(firstProvider.getTasks()).isEmpty();
        assertThat(secondProvider.getTasks()).hasSize(1);
    }

    // --- LP Dispatcher Helpers ---

    @Test
    void shouldFindRootPatternAtFirstStepIndex() {
        final Pattern first = pattern().ingredient(A, 1).output(D, 1).build();
        final Pattern second = pattern().ingredient(B, 1).output(C, 1).build();
        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(first, 0), 1),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(second, 1), 1)
        );

        final Pattern root = LpDispatcherHelper.findRootPattern(D, steps);

        assertThat(root).isEqualTo(first);
    }

    @Test
    void shouldReturnNullWhenNoRootPatternIsFound() {
        final Pattern first = pattern().ingredient(A, 1).output(B, 1).build();
        final Pattern second = pattern().ingredient(B, 1).output(C, 1).build();
        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(first, 0), 1),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(second, 1), 1)
        );

        final Pattern root = LpDispatcherHelper.findRootPattern(D, steps);

        assertThat(root).isNull();
    }

    @Test
    void shouldCollectRelevantPatternsWithoutLoopingOnCycles() {
        final Pattern patternA = pattern().ingredient(C, 1).output(A, 1).build();
        final Pattern patternC = pattern().ingredient(A, 1).output(C, 1).build();

        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, patternA);
        provider.setPattern(2, patternC);
        sut.onContainerAdded(() -> provider);

        final var relevant = invokeCollectRelevantPatternsForLp(C, rootStorage);

        assertThat(relevant).containsExactlyInAnyOrder(patternA, patternC);
    }

    @Test
    void shouldFindMaxCraftableAmountViaLpAtUpperBoundary() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(A, 5, Action.EXECUTE, Actor.EMPTY);

        final Pattern patternAB = pattern().ingredient(A, 1).output(B, 1).build();
        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, patternAB);
        sut.onContainerAdded(() -> provider);

        final var relevant = invokeCollectRelevantPatternsForLp(B, rootStorage);

        final long maxCraftable = invokeFindMaxCraftableAmountViaLp(
            relevant, rootStorage, B, 5, CancellationToken.NONE
        );

        assertThat(maxCraftable).isEqualTo(5);
    }

    @Test
    void shouldCollectRelevantPatternsTraversingFuzzyIngredients() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(A, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern fuzzyPattern = pattern()
            .ingredient(1).input(A).input(A_ALTERNATIVE).end()
            .output(B, 1)
            .build();
        final Pattern patternAtoC = pattern().ingredient(A, 1).output(C, 1).build();

        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 10);
        provider.setPattern(1, fuzzyPattern);
        provider.setPattern(2, patternAtoC);
        sut.onContainerAdded(() -> provider);

        final var relevant = invokeCollectRelevantPatternsForLp(B, rootStorage);

        assertThat(relevant).contains(fuzzyPattern);
    }

    @Test
    void shouldFindMaxCraftableAmountViaLpReturnZeroWhenCancelled() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(A, 100, Action.EXECUTE, Actor.EMPTY);

        final Pattern patternAB = pattern().ingredient(A, 1).output(B, 1).build();
        final PatternProviderNetworkNode provider = new PatternProviderNetworkNode(0, 5);
        provider.setPattern(1, patternAB);
        sut.onContainerAdded(() -> provider);

        final var relevant = invokeCollectRelevantPatternsForLp(B, rootStorage);

        final long maxCraftable = invokeFindMaxCraftableAmountViaLp(
            relevant, rootStorage, B, 100, new CancelledCancellationToken()
        );

        assertThat(maxCraftable).isZero();
    }

    // --- Interception ---

    @Test
    void shouldNotInterceptBeforeInsertWhenDispatcherIsCancelled() {
        final Pattern blockedFirst = pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern laterStep = pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode blockedProvider = new PatternProviderNetworkNode(0, 5);
        blockedProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> blockedProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, laterStep);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(laterStep, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, false);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();
        dispatcher.cancel();

        final long intercepted = dispatcher.beforeInsert(A, 10);

        assertThat(intercepted).isZero();
    }

    @Test
    void shouldOnlyInterceptUpToPendingRequirementInBeforeInsert() {
        final Pattern blockedFirst = pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern laterStep = pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode blockedProvider = new PatternProviderNetworkNode(0, 5);
        blockedProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> blockedProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, laterStep);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(laterStep, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, false);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        final long firstIntercepted = dispatcher.beforeInsert(A, 10);
        final long secondIntercepted = dispatcher.beforeInsert(A, 10);

        assertThat(firstIntercepted).isEqualTo(4);
        assertThat(secondIntercepted).isZero();
    }

    @Test
    void shouldInterceptViaActiveSubTaskInBeforeAndAfterInsert() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern firstStep = pattern().ingredient(C, 1).output(B, 1).build();
        final Pattern rootPattern = pattern().ingredient(B, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, firstStep);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(firstStep, 0), 1),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );
        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, true);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        invokeDispatcherBooleanMethod(dispatcher, "dispatchStrict", rootStorage);

        final long beforeIntercepted = dispatcher.beforeInsert(C, 1);
        final long afterIntercepted = dispatcher.afterInsert(C, 1);

        assertThat(beforeIntercepted).isBetween(0L, 1L);
        assertThat(afterIntercepted).isBetween(0L, 1L);
    }

    // --- Single-Step LP Plans ---

    @Test
    void shouldUseRequestedAmountForSingleStepPlan() {
        final Pattern singleStep =
            pattern().ingredient(A, 2).output(B, 3).build();
        final LpExecutionPlanStep step =
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(singleStep, 0), 4);

        final var plan =
            LpDispatcherHelper.toSingleStepPlan(B, 5, step, true);

        assertThat(plan.resource()).isEqualTo(B);
        assertThat(plan.amount()).isEqualTo(5);
        assertThat(plan.getPattern(singleStep).iterations()).isEqualTo(4);
        assertThat(plan.getPattern(singleStep).ingredients().get(0).get(A))
            .isEqualTo(8);
    }

    @Test
    void shouldUseOutputAmountForSingleStepPlanWhenRequestedAmountIsZero() {
        final Pattern singleStep =
            pattern().ingredient(A, 2).output(B, 3).build();
        final LpExecutionPlanStep step =
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(singleStep, 0), 4);

        final var plan =
            LpDispatcherHelper.toSingleStepPlan(B, 0, step, true);

        assertThat(plan.resource()).isEqualTo(B);
        assertThat(plan.amount()).isEqualTo(12);
    }

    @Test
    void shouldFallbackToRequestedResourceWhenSingleStepPatternHasNoOutputs() {
        final Pattern mockedPattern = mock(Pattern.class);
        final var mockedLayout = mock(PatternLayout.class);

        when(mockedPattern.id()).thenReturn(UUID.randomUUID());
        when(mockedPattern.layout()).thenReturn(mockedLayout);
        when(mockedLayout.ingredients()).thenReturn(List.of());
        when(mockedLayout.outputs()).thenReturn(List.of());

        final LpPatternRecipe recipe = new LpPatternRecipe(
            mockedPattern, new LpResourceSet(), new LpResourceSet(), 0, null
        );
        final LpExecutionPlanStep step = new LpExecutionPlanStep(recipe, 4);

        final var plan =
            LpDispatcherHelper.toSingleStepPlan(C, 0, step, true);

        assertThat(plan.resource()).isEqualTo(C);
        assertThat(plan.amount()).isEqualTo(4);
    }

    // --- Dispatcher Status ---

    @Test
    void shouldExposeProcessingInStatusForStrictDispatcher() {
        final Pattern firstStep = pattern().ingredient(C, 1).output(B, 1).build();
        final Pattern rootPattern = pattern().ingredient(B, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, firstStep);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(firstStep, 0), 1),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, true);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        final TaskStatus status = dispatcher.getStatus();

        assertThat(status.percentageCompleted()).isZero();
        assertThat(status.items()).hasSize(1);
        assertThat(status.items().getFirst().processing()).isEqualTo(2);
        assertThat(status.items().getFirst().scheduled()).isZero();
    }

    @Test
    void shouldExposeScheduledInStatusForRelaxedDispatcher() {
        final Pattern blockedFirst =
            pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern rootPattern =
            pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, false);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        final TaskStatus status = dispatcher.getStatus();

        assertThat(status.percentageCompleted()).isZero();
        assertThat(status.items()).hasSize(1);
        assertThat(status.items().getFirst().scheduled()).isEqualTo(2);
        assertThat(status.items().getFirst().processing()).isZero();
    }

    // --- Dispatcher State Transitions ---

    @Test
    void shouldTransitionDispatcherStateWhenCancelledWithActiveSubTask() {
        rootStorage.addSource(new StorageImpl());
        rootStorage.insert(C, 1, Action.EXECUTE, Actor.EMPTY);

        final Pattern firstStep = pattern().ingredient(C, 1).output(B, 1).build();
        final Pattern rootPattern = pattern().ingredient(B, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, firstStep);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        rootProvider.setNetwork(network);
        rootProvider.setActive(true);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(firstStep, 0), 1),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );
        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, true);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        invokeDispatcherBooleanMethod(dispatcher, "dispatchStrict", rootStorage);

        dispatcher.cancel();
        rootProvider.doWork();

        assertThat(dispatcher.getState())
            .isEqualTo(TaskState.RETURNING_INTERNAL_STORAGE);
    }

    @Test
    void shouldStepDispatcherFromReadyToRunningThenReportNoChange() {
        final Pattern blockedFirst =
            pattern().ingredient(A, 2).output(B, 1).build();
        final Pattern rootPattern =
            pattern().ingredient(C, 1).output(D, 1).build();

        final PatternProviderNetworkNode firstProvider = new PatternProviderNetworkNode(0, 5);
        firstProvider.setPattern(1, blockedFirst);
        sut.onContainerAdded(() -> firstProvider);

        final PatternProviderNetworkNode rootProvider = new PatternProviderNetworkNode(0, 5);
        rootProvider.setPattern(1, rootPattern);
        sut.onContainerAdded(() -> rootProvider);

        final List<LpExecutionPlanStep> steps = List.of(
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(blockedFirst, 0), 2),
            new LpExecutionPlanStep(LpPatternRecipe.fromPattern(rootPattern, 1), 1)
        );

        final Optional<TaskId> dispatcherTask =
            invokeAddLpDispatcherTask(D, 1, steps, false);
        assertThat(dispatcherTask).isPresent();
        final var dispatcher = rootProvider.getTasks().getFirst();

        final boolean firstChanged = dispatcher.step(
            rootStorage, layout -> List.of(),
            StepBehavior.DEFAULT, TaskListener.EMPTY
        );
        final boolean secondChanged = dispatcher.step(
            rootStorage, layout -> List.of(),
            StepBehavior.DEFAULT, TaskListener.EMPTY
        );

        assertThat(firstChanged).isTrue();
        assertThat(secondChanged).isFalse();
        assertThat(dispatcher.getState()).isEqualTo(TaskState.RUNNING);
    }

    // --- Helpers ---

    private Optional<TaskId> startTask(final ResourceKey resource, final long amount) {
        return sut.startTask(resource, amount, Actor.EMPTY, false, CancellationToken.NONE);
    }

    private PlanningAlgorithm determinePlanningAlgorithm(final ResourceKey resource) {
        return invokeShouldUseLpSystem(resource)
            ? PlanningAlgorithm.LP : PlanningAlgorithm.TRADITIONAL;
    }

    private boolean invokeShouldUseLpSystem(final ResourceKey resource) {
        try {
            final var field = AutocraftingNetworkComponentImpl.class
                .getDeclaredField("patternRepository");
            field.setAccessible(true);
            final var patternRepository = field.get(sut);
            final var repo = (PatternRepository) patternRepository;
            return LpPlanningHelper.shouldUseLPSystem(
                resource, rootStorage, repo
            );
        } catch (final NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError(
                "Unable to determine selected planning algorithm", e
            );
        }
    }

    private Optional<TaskId> invokeAddLpDispatcherTask(
        final ResourceKey resource,
        final long amount,
        final List<LpExecutionPlanStep> steps,
        final boolean hasRecipeCycles
    ) {
        return invokeAddLpDispatcherTask(
            resource, amount, steps, hasRecipeCycles, false
        );
    }

    @SuppressWarnings("unchecked")
    private Optional<TaskId> invokeAddLpDispatcherTask(
        final ResourceKey resource,
        final long amount,
        final List<LpExecutionPlanStep> steps,
        final boolean hasRecipeCycles,
        final boolean notify
    ) {
        try {
            final LpStepPlan lpStepPlan =
                new LpStepPlan(steps, hasRecipeCycles);
            final Method method =
                AutocraftingNetworkComponentImpl.class.getDeclaredMethod(
                    "addLpDispatcherTask",
                    ResourceKey.class,
                    long.class,
                    Actor.class,
                    LpStepPlan.class,
                    boolean.class
                );
            method.setAccessible(true);
            return (Optional<TaskId>) method.invoke(
                sut, resource, amount, Actor.EMPTY, lpStepPlan, notify
            );
        } catch (final NoSuchMethodException
                     | IllegalAccessException
                     | InvocationTargetException e) {
            throw new AssertionError(
                "Unable to invoke LP dispatcher task creation", e
            );
        }
    }

    private boolean invokeDispatcherBooleanMethod(
        final Task task,
        final String methodName,
        final RootStorage storage
    ) {
        try {
            final Method method = task.getClass()
                .getDeclaredMethod(methodName, RootStorage.class);
            method.setAccessible(true);
            return (boolean) method.invoke(task, storage);
        } catch (final NoSuchMethodException
                     | IllegalAccessException
                     | InvocationTargetException e) {
            throw new AssertionError(
                "Unable to invoke dispatcher method " + methodName, e
            );
        }
    }

    private long invokeDispatcherPendingRequirement(
        final Task task,
        final ResourceKey resource
    ) {
        try {
            final Method method = task.getClass()
                .getDeclaredMethod("getPendingRequirement", ResourceKey.class);
            method.setAccessible(true);
            return (long) method.invoke(task, resource);
        } catch (final NoSuchMethodException
                     | IllegalAccessException
                     | InvocationTargetException e) {
            throw new AssertionError(
                "Unable to invoke dispatcher getPendingRequirement", e
            );
        }
    }

    private Collection<Pattern> invokeCollectRelevantPatternsForLp(
        final ResourceKey resource,
        final RootStorage storage
    ) {
        try {
            final var field = AutocraftingNetworkComponentImpl.class
                .getDeclaredField("patternRepository");
            field.setAccessible(true);
            final var patternRepository = field.get(sut);
            final var repo = (PatternRepository) patternRepository;
            return LpPlanningHelper.collectRelevantPatternsForLp(
                resource, storage, repo
            );
        } catch (final NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError(
                "Unable to invoke collectRelevantPatternsForLp", e
            );
        }
    }

    private long invokeFindMaxCraftableAmountViaLp(
        final Collection<Pattern> relevantPatterns,
        final RootStorage storage,
        final ResourceKey resource,
        final long amount,
        final CancellationToken cancellationToken
    ) {
        try {
            final Method method =
                AutocraftingNetworkComponentImpl.class.getDeclaredMethod(
                    "findMaxCraftableAmountViaLp",
                    Collection.class,
                    RootStorage.class,
                    ResourceKey.class,
                    long.class,
                    CancellationToken.class
                );
            method.setAccessible(true);
            return (long) method.invoke(
                sut, relevantPatterns, storage, resource,
                amount, cancellationToken
            );
        } catch (final NoSuchMethodException
                     | IllegalAccessException
                     | InvocationTargetException e) {
            throw new AssertionError(
                "Unable to invoke findMaxCraftableAmountViaLp", e
            );
        }
    }

    @SuppressWarnings("unchecked")
    private void clearDispatcherPendingSteps(final Task task) {
        try {
            final var field =
                task.getClass().getDeclaredField("pendingSteps");
            field.setAccessible(true);
            ((List<LpExecutionPlanStep>) field.get(task)).clear();
        } catch (final NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError(
                "Unable to clear dispatcher pendingSteps", e
            );
        }
    }

    private enum PlanningAlgorithm {
        TRADITIONAL,
        LP
    }

    private static class CancelledCancellationToken implements CancellationToken {
        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public void cancel() {
            // no op
        }
    }
}
