package com.example.lms.harmony;

import com.example.lms.debug.AblationPenaltyBootDumper;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HarmonyScoreControllerTest {

    @Test
    void apiControllerExposesScoreAndStreamRoutes() throws Exception {
        RequestMapping root = HarmonyScoreController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/harmony"}, root.value());

        assertArrayEquals(new String[]{"/score"},
                HarmonyScoreController.class.getMethod("getScore").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/stream"},
                HarmonyScoreController.class.getMethod("stream").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/push"},
                HarmonyScoreController.class.getMethod("push").getAnnotation(GetMapping.class).value());
    }

    @Test
    void scoreEndpointReturnsSnapshotBody() {
        HarmonyScoreController controller = new HarmonyScoreController(new HarmonyScoreEngine(
                new HarmonyBreakLedger(),
                new ContaminationAccumulator()));

        ResponseEntity<HarmonyScoreSnapshot> response = controller.getScore();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(100.0d, response.getBody().goalPoint(), 0.0001d);
    }

    @Test
    void scoreEndpointSeesBootAblationPenaltyBaselineAcrossRequestTraceBoundary() {
        TraceStore.clear();
        new AblationPenaltyBootDumper(new MockEnvironment()
                .withProperty("uaw.ablation.penalty.default", "0.20"))
                .onApplicationEvent(null);
        TraceStore.clear();

        HarmonyScoreController controller = new HarmonyScoreController(new HarmonyScoreEngine(
                new HarmonyBreakLedger(),
                new ContaminationAccumulator()));

        HarmonyScoreSnapshot snapshot = controller.getScore().getBody();

        assertNotNull(snapshot);
        HarmonyScoreSnapshot.HarmonyBreakEntry hb01 = snapshot.harmonyBreaks().stream()
                .filter(entry -> "HB-01".equals(entry.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("DONE", hb01.status());
    }

    @Test
    void pageControllerRoutesToHarmonyDashboardTemplate() {
        HarmonyDashboardPageController controller = new HarmonyDashboardPageController();

        assertEquals("harmony-dashboard", controller.dashboard());
    }
}
