package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepository;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.CraftingProblem;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.CraftingSolution;
import com.refinedmods.refinedstorage.api.autocrafting.lp.calculation.CraftingSolver;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.DesanitizedRecipeApplicationPath;
import com.refinedmods.refinedstorage.api.autocrafting.lp.desanitization.RecipeDesanitizer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.preview.PreviewCalculator;
import com.refinedmods.refinedstorage.api.autocrafting.lp.sanitization.CraftingInitializer;
import com.refinedmods.refinedstorage.api.autocrafting.lp.task.TaskDispatcher;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

class LpPerformanceScenariosTest {
    private static final CancellationToken NO_CANCEL = CancellationToken.NONE;
    private static final ExternalPatternSinkProvider EMPTY_SINK_PROVIDER = patternLayout -> List.of();
    private static final StepBehavior FAST_STEP_BEHAVIOR = new StepBehavior() {
        @Override
        public int getSteps(final Pattern pattern) {
            return 2048;
        }
    };
    private static final int MAX_TASK_TICKS = 12_000;

    @Test
    @Timeout(90)
    void shouldBenchmarkStartToPreviewCodePathForBothPreviewKindsOnLargeAcyclicTree() {
        final Scenario success = buildLargeAcyclicTreeScenario("acyclic-success", 7, 120, 1.0D);
        final Scenario missing = buildLargeAcyclicTreeScenario("acyclic-missing", 7, 120, 0.35D);

        final FullPreviewBenchmark successBenchmark = benchmarkPreviewEndToEnd(success);
        final FullPreviewBenchmark missingBenchmark = benchmarkPreviewEndToEnd(missing);

        assertThat(successBenchmark.preview().type()).isEqualTo(PreviewType.SUCCESS);
        assertThat(successBenchmark.treePreview().type()).isEqualTo(PreviewType.SUCCESS);

        assertThat(missingBenchmark.preview().type()).isEqualTo(PreviewType.MISSING_RESOURCES);
        assertThat(missingBenchmark.treePreview().type()).isEqualTo(PreviewType.MISSING_RESOURCES);

        printPreviewBenchmark(successBenchmark);
        printPreviewBenchmark(missingBenchmark);
    }

    @Test
    @Timeout(120)
    void shouldBenchmarkStartToPreviewCodePathForExtremeCyclicScenarios() {
        final List<Supplier<Scenario>> scenarios = List.of(
            () -> buildLargeCyclicRingScenario("cyclic-ring-large", 30, 700),
            () -> buildManySmallCyclesScenario("cyclic-many-small", 10, 70),
            () -> buildOverlappingCyclesScenario("cyclic-overlapping", 8, 55)
        );

        for (final Supplier<Scenario> supplier : scenarios) {
            final FullPreviewBenchmark benchmark = benchmarkPreviewEndToEnd(supplier.get());
            assertThat(benchmark.path()).isPresent();
            assertThat(benchmark.path().get().hasCycles()).isTrue();
            assertThat(benchmark.preview().type()).isIn(PreviewType.SUCCESS, PreviewType.MISSING_RESOURCES);
            assertThat(benchmark.treePreview().type()).isIn(PreviewType.SUCCESS, PreviewType.MISSING_RESOURCES);
            printPreviewBenchmark(benchmark);
        }
    }

    @Test
    @Timeout(90)
    void shouldBenchmarkLargeNumberCraftingScenario() {
        final Scenario largeNumbers = buildLargeNumberChainScenario("large-number-chain", 20, 50_000);
        final FullPreviewBenchmark benchmark = benchmarkPreviewEndToEnd(largeNumbers);

        assertThat(benchmark.preview().type()).isEqualTo(PreviewType.SUCCESS);
        assertThat(benchmark.treePreview().type()).isEqualTo(PreviewType.SUCCESS);

        printPreviewBenchmark(benchmark);
    }

