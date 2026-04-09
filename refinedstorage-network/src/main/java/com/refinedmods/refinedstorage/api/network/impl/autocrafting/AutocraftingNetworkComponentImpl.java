package com.refinedmods.refinedstorage.api.network.impl.autocrafting;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatusListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.ParentContainer;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternListener;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class AutocraftingNetworkComponentImpl implements AutocraftingNetworkComponent, ParentContainer {
    private final AutocraftingNetworkComponentState state;
    private final TraditionalAutocraftingNetworkComponentImpl traditionalAutocrafting;
    private final LinearAutocraftingNetworkComponentImpl linearAutocrafting;

    public AutocraftingNetworkComponentImpl(final Supplier<RootStorage> rootStorageProvider,
                                            final ExecutorService executorService) {
        this.state = new AutocraftingNetworkComponentState(rootStorageProvider, executorService);
        this.traditionalAutocrafting = new TraditionalAutocraftingNetworkComponentImpl(state);
        this.linearAutocrafting = new LinearAutocraftingNetworkComponentImpl(state);
    }

    @Override
    public void onContainerAdded(final NetworkNodeContainer container) {
        state.onContainerAdded(this, container);
    }

    @Override
    public void onContainerRemoved(final NetworkNodeContainer container) {
        state.onContainerRemoved(this, container);
    }

    @Override
    public Set<ResourceKey> getOutputs() {
        return state.getOutputs();
    }

    @Override
    public boolean contains(final AutocraftingNetworkComponent component) {
        return state.contains(component);
    }

    @Nullable
    @Override
    public PatternProvider getProviderByPattern(final Pattern pattern) {
        return state.getProviderByPattern(pattern);
    }

    @Override
    public CompletableFuture<Optional<Preview>> getPreview(final ResourceKey resource,
                                                           final long amount,
                                                           final CancellationToken cancellationToken) {
        return getImplementation().getPreview(resource, amount, cancellationToken);
    }

    @Override
    public CompletableFuture<Optional<TreePreview>> getTreePreview(final ResourceKey resource,
                                                                   final long amount,
                                                                   final CancellationToken cancellationToken) {
        return getImplementation().getTreePreview(resource, amount, cancellationToken);
    }

    @Override
    public CompletableFuture<Long> getMaxAmount(final ResourceKey resource,
                                                final CancellationToken cancellationToken) {
        return getImplementation().getMaxAmount(resource, cancellationToken);
    }

    @Override
    public Optional<TaskId> startTask(final ResourceKey resource,
                                      final long amount,
                                      final Actor actor,
                                      final boolean notify,
                                      final CancellationToken cancellationToken) {
        return getImplementation().startTask(resource, amount, actor, notify, cancellationToken);
    }

    @Override
    public EnsureResult ensureTask(final ResourceKey resource,
                                   final long amount,
                                   final Actor actor,
                                   final CancellationToken cancellationToken) {
        return getImplementation().ensureTask(resource, amount, actor, cancellationToken);
    }

    private TraditionalAutocraftingNetworkComponentImpl getImplementation() {
        return shouldUseLinearAutocraftingSystem() ? linearAutocrafting : traditionalAutocrafting;
    }

    boolean shouldUseLinearAutocraftingSystem() {
        return AutocraftingModeContext.isUseLinearAutocraftingSystem();
    }

    @Override
    public void addListener(final PatternListener listener) {
        state.addListener(listener);
    }

    @Override
    public void addListener(final TaskStatusListener listener) {
        state.addListener(listener);
    }

    @Override
    public void removeListener(final PatternListener listener) {
        state.removeListener(listener);
    }

    @Override
    public void removeListener(final TaskStatusListener listener) {
        state.removeListener(listener);
    }

    @Override
    public Set<Pattern> getPatterns() {
        return state.getPatterns();
    }

    @Override
    public List<Pattern> getPatternsByOutput(final ResourceKey output) {
        return state.getPatternsByOutput(output);
    }

    @Override
    public List<TaskStatus> getStatuses() {
        return state.getStatuses();
    }

    @Override
    public void cancel(final TaskId taskId) {
        state.cancel(taskId);
    }

    @Override
    public void cancelAll() {
        state.cancelAll();
    }

    @Override
    public void add(final PatternProvider provider, final Pattern pattern, final int priority) {
        state.add(provider, pattern, priority);
    }

    @Override
    public void remove(final PatternProvider provider, final Pattern pattern) {
        state.remove(provider, pattern);
    }

    @Override
    public void update(final Pattern pattern, final int priority) {
        state.update(pattern, priority);
    }

    @Override
    public void taskAdded(final PatternProvider provider, final Task task) {
        state.taskAdded(provider, task);
    }

    @Override
    public void taskRemoved(final Task task) {
        state.taskRemoved(task);
    }

    @Override
    public void taskCompleted(final Task task) {
        state.taskCompleted(task);
    }

    @Override
    public void taskChanged(final Task task) {
        state.taskChanged(task);
    }

    @Override
    public List<ExternalPatternSink> getSinksByPatternLayout(final PatternLayout patternLayout) {
        return state.getSinksByPatternLayout(patternLayout);
    }
}
