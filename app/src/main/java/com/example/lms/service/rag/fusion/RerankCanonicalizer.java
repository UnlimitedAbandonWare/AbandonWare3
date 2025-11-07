package com.example.lms.service.rag.fusion;

import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Canonicalize URLs by stripping common tracking parameters and normalizing
 * scheme/host/path.  This helper can be used by ranking engines to
 * deduplicate documents that refer to the same underlying resource.
 */
@Component
public class RerankCanonicalizer {
    private static final Set<String> DROP = Set.of(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "gclid", "fbclid", "mc_cid", "mc_eid", "_hsenc", "_hsmi"
    );

    /**
     * Return a canonical URL with tracking parameters removed.  If the
     * input cannot be parsed as a URI it is returned unchanged.
     *
     * @param url the original URL
     * @return the canonicalized URL
     */
    public String canonicalizeUrl(String url) {
        if (url == null) return null;
        try {
            URI u = new URI(url);
            String q = u.getQuery();
            if (q == null || q.isEmpty()) return url;
            Map<String,String> keep = new LinkedHashMap<>();
            for (String kv : q.split("&")) {
                int idx = kv.indexOf('=');
                String k = idx > 0 ? kv.substring(0, idx) : kv;
                if (!DROP.contains(k)) {
                    keep.put(k, idx > 0 ? kv.substring(idx + 1) : "");
                }
            }
            String nq = keep.isEmpty() ? null : keep.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "&" + b).orElse(null);
            return new URI(
                u.getScheme(),
                u.getAuthority(),
                u.getPath(),
                nq,
                u.getFragment()
            ).toString();
        } catch (URISyntaxException e) {
            return url;
        }
    }
}