    @Test
    @Timeout(90)
    void shouldBenchmarkStartToExecutionCompletionCodePathForCyclicTaskImpl() {
        final List<Supplier<Scenario>> scenarios = List.of(
            () -> buildLargeCyclicRingScenario("exec-cyclic-ring", 34, 800),
            () -> buildManySmallCyclesScenario("exec-many-small-cycles", 10, 90),
            () -> buildOverlappingCyclesScenario("exec-overlapping-cycles", 9, 70)
        );

        for (final Supplier<Scenario> supplier : scenarios) {
            final Scenario scenario = supplier.get();
            final TaskExecutionBenchmark benchmark = benchmarkCyclicTaskExecutionEndToEnd(scenario);
            assertThat(benchmark.taskState()).isEqualTo(TaskState.COMPLETED);
            assertThat(benchmark.ticks()).isLessThan(MAX_TASK_TICKS);
            printTaskBenchmark(benchmark);
        }
    }

    @Test
    @Timeout(120)
    void shouldBenchmarkIndividualSectionsToSpotBottlenecks() {
        final List<Supplier<Scenario>> scenarios = List.of(
            () -> buildLargeAcyclicTreeScenario("sections-acyclic", 6, 80, 1.0D),
            () -> buildLargeCyclicRingScenario("sections-cyclic-ring", 20, 300),
            () -> buildManySmallCyclesScenario("sections-many-small-cycles", 6, 50),
            () -> buildOverlappingCyclesScenario("sections-overlapping-cycles", 5, 40)
        );

        for (final Supplier<Scenario> supplier : scenarios) {
            final Scenario scenario = supplier.get();
            final SectionTiming timing = benchmarkSections(scenario);

            assertThat(timing.initializationNanos()).isPositive();
            assertThat(timing.solveNanos()).isPositive();
            assertThat(timing.previewNanos()).isPositive();
            assertThat(timing.treePreviewNanos()).isPositive();

            printSectionTiming(timing);
        }
    }

    private static FullPreviewBenchmark benchmarkPreviewEndToEnd(final Scenario scenario) {
        final long startNanos = System.nanoTime();
        final CraftingOrchestrator.SolveAndPreviewResult previewResult = CraftingOrchestrator.solveAndCalculatePreview(
            scenario.storage(),
            scenario.patternRepository(),
            scenario.targetResource(),
            scenario.requestedAmount(),
            NO_CANCEL
        );
        final long afterPreviewNanos = System.nanoTime();

        final CraftingOrchestrator.SolveAndTreePreviewResult treeResult = CraftingOrchestrator.solveAndCalculateTreePreview(
            scenario.storage(),
            scenario.patternRepository(),
            scenario.targetResource(),
            scenario.requestedAmount(),
            NO_CANCEL
        );
        final long endNanos = System.nanoTime();

        return new FullPreviewBenchmark(
            scenario.name(),
            previewResult.recipeApplicationPath(),
            previewResult.previewResult(),
            treeResult.previewResult(),
            afterPreviewNanos - startNanos,
            endNanos - afterPreviewNanos,
            endNanos - startNanos
        );
    }

    private static TaskExecutionBenchmark benchmarkCyclicTaskExecutionEndToEnd(final Scenario scenario) {
        final long solveStartNanos = System.nanoTime();
        final Optional<DesanitizedRecipeApplicationPath> path = CraftingOrchestrator.solveToStepPlan(
            scenario.storage(),
            scenario.patternRepository(),
            scenario.targetResource(),
            scenario.requestedAmount(),
            NO_CANCEL
        );
        final long solveNanos = System.nanoTime() - solveStartNanos;

        assertThat(path).isPresent();
        assertThat(path.get().hasCycles()).isTrue();

        final AtomicReference<Task> submittedTask = new AtomicReference<>();
        final long dispatchStartNanos = System.nanoTime();
        final Optional<TaskId> taskId = TaskDispatcher.addTask(
            scenario.targetResource(),
            scenario.requestedAmount(),
            Actor.EMPTY,
            path.get(),
            scenario.patternRepository().getAll(),
            true,
            (actor, plan, notify) -> TaskId.create(),
            (pattern, task) -> {
                submittedTask.set(task);
                return true;
            },
            ignored -> true
        );
        final long dispatchNanos = System.nanoTime() - dispatchStartNanos;

        assertThat(taskId).isPresent();
        final Task task = submittedTask.get();
        assertThat(task).isNotNull();

        scenario.storage().addListener(task);

        final long executeStartNanos = System.nanoTime();
        int ticks = 0;
        while (task.getState() != TaskState.COMPLETED && ticks < MAX_TASK_TICKS) {
            task.step(scenario.storage(), EMPTY_SINK_PROVIDER, FAST_STEP_BEHAVIOR, TaskListener.EMPTY);
            ticks++;
        }
        final long executeNanos = System.nanoTime() - executeStartNanos;

        return new TaskExecutionBenchmark(
            scenario.name(),
            task.getState(),
            ticks,
            solveNanos,
            dispatchNanos,
            executeNanos,
            solveNanos + dispatchNanos + executeNanos
        );
    }

