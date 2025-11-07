package com.example.lms.service.rag.fusion;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Simple Weighted Reciprocal Rank Fusion implementation.
 *
 * <p>This implementation fuses ranked lists of {@link Candidate} objects from
 * multiple sources using the reciprocal rank fusion (RRF) formula. It also
 * supports optional URL canonicalisation and per-source score calibration
 * via dynamically injected services. The design intentionally avoids
 * dependencies on the broader domain model to ensure the {@code app}
 * module builds independently of {@code lms-core}.</p>
 */
public class WeightedRRF {
    /**
     * Mode selection for fusion behaviour. Default is "RRF".
     * Some callers expect to configure this property even though this
     * implementation currently ignores it. It is retained for
     * forward‑compatibility and to avoid NoSuchMethodErrors.
     */
    private String mode = "RRF";

    /**
     * Power parameter for weighted power mean fusion. Default is 1.0.
     * This implementation does not currently use a power mean, but
     * exposes the property to satisfy configuration expectations.
     */
    private double p = 1.0;

    /** Optional external canonicaliser used to normalise document IDs or URLs. */
    private Object externalCanonicalizer;
    /** Optional external calibrator used to adjust raw source scores. */
    private Object externalCalibrator;

    /**
     * Set an external canonicaliser. The provided object must expose a
     * {@code canonicalUrl(String)} method returning a {@code String}. When
     * unset or when invocation fails the built‑in canonicaliser is used.
     *
     * @param can canonicaliser implementation
     */
    public void setExternalCanonicalizer(Object can) {
        this.externalCanonicalizer = can;
    }

    /**
     * Set an external calibrator. The provided object must expose a
     * {@code calibrate(String,double)} method returning a {@code double}.
     * When unset or when invocation fails the base score is returned
     * unmodified.
     *
     * @param calibrator calibrator implementation
     */
    public void setExternalCalibrator(Object calibrator) {
        this.externalCalibrator = calibrator;
    }

    /**
     * Set the fusion mode.
     *
     * <p>This setter is a no‑op in the current implementation. It exists
     * solely to avoid build errors when callers attempt to configure
     * the fusion mode via {@code RrfFusion}. Future versions may
     * interpret different modes.
     *
     * @param mode fusion mode name (e.g. "RRF" or "WPM")
     */
    public void setMode(String mode) {
        if (mode != null) {
            this.mode = mode;
        }
    }

    /**
     * Set the power parameter used by the weighted power mean fusion.
     *
     * <p>This setter is a no‑op in the current implementation. It is
     * provided to avoid {@code NoSuchMethodError} at runtime when
     * configuring the fusion via system properties. Future versions
     * may use this value when implementing power mean fusion.
     *
     * @param p power mean exponent
     */
    public void setP(double p) {
        this.p = p;
    }

    /**
     * Candidate object representing a document and its associated metadata.
     */
    public static final class Candidate {
        public final String id;
        public final String source;
        public final double baseScore;
        public int rank;
        public double fused;

        public Candidate(String id, String source, double baseScore, int rank) {
            this.id = id;
            this.source = source;
            this.baseScore = baseScore;
            this.rank = rank;
            this.fused = 0.0;
        }
    }

    /** Default RRF constant. */
    private int k = 60;

    /**
     * Fuse multiple ranked lists into a single score per canonical ID.
     *
     * <p>This method applies reciprocal rank fusion (RRF) to the provided
     * lists. Each source may be assigned an optional weight via the
     * {@code weights} map. Before fusion each candidate's base score is
     * optionally calibrated via the external calibrator and its ID is
     * canonicalised to aid deduplication.</p>
     *
     * @param channels map from source name to list of candidates
     * @param k RRF constant (larger values reduce the influence of rank)
     * @param weights per‑source weights (may be {@code null})
     * @return map from canonical ID to fused score
     */
    public Map<String, Double> fuse(Map<String, List<Candidate>> channels,
                                    double k,
                                    Map<String, Double> weights) {
        // update k if provided
        int kVal = (int) k;
        if (kVal > 0) {
            this.k = kVal;
        }
        Map<String, Double> sum = new HashMap<>();
        Map<String, Candidate> repr = new HashMap<>();
        if (channels == null) {
            return sum;
        }
        for (Map.Entry<String, List<Candidate>> entry : channels.entrySet()) {
            String source = entry.getKey();
            double w = weights == null ? 1.0 : weights.getOrDefault(source, 1.0);
            fuseInto(sum, repr, entry.getValue(), source, w);
        }
        return sum;
    }

