package com.example.lms.service.rag.extract;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;




@Component
public class PageContentScraper {
    private static final Logger log = LoggerFactory.getLogger(PageContentScraper.class);

    @Value("${search.budget.per-page-ms:3500}")
    private int defaultPerPageMs;

    /**
     * URL의 HTML을 받아 본문 텍스트만 최대 길이로 깔끔히 반환.
     */
    public String fetchText(String url) {
        return fetchText(url, defaultPerPageMs);
    }

    /**
     * Fetches the text contents of the given URL within a configurable timeout.
     * When the timeout is reached or any error occurs, this method returns
     * {@code null} to signal that the caller should continue gracefully.
     *
     * @param url       the page URL to scrape
     * @param perPageMs the timeout in milliseconds applied to the request
     * @return the extracted and trimmed text, or {@code null} if the page could not be fetched
     */
    public String fetchText(String url, int perPageMs) {
        int timeoutMs = Math.max(1000, perPageMs);
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; AbandonWareBot/1.0)")
                    .timeout(timeoutMs)
                    .get();
            String text = doc.text();
            return (text != null) ? text.strip() : null;
        } catch (Exception e) {
            // Allow partial success by returning null on any failure
            traceFetchFailure(url, timeoutMs, e);
            return null;
        }
    }

    private static void traceFetchFailure(String url, int timeoutMs, Exception ex) {
        String errorType = ex == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
        TraceStore.put("web.pageScraper.fetchText.failed", true);
        TraceStore.put("web.pageScraper.fetchText.stage", "fetchText");
        TraceStore.put("web.pageScraper.fetchText.errorType", errorType);
        TraceStore.put("web.pageScraper.fetchText.urlHash12", SafeRedactor.hash12(url));
        TraceStore.put("web.pageScraper.fetchText.timeoutMs", Math.max(1000, timeoutMs));
        log.debug("[PageContentScraper] fail-soft stage={}", "fetchText");
        log.debug("[PageContentScraper] fail-soft errorType={}", errorType);
    }
}