    private static SectionTiming benchmarkSections(final Scenario scenario) {
        final long initStartNanos = System.nanoTime();
        final CraftingProblem problem = CraftingInitializer.initialize(
            scenario.storage(),
            scenario.patternRepository(),
            scenario.targetResource(),
            scenario.requestedAmount(),
            NO_CANCEL
        );
        final long initializationNanos = System.nanoTime() - initStartNanos;

        final long solveStartNanos = System.nanoTime();
        final Optional<CraftingSolution> solution = new CraftingSolver().solve(problem);
        final long solveNanos = System.nanoTime() - solveStartNanos;

        final long desanitizeStartNanos = System.nanoTime();
        final Optional<DesanitizedRecipeApplicationPath> path = solution.map(found ->
            RecipeDesanitizer.decodeCraftingSolutionToDesanitized(
                found,
                problem.sanitizedStartingResources(),
                NO_CANCEL
            )
        );
        final long desanitizeNanos = System.nanoTime() - desanitizeStartNanos;

        final long previewStartNanos = System.nanoTime();
        final Preview preview = path.map(value -> PreviewCalculator.calculatePreview(
            value,
            java.util.Set.of(scenario.targetResource()),
            NO_CANCEL
        )).orElse(new Preview(PreviewType.NOT_AVAILABLE, List.of(), List.of()));
        final long previewNanos = System.nanoTime() - previewStartNanos;

        final long treePreviewStartNanos = System.nanoTime();
        final TreePreview treePreview = PreviewCalculator.calculateTreePreview(
            scenario.targetResource(),
            scenario.requestedAmount(),
            scenario.storage(),
            problem,
            path
        );
        final long treePreviewNanos = System.nanoTime() - treePreviewStartNanos;

        return new SectionTiming(
            scenario.name(),
            path.isPresent(),
            preview.type(),
            treePreview.type(),
            initializationNanos,
            solveNanos,
            desanitizeNanos,
            previewNanos,
            treePreviewNanos,
            initializationNanos + solveNanos + desanitizeNanos + previewNanos + treePreviewNanos
        );
    }

    private static Scenario buildLargeAcyclicTreeScenario(
        final String name,
        final int depth,
        final long requestedAmount,
        final double stockRatio
    ) {
        final List<List<ResourceKey>> levels = new ArrayList<>();
        for (int level = 0; level <= depth; level++) {
            final int width = 1 << level;
            final List<ResourceKey> resources = new ArrayList<>(width);
            for (int index = 0; index < width; index++) {
                resources.add(new SyntheticResourceKey(name + "-L" + level + "-N" + index));
            }
            levels.add(List.copyOf(resources));
        }

        final PatternRepository repository = new PatternRepositoryImpl();
        for (int level = 0; level < depth; level++) {
            final List<ResourceKey> current = levels.get(level);
            final List<ResourceKey> next = levels.get(level + 1);
            for (int index = 0; index < current.size(); index++) {
                final ResourceKey output = current.get(index);
                final ResourceKey left = next.get(index * 2);
                final ResourceKey right = next.get(index * 2 + 1);
                repository.add(PatternBuilder.pattern()
                    .ingredient(left, 1)
                    .ingredient(right, 1)
                    .output(output, 1)
                    .build(), 0);
            }
        }

        final RootStorage storage = emptyStorage();
        final long leafStock = Math.max(1L, Math.round(requestedAmount * stockRatio));
        for (final ResourceKey leaf : levels.get(depth)) {
            storage.insert(leaf, leafStock, Action.EXECUTE, Actor.EMPTY);
        }

        return new Scenario(name, storage, repository, levels.getFirst().getFirst(), requestedAmount);
    }

