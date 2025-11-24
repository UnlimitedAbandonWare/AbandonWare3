package com.abandonware.ai.service.rag.fusion;

/** Alias shim for legacy references to MPLawNormalizer */
public final class MPLawNormalizer {
  private MPLawNormalizer() {}
  public static double clamp(double x, double beta) {
    return MpLawNormalizer.clamp(x, beta);
  }
}
