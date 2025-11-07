package com.example.lms.service.rag.rerank;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Determinantal point process (DPP) based selector to promote
 * diversity in a ranked list of documents.  Given a pool of
 * candidates each with an embedding and relevance score the
 * algorithm greedily picks items that maximise a trade‑off between
 * relevance and diversity.  This reduces redundancy before passing
 * documents to a more expensive cross‑encoder reranker.
 */
public class DppDiversityReranker {
    public static class Doc {
        public final String id;
        public final double score;
        public final float[] embedding;
        public Doc(String id, double score, float[] embedding) {
            this.id = id;
            this.score = score;
            this.embedding = embedding;
        }
    }

    /**
     * Select up to {@code k} diverse documents from the input pool.  If
     * the pool size is less than or equal to {@code k} the pool is
     * returned unchanged.
     *
     * @param pool candidate documents
     * @param k desired output size
     * @return a list of at most {@code k} documents
     */
    public List<Doc> select(List<Doc> pool, int k) {
        if (pool == null || pool.size() <= k) {
            return pool;
        }
        int n = pool.size();
        double[][] sim = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                double s = cosine(pool.get(i).embedding, pool.get(j).embedding);
                sim[i][j] = s;
                sim[j][i] = s;
            }
        }
        List<Doc> result = new ArrayList<>(k);
        Set<Integer> chosen = new HashSet<>();
        Set<Integer> cand = new HashSet<>();
        for (int i = 0; i < n; i++) {
            cand.add(i);
        }
        while (result.size() < k && !cand.isEmpty()) {
            int best = -1;
            double bestGain = Double.NEGATIVE_INFINITY;
            for (int i : cand) {
                double diversity = 0.0;
                for (int j : chosen) {
                    diversity += Math.abs(sim[i][j]);
                }
                double gain = pool.get(i).score - diversity;
                if (gain > bestGain) {
                    bestGain = gain;
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            chosen.add(best);
            cand.remove(best);
            result.add(pool.get(best));
        }
        return result;
    }

    private double cosine(float[] a, float[] b) {
        double dp = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dp += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dp / (Math.sqrt(na) * Math.sqrt(nb) + 1e-8);
    }
}