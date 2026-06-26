package com.example.lms.service.rag.extract;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PageContentScraperTest {

    @Test
    void fetchFailureReturnsNullAndLeavesRedactedTraceBreadcrumb() {
        PageContentScraper scraper = new PageContentScraper();

        TraceStore.clear();
        String text = scraper.fetchText("http://[raw-page-secret", 10);

        assertNull(text);
        assertEquals(true, TraceStore.get("web.pageScraper.fetchText.failed"));
        assertEquals("fetchText", TraceStore.get("web.pageScraper.fetchText.stage"));
        assertEquals(1000, TraceStore.get("web.pageScraper.fetchText.timeoutMs"));
        assertFalse(TraceStore.getAll().toString().contains("raw-page-secret"));
    }
}