    /**
     * Internal helper to fuse a single list of candidates into the accumulator.
     */
    private void fuseInto(Map<String, Double> acc,
                          Map<String, Candidate> repr,
                          List<Candidate> list,
                          String source,
                          double weight) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Candidate c : list) {
            // calibrate base score
            double calibrated = calibrate(source, c.baseScore);
            // compute RRF contribution using calibrated score only indirectly via ordering
            double rrf = weight / (this.k + Math.max(1, c.rank));
            // canonicalise ID (or URL)
            String key = canonical(c.id);
            acc.merge(key, rrf, Double::sum);
            // track representative candidate with highest calibrated score
            Candidate prev = repr.get(key);
            if (prev == null || calibrated > calibrate(prev.source, prev.baseScore)) {
                repr.put(key, c);
            }
        }
    }

    /**
     * Canonicalise a document ID or URL.
     *
     * <p>The canonicalisation first attempts to call an injected
     * {@code canonicalUrl(String)} method. If that fails it falls back to
     * normalising HTTP(S) URLs by stripping trailing slashes and removing
     * {@code utm_} query parameters. Non‑URL identifiers are lower‑cased and
     * trimmed.</p>
     *
     * @param idOrUrl document identifier or URL
     * @return canonical key
     */
    private String canonical(String idOrUrl) {
        // delegate to external canonicaliser if present
        if (externalCanonicalizer != null) {
            try {
                Method m = externalCanonicalizer.getClass().getMethod("canonicalUrl", String.class);
                Object out = m.invoke(externalCanonicalizer, idOrUrl);
                if (out instanceof String s) {
                    return s;
                }
            } catch (Exception ignore) {
                // ignore and fall back
            }
        }
        if (idOrUrl == null) {
            return "";
        }
        String s = idOrUrl.trim();
        try {
            if (s.startsWith("http://") || s.startsWith("https://")) {
                URI u = new URI(s);
                String host = u.getHost();
                String path = Optional.ofNullable(u.getPath()).orElse("");
                // remove trailing slashes
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                // filter out utm_* parameters
                String query = Optional.ofNullable(u.getQuery()).orElse("");
                String q = java.util.Arrays.stream(query.split("&"))
                        .filter(p -> !p.startsWith("utm_"))
                        .filter(p -> !p.isBlank())
                        .collect(Collectors.joining("&"));
                URI normalized = new URI(u.getScheme(), u.getUserInfo(), host, u.getPort(),
                        path.isEmpty() ? null : path,
                        q.isEmpty() ? null : q,
                        null);
                return normalized.toString();
            }
            return s.toLowerCase();
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Calibrate a raw score for a given source.
     *
     * <p>If an external calibrator is injected it must provide a
     * {@code calibrate(String,double)} method. Any exceptions thrown during
     * invocation result in the input score being returned unmodified.</p>
     *
     * @param source source name
     * @param score raw score
     * @return calibrated score or the original when no calibrator is present
     */
    private double calibrate(String source, double score) {
        if (externalCalibrator == null) {
            return score;
        }
        try {
            Method m = externalCalibrator.getClass().getMethod("calibrate", String.class, double.class);
            Object out = m.invoke(externalCalibrator, source, score);
            if (out instanceof Number n) {
                return n.doubleValue();
            }
            return score;
        } catch (Throwable t) {
            return score;
        }
    }
}