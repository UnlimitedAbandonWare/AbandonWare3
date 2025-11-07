package com.example.lms.service.rag.diversity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Greedy DPP-style diversity reranker.  This component takes an input
 * collection of candidates (implementing {@link HasVec}) and selects
 * a subset that balances relevance and novelty.  The {@code lambda}
 * parameter determines how strongly the novelty penalty influences
 * selection; lower values favour relevance while higher values
 * encourage diversity.
 */
@Component
public class DppDiversityReranker {
    private final double lambda;
    public DppDiversityReranker(@Value("${rerank.dpp.lambda:0.30}") double lambda) {
        this.lambda = lambda;
    }

    /**
     * Rerank the input list and return the top {@code topK} elements.
     *
     * @param in   the input list of candidates
     * @param topK the maximum number of results to return
     * @param <T>  the candidate type
     * @return a new list containing up to {@code topK} items
     */
    public <T extends HasVec> List<T> rerank(List<T> in, int topK) {
        if (in == null || in.isEmpty()) return in;
        int k = Math.min(topK, in.size());
        // sort by relevance descending
        List<T> pool = new ArrayList<>(in);
        pool.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        List<T> out = new ArrayList<>();
        while (out.size() < k && !pool.isEmpty()) {
            T best = null;
            double bestObj = -1e9;
            for (T cand : pool) {
                double noveltyPenalty = 0.0;
                for (T s : out) {
                    noveltyPenalty = Math.max(noveltyPenalty, cosSim(cand.getVector(), s.getVector()));
                }
                double objective = cand.getScore() - lambda * noveltyPenalty;
                if (objective > bestObj) {
                    bestObj = objective;
                    best = cand;
                }
            }
            out.add(best);
            pool.remove(best);
        }
        return out;
    }

    private static double cosSim(double[] a, double[] b) {
        if (a == null || b == null) return 0.0;
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * Interface to be implemented by rerank candidates.  Provides
     * access to the raw relevance score and optional embedding vector.
     */
    public interface HasVec {
        double getScore();
        double[] getVector(); // may return null
    }
}