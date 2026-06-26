package com.example.lms.search.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-safe query variant planner.
 *
 * <p>No network, no LLM, and no raw query logging. Providers use the returned
 * counts/reason for redacted telemetry only.
 */
public final class AdaptiveSearchQueryVariants {

    public enum Provider {
        NAVER,
        BRAVE
    }

    public record Options(
            Provider provider,
            boolean enabled,
            int maxQueries,
            long remainingBudgetMs,
            long maxOverallTimeoutMs,
            long perCallFloorMs,
            boolean recallMode,
            boolean providerEmpty,
            boolean afterFilterStarved) {
    }

    public record Plan(
            List<String> queries,
            String triggerReason,
            int sliceCount,
            int expansionCount,
            long budgetMs,
            long perCallMs,
            boolean enabled) {

        public List<String> variants() {
            if (queries == null || queries.size() <= 1) {
                return List.of();
            }
            return List.copyOf(queries.subList(1, queries.size()));
        }
    }

    private AdaptiveSearchQueryVariants() {
    }

    public static Plan plan(String originalQuery, List<String> baseCandidates, Options options) {
        Options o = options == null
                ? new Options(Provider.NAVER, false, 1, 0L, 0L, 0L, false, false, false)
                : options;
        int providerCap = o.provider() == Provider.BRAVE ? 3 : 9;
        int maxQueries = clamp(o.maxQueries(), 1, providerCap);
        long maxOverall = Math.max(0L, o.maxOverallTimeoutMs());
        long remaining = o.remainingBudgetMs() > 0 ? o.remainingBudgetMs() : maxOverall;
        long budgetMs = maxOverall > 0 ? Math.min(remaining, maxOverall) : Math.max(0L, remaining);
        long floorMs = Math.max(1L, o.perCallFloorMs());

        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        addAll(out, baseCandidates);
        String original = compact(originalQuery);
        add(out, original);

        if (out.isEmpty()) {
            return new Plan(List.of(), "blank", 0, 0, budgetMs, floorMs, false);
        }
        if (!o.enabled()) {
            return new Plan(List.copyOf(out.values()), "disabled", 0, 0, budgetMs, floorMs, false);
        }
        if (maxQueries <= 1 || budgetMs < floorMs * 2) {
            return new Plan(capped(out, 1), "budget", 0, 0, budgetMs, floorMs, true);
        }

        List<String> slices = QuerySlicer.slice(original, 2, 1, Math.max(1, maxQueries - out.size()));
        boolean compound = isCompound(original, slices);
        String trigger = triggerReason(o, compound);
        if ("base-only".equals(trigger)) {
            return new Plan(capped(out, maxQueries), trigger, 0, 0, budgetMs, floorMs, true);
        }

        int beforeSlices = out.size();
        for (String slice : slices) {
            addLooselyDistinct(out, slice);
            if (out.size() >= maxQueries) {
                break;
            }
        }
        int sliceCount = Math.max(0, out.size() - beforeSlices);

        int beforeExpansions = out.size();
        if (out.size() < maxQueries) {
            SearchPolicyMode mode = o.providerEmpty() || o.afterFilterStarved() || o.recallMode()
                    ? SearchPolicyMode.RECALL
                    : SearchPolicyMode.BALANCED;
            List<String> expansions = StochasticExpander.expand(original, mode, maxQueries - out.size());
            for (String expansion : expansions) {
                addLooselyDistinct(out, expansion);
                if (out.size() >= maxQueries) {
                    break;
                }
            }
        }
        int expansionCount = Math.max(0, out.size() - beforeExpansions);
        int queryCount = Math.max(1, Math.min(maxQueries, out.size()));
        long perCallMs = Math.max(floorMs, budgetMs / Math.max(1, queryCount));

        return new Plan(capped(out, maxQueries), trigger, sliceCount, expansionCount, budgetMs, perCallMs, true);
    }

    private static String triggerReason(Options o, boolean compound) {
        if (o.afterFilterStarved()) {
            return "after-filter-starvation";
        }
        if (o.providerEmpty()) {
            return "provider-empty";
        }
        if (o.recallMode()) {
            return "recall-policy";
        }
        if (compound) {
            return "compound-query";
        }
        return "base-only";
    }

    private static boolean isCompound(String query, List<String> slices) {
        String q = compact(query);
        if (!slices.isEmpty()) {
            return true;
        }
        if (q.length() >= 96) {
            return true;
        }
        String[] tokens = q.isBlank() ? new String[0] : q.split("\\s+");
        return tokens.length >= 13;
    }

    private static void addAll(LinkedHashMap<String, String> out, List<String> candidates) {
        if (candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            add(out, candidate);
        }
    }

    private static void add(LinkedHashMap<String, String> out, String candidate) {
        String s = compact(candidate);
        if (s.isBlank()) {
            return;
        }
        String key = key(s);
        if (!key.isBlank()) {
            out.putIfAbsent(key, s);
        }
    }

    private static void addLooselyDistinct(LinkedHashMap<String, String> out, String candidate) {
        String s = compact(candidate);
        if (s.isBlank()) {
            return;
        }
        Set<String> candidateTokens = tokenSet(s);
        for (String existing : out.values()) {
            if (jaccard(candidateTokens, tokenSet(existing)) >= 0.94d) {
                return;
            }
        }
        add(out, s);
    }

    private static List<String> capped(LinkedHashMap<String, String> out, int maxQueries) {
        List<String> values = new ArrayList<>(out.values());
        if (values.size() <= maxQueries) {
            return List.copyOf(values);
        }
        return List.copyOf(values.subList(0, maxQueries));
    }

    private static String compact(String value) {
        return Objects.toString(value, "").replaceAll("\\p{Cntrl}", " ").replaceAll("\\s{2,}", " ").trim();
    }

    private static String key(String value) {
        return compact(value).toLowerCase(Locale.ROOT);
    }

    private static Set<String> tokenSet(String value) {
        String compact = key(value);
        if (compact.isBlank()) {
            return Set.of();
        }
        return new java.util.LinkedHashSet<>(List.of(compact.split("\\s+")));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        int intersection = 0;
        for (String token : a) {
            if (b.contains(token)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union <= 0 ? 0.0d : (double) intersection / (double) union;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