    private static Scenario buildLargeCyclicRingScenario(
        final String name,
        final int ringSize,
        final long requestedAmount
    ) {
        final List<ResourceKey> ring = new ArrayList<>(ringSize);
        for (int index = 0; index < ringSize; index++) {
            ring.add(new SyntheticResourceKey(name + "-R" + index));
        }
        final ResourceKey target = new SyntheticResourceKey(name + "-TARGET");

        final PatternRepository repository = new PatternRepositoryImpl();
        for (int index = 0; index < ringSize; index++) {
            final ResourceKey input = ring.get(index);
            final ResourceKey output = ring.get((index + 1) % ringSize);
            repository.add(PatternBuilder.pattern()
                .ingredient(input, 1)
                .output(output, 2)
                .build(), 0);
        }
        repository.add(PatternBuilder.pattern()
            .ingredient(ring.getFirst(), 1)
            .output(target, 1)
            .build(), 0);

        final RootStorage storage = emptyStorage();
        storage.insert(ring.getFirst(), 2, Action.EXECUTE, Actor.EMPTY);

        return new Scenario(name, storage, repository, target, requestedAmount);
    }

    private static Scenario buildManySmallCyclesScenario(
        final String name,
        final int cycleCount,
        final long requestedAmount
    ) {
        final PatternRepository repository = new PatternRepositoryImpl();
        final List<ResourceKey> cycleOutputs = new ArrayList<>(cycleCount);
        final RootStorage storage = emptyStorage();

        for (int index = 0; index < cycleCount; index++) {
            final ResourceKey a = new SyntheticResourceKey(name + "-A" + index);
            final ResourceKey b = new SyntheticResourceKey(name + "-B" + index);
            final ResourceKey out = new SyntheticResourceKey(name + "-O" + index);

            repository.add(PatternBuilder.pattern()
                .ingredient(a, 1)
                .output(b, 2)
                .build(), 0);
            repository.add(PatternBuilder.pattern()
                .ingredient(b, 1)
                .output(a, 2)
                .build(), 0);
            repository.add(PatternBuilder.pattern()
                .ingredient(a, 1)
                .output(out, 1)
                .build(), 0);

            storage.insert(a, 1, Action.EXECUTE, Actor.EMPTY);
            cycleOutputs.add(out);
        }

        final ResourceKey target = new SyntheticResourceKey(name + "-TARGET");
        final PatternBuilder rootBuilder = PatternBuilder.pattern();
        for (final ResourceKey cycleOutput : cycleOutputs) {
            rootBuilder.ingredient(cycleOutput, 1);
        }
        rootBuilder.output(target, 1);
        repository.add(rootBuilder.build(), 0);

        return new Scenario(name, storage, repository, target, requestedAmount);
    }

    private static Scenario buildOverlappingCyclesScenario(
        final String name,
        final int clusterCount,
        final long requestedAmount
    ) {
        final PatternRepository repository = new PatternRepositoryImpl();
        final RootStorage storage = emptyStorage();
        final List<ResourceKey> clusterOutputs = new ArrayList<>(clusterCount);

        for (int index = 0; index < clusterCount; index++) {
            final ResourceKey a = new SyntheticResourceKey(name + "-A" + index);
            final ResourceKey b = new SyntheticResourceKey(name + "-B" + index);
            final ResourceKey c = new SyntheticResourceKey(name + "-C" + index);
            final ResourceKey d = new SyntheticResourceKey(name + "-D" + index);
            final ResourceKey p = new SyntheticResourceKey(name + "-P" + index);

            repository.add(PatternBuilder.pattern()
                .ingredient(a, 1)
                .output(b, 2)
                .build(), 0);
            repository.add(PatternBuilder.pattern()
                .ingredient(b, 1)
                .output(c, 2)
                .build(), 0);
            repository.add(PatternBuilder.pattern()
                .ingredient(c, 1)
                .output(a, 2)
                .build(), 0);

            repository.add(PatternBuilder.pattern()
                .ingredient(a, 1)
                .output(d, 2)
                .build(), 0);
            repository.add(PatternBuilder.pattern()
                .ingredient(d, 1)
                .output(c, 2)
                .build(), 0);

            repository.add(PatternBuilder.pattern()
                .ingredient(c, 1)
                .output(p, 1)
                .build(), 0);

            storage.insert(a, 1, Action.EXECUTE, Actor.EMPTY);
            clusterOutputs.add(p);
        }

        final ResourceKey target = new SyntheticResourceKey(name + "-TARGET");
        final PatternBuilder rootBuilder = PatternBuilder.pattern();
        for (final ResourceKey output : clusterOutputs) {
            rootBuilder.ingredient(output, 1);
        }
        rootBuilder.output(target, 1);
        repository.add(rootBuilder.build(), 0);

        return new Scenario(name, storage, repository, target, requestedAmount);
    }

