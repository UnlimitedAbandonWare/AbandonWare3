package ai.abandonware.nova.boot.log;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NovaNoiseTurboFilterTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void invalidSafeIntUsesStableReasonCodeWithoutRawValue() throws Exception {
        Method method = NovaNoiseTurboFilter.class.getDeclaredMethod("safeInt", Object.class, int.class);
        method.setAccessible(true);

        int value = (Integer) method.invoke(null, "ownerToken-not-an-int", -1);

        assertEquals(-1, value);
        assertEquals("safe_int", TraceStore.get("nova.noiseFilter.fallback.stage"));
        assertEquals("invalid_number", TraceStore.get("nova.noiseFilter.fallback.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken-not-an-int"));
        assertFalse(trace.contains("NumberFormatException"));
    }
}
