
package com.example.lms.guard;

import org.springframework.stereotype.Component;

/** Detects low-confidence conditions to flip Overdrive/Anger mode. */
@Component
public class OverdriveGuard {
    public boolean shouldOverdrive(double coverage, double authorityScore){
        return coverage < 0.4 || authorityScore < 0.3;
    }
}