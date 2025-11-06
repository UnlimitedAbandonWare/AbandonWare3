package com.example.lms.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/probe")
public class SearchProbeController {

  @Value("${probe.search.enabled:false}")
  private boolean enabled;

  @Value("${probe.admin-token:}")
  private String adminToken;

  @PostMapping("/search")
  public Map<String,Object> search(@RequestBody Map<String,Object> req,
                                   @RequestHeader(value="X-Admin-Token", required=false) String adminHeader,
                                   @RequestHeader(value="X-Probe-Token", required=false) String probeHeader) {
    if (!enabled) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Probe API disabled");
    }
    if (adminToken != null && !adminToken.isBlank()) {
      String token = (adminHeader != null && !adminHeader.isBlank()) ? adminHeader : probeHeader;
      if (token == null || !adminToken.equals(token)) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing admin token");
      }
    }
    // Echo-only minimal implementation (replica path)
    return Map.of("replica","ok","echo",req);
  }
}