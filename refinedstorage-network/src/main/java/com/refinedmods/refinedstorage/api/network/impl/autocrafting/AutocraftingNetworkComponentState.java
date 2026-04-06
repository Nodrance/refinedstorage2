package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.PatternType;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatusListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlan;
import com.refinedmods.refinedstorage.api.core.CoreValidations;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.ParentContainer;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternListener;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import org.slf4j.Logger;

final class AutocraftingNetworkComponentState {
    private final Supplier<RootStorage> rootStorageProvider;
    private final ExecutorService executorService;
    private final Set<PatternProvider> providers = new HashSet<>();
    private final Map<Pattern, PatternProvider> providerByPattern = new HashMap<>();
    private final Map<PatternLayout, List<ExternalPatternSink>> sinksByPatternLayout = new HashMap<>();
    private final Map<TaskId, PatternProvider> providerByTaskId = new HashMap<>();
    private final Set<PatternListener> patternListeners = new HashSet<>();
    private final Set<TaskStatusListener> statusListeners = new HashSet<>();
    private final PatternRepositoryImpl patternRepository = new PatternRepositoryImpl();

    AutocraftingNetworkComponentState(final Supplier<RootStorage> rootStorageProvider,
                                      final ExecutorService executorService) {
        this.rootStorageProvider = rootStorageProvider;
        this.executorService = executorService;
    }

    Supplier<RootStorage> getRootStorageProvider() {
        return rootStorageProvider;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    PatternRepositoryImpl getPatternRepository() {
        return patternRepository;
    }

    Map<Pattern, PatternProvider> getProviderByPatternMap() {
        return providerByPattern;
    }

    void onContainerAdded(final ParentContainer parent, final NetworkNodeContainer container) {
        if (container.getNode() instanceof PatternProvider provider) {
            provider.onAddedIntoContainer(parent);
            providers.add(provider);
        }
    }

    void onContainerRemoved(final ParentContainer parent, final NetworkNodeContainer container) {
        if (container.getNode() instanceof PatternProvider provider) {
            provider.onRemovedFromContainer(parent);
            providers.remove(provider);
        }
    }

    Set<ResourceKey> getOutputs() {
        return patternRepository.getOutputs();
    }

    boolean contains(final AutocraftingNetworkComponent component) {
        return providers.stream().anyMatch(provider -> provider.contains(component));
    }

    @Nullable
    PatternProvider getProviderByPattern(final Pattern pattern) {
        return providerByPattern.get(pattern);
    }

    void addListener(final PatternListener listener) {
        patternListeners.add(listener);
    }

    void addListener(final TaskStatusListener listener) {
        statusListeners.add(listener);
    }

    void removeListener(final PatternListener listener) {
        patternListeners.remove(listener);
    }

    void removeListener(final TaskStatusListener listener) {
        statusListeners.remove(listener);
    }

    Set<Pattern> getPatterns() {
        return patternRepository.getAll();
    }

    List<Pattern> getPatternsByOutput(final ResourceKey output) {
        return patternRepository.getByOutput(output);
    }

    List<TaskStatus> getStatuses() {
        return providers.stream().map(PatternProvider::getTaskStatuses).flatMap(List::stream).toList();
    }

    void cancel(final TaskId taskId) {
        final PatternProvider provider = providerByTaskId.get(taskId);
        if (provider == null) {
            return;
        }
        provider.cancelTask(taskId);
    }

    void cancelAll() {
        for (final Map.Entry<TaskId, PatternProvider> entry : providerByTaskId.entrySet()) {
            entry.getValue().cancelTask(entry.getKey());
        }
    }

    void add(final PatternProvider provider, final Pattern pattern, final int priority) {
        patternRepository.add(pattern, priority);
        providerByPattern.put(pattern, provider);
        final List<ExternalPatternSink> sinks = sinksByPatternLayout.computeIfAbsent(
            pattern.layout(),
            layout -> new ArrayList<>()
        );
        if (!sinks.contains(provider)) {
            sinks.add(provider);
        }
        patternListeners.forEach(listener -> listener.onAdded(pattern));
    }

    void remove(final PatternProvider provider, final Pattern pattern) {
        patternListeners.forEach(listener -> listener.onRemoved(pattern));
        providerByPattern.remove(pattern);
        final List<ExternalPatternSink> sinksByLayout = sinksByPatternLayout.get(pattern.layout());
        if (sinksByLayout != null) {
            sinksByLayout.remove(provider);
            if (sinksByLayout.isEmpty()) {
                sinksByPatternLayout.remove(pattern.layout());
            }
        }
        patternRepository.remove(pattern);
    }

    void update(final Pattern pattern, final int priority) {
        patternRepository.update(pattern, priority);
    }

    void taskAdded(final PatternProvider provider, final Task task) {
        providerByTaskId.put(task.getId(), provider);
        statusListeners.forEach(listener -> listener.taskAdded(task.getStatus()));
    }

    void taskRemoved(final Task task) {
        providerByTaskId.remove(task.getId());
        statusListeners.forEach(listener -> listener.taskRemoved(task.getId()));
    }

    void taskCompleted(final Task task) {
        taskRemoved(task);
    }

    void taskChanged(final Task task) {
        if (statusListeners.isEmpty()) {
            return;
        }
        final TaskStatus status = task.getStatus();
        statusListeners.forEach(listener -> listener.taskStatusChanged(status));
    }

    List<ExternalPatternSink> getSinksByPatternLayout(final PatternLayout patternLayout) {
        return sinksByPatternLayout.getOrDefault(patternLayout, Collections.emptyList());
    }

    TaskId addTask(final ResourceKey resource,
                   final long amount,
                   final Actor actor,
                   final TaskPlan plan,
                   final boolean notify,
                   final Logger logger) {
        final Task task = new TaskImpl(plan, actor, notify);
        logger.debug("Created task {} for {}x {} for {}", task.getId(), amount, resource, actor);
        final PatternProvider provider = CoreValidations.validateNotNull(
            providerByPattern.get(plan.rootPattern()),
            "No provider for pattern " + plan.rootPattern()
        );
        provider.addTask(task);
        return task.getId();
    }

    TaskId addSingleStepTask(final Actor actor,
                             final TaskPlan plan,
                             final boolean notify,
                             final Logger logger) {
        final Task task = new TaskImpl(plan, actor, notify);
        logger.debug(
            "Created single-step LP task {} for {}x {} for {}",
            task.getId(),
            plan.amount(),
            plan.resource(),
            actor
        );

        final PatternProvider provider;
        if (plan.rootPattern().layout().type() == PatternType.EXTERNAL) {
            provider = getSinksByPatternLayout(plan.rootPattern().layout()).stream()
                .filter(PatternProvider.class::isInstance)
                .map(PatternProvider.class::cast)
                .findFirst()
                .orElseGet(() -> CoreValidations.validateNotNull(
                    providerByPattern.get(plan.rootPattern()),
                    "No provider for pattern " + plan.rootPattern()
                ));
        } else {
            provider = CoreValidations.validateNotNull(
                providerByPattern.get(plan.rootPattern()),
                "No provider for pattern " + plan.rootPattern()
            );
        }

        provider.addTask(task);
        return task.getId();
    }
}