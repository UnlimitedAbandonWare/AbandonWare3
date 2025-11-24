
package com.rc111.merge21.web;

import com.rc111.merge21.qa.ChatAnswerService;
import com.rc111.merge21.qa.Answer;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChatController {
    private final ChatAnswerService service;
    public ChatController(ChatAnswerService service) { this.service = service; }

    @PostMapping("/chat")
    public Answer chat(@RequestBody Prompt p) {
        return service.answer(p.query);
    }

    public static class Prompt { public String query; }
}