    private static Scenario buildLargeNumberChainScenario(
        final String name,
        final int depth,
        final long requestedAmount
    ) {
        final List<ResourceKey> chain = new ArrayList<>(depth + 1);
        for (int index = 0; index <= depth; index++) {
            chain.add(new SyntheticResourceKey(name + "-R" + index));
        }

        final PatternRepository repository = new PatternRepositoryImpl();
        for (int index = 0; index < depth; index++) {
            final ResourceKey output = chain.get(index);
            final ResourceKey input = chain.get(index + 1);
            repository.add(PatternBuilder.pattern()
                .ingredient(input, 1)
                .output(output, 2)
                .build(), 0);
        }

        final RootStorage storage = emptyStorage();
        storage.insert(chain.get(depth), requestedAmount, Action.EXECUTE, Actor.EMPTY);

        return new Scenario(name, storage, repository, chain.getFirst(), requestedAmount);
    }

    private static RootStorage emptyStorage() {
        final RootStorage storage = new RootStorageImpl();
        storage.addSource(new StorageImpl());
        return storage;
    }

    private static void printPreviewBenchmark(final FullPreviewBenchmark benchmark) {
        System.out.printf(
            "[LP-PERF][preview] scenario=%s solveAndCalculatePreview=%s solveAndCalculateTreePreview=%s total=%s"
                + " previewType=%s treeType=%s hasPath=%s%n",
            benchmark.scenarioName(),
            toMillis(benchmark.solveAndPreviewNanos()),
            toMillis(benchmark.solveAndTreePreviewNanos()),
            toMillis(benchmark.totalNanos()),
            benchmark.preview().type(),
            benchmark.treePreview().type(),
            benchmark.path().isPresent()
        );
        LpPerformanceHistoryRecorder.recordPreview(
            benchmark.scenarioName(),
            "solveAndCalculatePreview",
            benchmark.solveAndPreviewNanos(),
            benchmark.preview().type(),
            benchmark.treePreview().type(),
            benchmark.path().isPresent()
        );
        LpPerformanceHistoryRecorder.recordPreview(
            benchmark.scenarioName(),
            "solveAndCalculateTreePreview",
            benchmark.solveAndTreePreviewNanos(),
            benchmark.preview().type(),
            benchmark.treePreview().type(),
            benchmark.path().isPresent()
        );
        LpPerformanceHistoryRecorder.recordPreview(
            benchmark.scenarioName(),
            "total",
            benchmark.totalNanos(),
            benchmark.preview().type(),
            benchmark.treePreview().type(),
            benchmark.path().isPresent()
        );
        System.out.printf(
            "[LP-PERF][history] Appended rows to %s%n",
            LpPerformanceHistoryRecorder.outputPath()
        );
    }

