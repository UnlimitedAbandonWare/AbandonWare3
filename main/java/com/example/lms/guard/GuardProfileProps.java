package com.example.lms.guard;

import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GuardProfileProps {

    @Value("${jammini.guard.profile:PROFILE_MEMORY}")
    private String profile;

    public GuardProfile currentProfile() {
        try {
            return GuardProfile.valueOf(profile.toUpperCase());
        } catch (IllegalArgumentException e) {
            traceSuppressed("guardProfile.currentProfile", e);
            return GuardProfile.PROFILE_MEMORY;
        }
    }

    public void setProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return;
        }
        this.profile = profile;
    }

    /**
     * Map QueryDomain to GuardProfile for EvidenceAwareGuard.
     */
    public GuardProfile profileFor(QueryDomain domain) {
        if (domain == null) {
            return GuardProfile.PROFILE_MEMORY;
        }
        return switch (domain) {
            case SENSITIVE -> GuardProfile.PROFILE_MEMORY;
            case GAME, SUBCULTURE -> GuardProfile.PROFILE_FREE;
            case STUDY, GENERAL -> GuardProfile.PROFILE_HEX;
        };
    }

    /**
     * Convenience setter for current GuardProfile (used by ChatService).
     */
    public void setCurrentProfile(GuardProfile profile) {
        if (profile != null) {
            this.profile = profile.name();
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("guard.profile.suppressed." + safeStage, true);
        TraceStore.put("guard.profile.suppressed." + safeStage + ".errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
    }
}
