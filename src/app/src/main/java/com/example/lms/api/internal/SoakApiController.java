package com.example.lms.api.internal;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/internal/soak")
public class SoakApiController {
  @GetMapping("/run")
  public Map<String,Object> run(@RequestParam(defaultValue="10") int k,
                                @RequestParam(defaultValue="all") String topic) {
    return Map.of("k",k,"topic",topic,"status","OK");
  }
}