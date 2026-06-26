package com.example.lms.api;

import com.example.lms.service.ChannelRecipientService;
import com.example.lms.search.TraceStore;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/channels")
@RequiredArgsConstructor
public class ChannelRecipientController {

    private static final String SESSION_TOKEN = "channelAccessToken";
    private static final String DISABLED_REASON = "missing_access_token";

    private final ChannelRecipientService recipientService;

    @Value("${channel.recipients.page-size:${kakao.friends.page-size:10}}")
    private int pageSize;

    @GetMapping("/recipients")
    public String showRecipients(
            @RequestParam(name = "page", defaultValue = "1") int page,
            HttpSession session,
            @ModelAttribute("result") String result,
            @ModelAttribute("error") String error,
            Model model) {

        String accessToken = (String) session.getAttribute(SESSION_TOKEN);
        if (accessToken == null || accessToken.isBlank()) {
            traceMissingAccessToken(Math.max(1, page));
            model.addAttribute("recipients", List.of());
            model.addAttribute("page", Math.max(1, page));
            model.addAttribute("hasPrev", false);
            model.addAttribute("hasNext", false);
            model.addAttribute("error", error == null || error.isBlank()
                    ? "channel recipient provider is not configured"
                    : error);
            model.addAttribute("result", result);
            return "channels/recipients";
        }

        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safePageSize;
        List<String> recipients = recipientService.fetchRecipientIds(accessToken, offset, safePageSize);

        model.addAttribute("recipients", recipients);
        model.addAttribute("page", safePage);
        model.addAttribute("hasPrev", safePage > 1);
        model.addAttribute("hasNext", recipients.size() == safePageSize);
        model.addAttribute("result", result);
        model.addAttribute("error", error);
        return "channels/recipients";
    }

    @PostMapping("/recipients")
    public String movePage(@RequestParam int page, RedirectAttributes redir) {
        redir.addAttribute("page", page);
        return "redirect:/channels/recipients";
    }

    private static void traceMissingAccessToken(int safePage) {
        TraceStore.put("channel.recipients.controller.providerDisabled", true);
        TraceStore.put("channel.recipients.controller.disabledReason", DISABLED_REASON);
        TraceStore.put("channel.recipients.controller.skipped.reason", DISABLED_REASON);
        TraceStore.put("channel.recipients.controller.requestedPage", safePage);
        TraceStore.put("channel.recipients.controller.returnedCount", 0);
    }
}
