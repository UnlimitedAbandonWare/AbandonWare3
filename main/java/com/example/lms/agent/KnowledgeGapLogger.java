package com.example.lms.agent;

import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

@Service
public class KnowledgeGapLogger {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGapLogger.class);

    public static class GapEvent {
        private final String query;
        private final String domain;
        private final String subject;
        private final String intent;
        private final Instant timestamp;

        public GapEvent(String query, String domain, String subject, String intent) {
            this.query = query == null ? "" : query.trim();
            this.domain = domain == null ? "" : domain.trim();
            this.subject = subject == null ? "" : subject.trim();
            this.intent = intent == null ? "" : intent.trim();
            this.timestamp = Instant.now();
        }

        // 👉 Lombok이 안 먹어도 되도록 “수동 게터” 추가
        @JsonIgnore
        public String getQuery()     { return query; }
        @JsonIgnore
        public String getDomain()    { return domain; }
        @JsonIgnore
        public String getSubject()   { return subject; }
        @JsonIgnore
        public String getIntent()    { return intent; }
        @JsonIgnore
        public Instant getTimestamp(){ return timestamp; }

        public String getQueryHash() { return SafeRedactor.hashValue(query); }
        public int getQueryLength()  { return query.length(); }
        public String getDomainHash(){ return SafeRedactor.hashValue(domain); }
        public int getDomainLength() { return domain.length(); }
        public String getSubjectHash(){ return SafeRedactor.hashValue(subject); }
        public int getSubjectLength(){ return subject.length(); }
        public String getIntentHash(){ return SafeRedactor.hashValue(intent); }
        public int getIntentLength() { return intent.length(); }
        public long getTimestampEpochMs(){ return timestamp.toEpochMilli(); }
    }

    private final ConcurrentLinkedQueue<GapEvent> events = new ConcurrentLinkedQueue<>();

    public void logEvent(String query, String domain, String subject, String intent) {
        GapEvent evt = new GapEvent(query, domain, subject, intent);
        events.add(evt);
        log.debug("[KnowledgeGapLogger] Recorded gap: queryHash={}, queryLength={}, domainHash={} domainLength={} subjectHash={} subjectLength={} intentHash={} intentLength={}",
                evt.getQueryHash(), evt.getQueryLength(),
                evt.getDomainHash(), evt.getDomainLength(),
                evt.getSubjectHash(), evt.getSubjectLength(),
                evt.getIntentHash(), evt.getIntentLength());
    }

    public Optional<GapEvent> poll() { return Optional.ofNullable(events.poll()); }

    public List<GapEvent> snapshot() { return events.stream().collect(Collectors.toList()); }

    /**
     * Returns at most the most recent {@code limit} gap events.
     *
     * <p>Insertion order is preserved (oldest→newest within the returned list).
     * This is intentionally fail-soft and avoids leaking raw chat context.
     * </p>
     */
    public List<GapEvent> snapshotRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<GapEvent> all = snapshot();
        int size = all.size();
        if (size <= limit) {
            return all;
        }
        int from = Math.max(0, size - limit);
        return new java.util.ArrayList<>(all.subList(from, size));
    }

    public void clear() { events.clear(); }
}