    private static void printTaskBenchmark(final TaskExecutionBenchmark benchmark) {
        System.out.printf(
            "[LP-PERF][execution] scenario=%s solve=%s dispatch=%s execute=%s total=%s ticks=%d state=%s%n",
            benchmark.scenarioName(),
            toMillis(benchmark.solveNanos()),
            toMillis(benchmark.dispatchNanos()),
            toMillis(benchmark.executeNanos()),
            toMillis(benchmark.totalNanos()),
            benchmark.ticks(),
            benchmark.taskState()
        );
        LpPerformanceHistoryRecorder.recordExecution(
            benchmark.scenarioName(),
            "solve",
            benchmark.solveNanos(),
            benchmark.ticks(),
            benchmark.taskState()
        );
        LpPerformanceHistoryRecorder.recordExecution(
            benchmark.scenarioName(),
            "dispatch",
            benchmark.dispatchNanos(),
            benchmark.ticks(),
            benchmark.taskState()
        );
        LpPerformanceHistoryRecorder.recordExecution(
            benchmark.scenarioName(),
            "execute",
            benchmark.executeNanos(),
            benchmark.ticks(),
            benchmark.taskState()
        );
        LpPerformanceHistoryRecorder.recordExecution(
            benchmark.scenarioName(),
            "total",
            benchmark.totalNanos(),
            benchmark.ticks(),
            benchmark.taskState()
        );
        System.out.printf(
            "[LP-PERF][history] Appended rows to %s%n",
            LpPerformanceHistoryRecorder.outputPath()
        );
    }

    private static void printSectionTiming(final SectionTiming timing) {
        System.out.printf(
            "[LP-PERF][sections] scenario=%s init=%s solve=%s desanitize=%s preview=%s treePreview=%s total=%s"
                + " solved=%s previewType=%s treeType=%s%n",
            timing.scenarioName(),
            toMillis(timing.initializationNanos()),
            toMillis(timing.solveNanos()),
            toMillis(timing.desanitizeNanos()),
            toMillis(timing.previewNanos()),
            toMillis(timing.treePreviewNanos()),
            toMillis(timing.totalNanos()),
            timing.solved(),
            timing.previewType(),
            timing.treePreviewType()
        );
        LpPerformanceHistoryRecorder.recordSection(
            timing.scenarioName(),
            "initialization",
            timing.initializationNanos(),
            timing.solved(),
            timing.previewType(),
            timing.treePreviewType()
        );
        LpPerformanceHistoryRecorder.recordSection(
            timing.scenarioName(),
            "solve",
            timing.solveNanos(),
            timing.solved(),
            timing.previewType(),
            timing.treePreviewType()
        );
        LpPerformanceHistoryRecorder.recordSection(
            timing.scenarioName(),
            "desanitize",
            timing.desanitizeNanos(),
            timing.solved(),
            timing.previewType(),
            timing.treePreviewType()
        );
        LpPerformanceHistoryRecorder.recordSection(
            timing.scenarioName(),
            "preview",
            timing.previewNanos(),
            timing.solved(),
            timing.previewType(),
            timing.treePreviewType()
        );
        LpPerformanceHistoryRecorder.recordSection(
            timing.scenarioName(),
            "treePreview",
            timing.treePreviewNanos(),
            timing.solved(),
            timing.previewType(),
            timing.treePreviewType()
        );
        LpPerformanceHistoryRecorder.recordSection(
            timing.scenarioName(),
            "total",
            timing.totalNanos(),
            timing.solved(),
            timing.previewType(),
            timing.treePreviewType()
        );
        System.out.printf(
            "[LP-PERF][history] Appended rows to %s%n",
            LpPerformanceHistoryRecorder.outputPath()
        );
    }

    private static String toMillis(final long nanos) {
        return Duration.ofNanos(nanos).toMillis() + "ms";
    }

    private record SyntheticResourceKey(String value) implements ResourceKey {
        @Override
        public String toString() {
            return value;
        }
    }

    private record Scenario(
        String name,
        RootStorage storage,
        PatternRepository patternRepository,
        ResourceKey targetResource,
        long requestedAmount
    ) {
    }

    private record FullPreviewBenchmark(
        String scenarioName,
        Optional<DesanitizedRecipeApplicationPath> path,
        Preview preview,
        TreePreview treePreview,
        long solveAndPreviewNanos,
        long solveAndTreePreviewNanos,
        long totalNanos
    ) {
    }

    private record TaskExecutionBenchmark(
        String scenarioName,
        TaskState taskState,
        int ticks,
        long solveNanos,
        long dispatchNanos,
        long executeNanos,
        long totalNanos
    ) {
    }

    private record SectionTiming(
        String scenarioName,
        boolean solved,
        PreviewType previewType,
        PreviewType treePreviewType,
        long initializationNanos,
        long solveNanos,
        long desanitizeNanos,
        long previewNanos,
        long treePreviewNanos,
        long totalNanos
    ) {
    }
}
