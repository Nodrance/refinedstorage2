package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CycleSafeBudgetPlanner {
    private CycleSafeBudgetPlanner() {
    }

    static Map<Pattern, Integer> computeStepBudgets(final Set<Pattern> activePatterns,
                                                    final List<Pattern> plannerPatternOrder,
                                                    final Map<Pattern, Integer> plannerOverrideRemainingSteps,
                                                    final Map<Pattern, Set<ResourceKey>> consumedResourcesByPattern) {
        final Map<Pattern, Integer> budgets = new LinkedHashMap<>();
        final CycleSafetyState cycleSafetyState = buildCycleSafetyState(activePatterns, consumedResourcesByPattern);
        final Pattern plannerOverridePattern = findPlannerOverridePattern(
            activePatterns,
            plannerPatternOrder,
            plannerOverrideRemainingSteps
        );

        for (final Pattern pattern : plannerPatternOrder) {
            if (!activePatterns.contains(pattern)) {
                continue;
            }
            final int budget = computeBudget(pattern, plannerOverridePattern, plannerOverrideRemainingSteps, cycleSafetyState);
            if (budget > 0) {
                budgets.put(pattern, budget);
            }
        }

        for (final Pattern pattern : activePatterns) {
            if (budgets.containsKey(pattern)) {
                continue;
            }
            final int budget = computeBudget(pattern, plannerOverridePattern, plannerOverrideRemainingSteps, cycleSafetyState);
            if (budget > 0) {
                budgets.put(pattern, budget);
            }
        }

        return budgets;
    }

    private static int computeBudget(final Pattern pattern,
                                     final Pattern plannerOverridePattern,
                                     final Map<Pattern, Integer> plannerOverrideRemainingSteps,
                                     final CycleSafetyState cycleSafetyState) {
        if (isUnlimitedCycleSafe(pattern, consumedResourcesByPattern(pattern, cycleSafetyState), cycleSafetyState)) {
            return Integer.MAX_VALUE;
        }
        if (pattern.equals(plannerOverridePattern)) {
            return Math.max(0, plannerOverrideRemainingSteps.getOrDefault(pattern, 0));
        }
        return 0;
    }

    private static Set<ResourceKey> consumedResourcesByPattern(final Pattern pattern,
                                                               final CycleSafetyState cycleSafetyState) {
        return cycleSafetyState.consumedResourcesByPattern().getOrDefault(pattern, Set.of());
    }

    private static boolean isUnlimitedCycleSafe(final Pattern pattern,
                                                final Set<ResourceKey> consumedResources,
                                                final CycleSafetyState cycleSafetyState) {
        final Set<ResourceKey> conflictedConsumed = new HashSet<>();
        for (final ResourceKey resource : consumedResources) {
            if (cycleSafetyState.conflictedResources().contains(resource)) {
                conflictedConsumed.add(resource);
            }
        }

        if (conflictedConsumed.isEmpty()) {
            return true;
        }

        final Integer groupId = cycleSafetyState.cycleGroupByPattern().get(pattern);
        if (groupId == null) {
            return false;
        }

        final Set<ResourceKey> producedByGroup = cycleSafetyState.producedResourcesByCycleGroup()
            .getOrDefault(groupId, Set.of());
        return producedByGroup.containsAll(conflictedConsumed);
    }

    private static Pattern findPlannerOverridePattern(final Set<Pattern> activePatterns,
                                                      final List<Pattern> plannerPatternOrder,
                                                      final Map<Pattern, Integer> plannerOverrideRemainingSteps) {
        for (final Pattern pattern : plannerPatternOrder) {
            if (!activePatterns.contains(pattern)) {
                continue;
            }
            if (plannerOverrideRemainingSteps.getOrDefault(pattern, 0) > 0) {
                return pattern;
            }
        }
        return null;
    }

    private static CycleSafetyState buildCycleSafetyState(final Set<Pattern> activePatterns,
                                                          final Map<Pattern, Set<ResourceKey>> consumedResourcesByPattern) {
        final Map<Pattern, Set<ResourceKey>> producedResourcesByPattern = new HashMap<>();
        final Map<Pattern, Set<Pattern>> adjacency = new HashMap<>();

        for (final Pattern pattern : activePatterns) {
            producedResourcesByPattern.put(pattern, getProducedResources(pattern));
            adjacency.put(pattern, new LinkedHashSet<>());
        }

        for (final Pattern source : activePatterns) {
            final Set<ResourceKey> sourceProduces = producedResourcesByPattern.getOrDefault(source, Set.of());
            for (final Pattern target : activePatterns) {
                final Set<ResourceKey> targetConsumes = consumedResourcesByPattern.getOrDefault(target, Set.of());
                if (!sourceProduces.isEmpty() && !targetConsumes.isEmpty() && intersects(sourceProduces, targetConsumes)) {
                    adjacency.get(source).add(target);
                }
            }
        }

        final Map<Pattern, Set<Pattern>> reachable = new HashMap<>();
        for (final Pattern pattern : activePatterns) {
            reachable.put(pattern, dfsReachable(pattern, adjacency));
        }

        final Map<Pattern, Integer> groupByPattern = new HashMap<>();
        final Map<Integer, Set<Pattern>> groups = new HashMap<>();
        int groupId = 0;
        for (final Pattern pattern : activePatterns) {
            if (groupByPattern.containsKey(pattern)) {
                continue;
            }
            final Set<Pattern> group = new LinkedHashSet<>();
            group.add(pattern);
            for (final Pattern other : activePatterns) {
                if (pattern.equals(other)) {
                    continue;
                }
                if (reachable.getOrDefault(pattern, Set.of()).contains(other)
                    && reachable.getOrDefault(other, Set.of()).contains(pattern)) {
                    group.add(other);
                }
            }
            for (final Pattern member : group) {
                groupByPattern.put(member, groupId);
            }
            groups.put(groupId, group);
            groupId++;
        }

        final Map<Integer, Set<ResourceKey>> producedByCycleGroup = new HashMap<>();
        final Set<Pattern> cyclePatterns = new HashSet<>();
        for (final var entry : groups.entrySet()) {
            final int id = entry.getKey();
            final Set<Pattern> group = entry.getValue();
            final boolean selfCycle = group.size() == 1
                && adjacency.getOrDefault(group.iterator().next(), Set.of()).contains(group.iterator().next());
            final boolean isCycle = group.size() > 1 || selfCycle;
            if (!isCycle) {
                continue;
            }

            final Set<ResourceKey> produced = new HashSet<>();
            for (final Pattern pattern : group) {
                cyclePatterns.add(pattern);
                produced.addAll(producedResourcesByPattern.getOrDefault(pattern, Set.of()));
            }
            producedByCycleGroup.put(id, produced);
        }

        final Set<ResourceKey> producedInCycles = new HashSet<>();
        final Set<ResourceKey> consumedInCycles = new HashSet<>();
        for (final Pattern pattern : cyclePatterns) {
            producedInCycles.addAll(producedResourcesByPattern.getOrDefault(pattern, Set.of()));
            consumedInCycles.addAll(consumedResourcesByPattern.getOrDefault(pattern, Set.of()));
        }
        final Set<ResourceKey> conflicted = new HashSet<>(producedInCycles);
        conflicted.retainAll(consumedInCycles);

        return new CycleSafetyState(
            groupByPattern,
            producedByCycleGroup,
            conflicted,
            consumedResourcesByPattern
        );
    }

    private static Set<Pattern> dfsReachable(final Pattern start,
                                             final Map<Pattern, Set<Pattern>> adjacency) {
        final Set<Pattern> visited = new LinkedHashSet<>();
        final List<Pattern> stack = new ArrayList<>();
        stack.add(start);
        while (!stack.isEmpty()) {
            final Pattern current = stack.removeLast();
            if (!visited.add(current)) {
                continue;
            }
            for (final Pattern next : adjacency.getOrDefault(current, Set.of())) {
                if (!visited.contains(next)) {
                    stack.add(next);
                }
            }
        }
        return visited;
    }

    private static boolean intersects(final Set<ResourceKey> left,
                                      final Set<ResourceKey> right) {
        for (final ResourceKey resource : left) {
            if (right.contains(resource)) {
                return true;
            }
        }
        return false;
    }

    private static Set<ResourceKey> getProducedResources(final Pattern pattern) {
        final Set<ResourceKey> produced = new LinkedHashSet<>();
        pattern.layout().outputs().forEach(output -> produced.add(output.resource()));
        pattern.layout().byproducts().forEach(byproduct -> produced.add(byproduct.resource()));
        return produced;
    }

    private record CycleSafetyState(Map<Pattern, Integer> cycleGroupByPattern,
                                    Map<Integer, Set<ResourceKey>> producedResourcesByCycleGroup,
                                    Set<ResourceKey> conflictedResources,
                                    Map<Pattern, Set<ResourceKey>> consumedResourcesByPattern) {
    }
}
