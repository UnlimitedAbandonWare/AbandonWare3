package com.example.lms.service.rag.fusion;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;

/**
 * Simple fallback calibrator to map heterogeneous scores into [0,1].  If the
 * input distribution has sufficient variety a linear min/max normalization
 * is applied; otherwise a logistic squashing is used as a fallback.
 */
@Component
public class ScoreCalibrator {
    public double normalize(double v, double min, double max) {
        if (Double.isNaN(v)) return 0.0;
        if (max <= min) return sigmoid(v);
        double x = (v - min) / (max - min);
        if (x < 0) x = 0;
        if (x > 1) x = 1;
        return x;
    }
    public double normalizeBySample(double v, List<Double> sample) {
        double mn = sample.stream().mapToDouble(d -> d).min().orElse(0.0);
        double mx = sample.stream().mapToDouble(d -> d).max().orElse(1.0);
        return normalize(v, mn, mx);
    }
    public static double sigmoid(double x) {
        double e = Math.exp(Math.max(Math.min(x, 20), -20));
        return e / (1 + e);
    }
}