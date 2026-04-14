package com.refinedmods.refinedstorage.api.autocrafting;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.Collection;
import java.util.Set;

import org.apiguardian.api.API;

@API(status = API.Status.STABLE, since = "2.0.0-milestone.4.8")
public interface PatternRepository {
    void add(Pattern pattern, int priority);

    void update(Pattern pattern, int priority);

    int getPriority(Pattern pattern);

    void remove(Pattern pattern);

    Collection<Pattern> getByOutput(ResourceKey output);

    Set<ResourceKey> getOutputs();

    Set<Pattern> getAll();
}
