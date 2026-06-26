package com.example.lms.api;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.conversation.archive.ConversationArchiveIngestReport;
import com.example.lms.conversation.archive.ConversationArchiveIngestService;
import com.example.lms.service.AttachmentInspectionService;
import com.example.lms.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;




/**
 * 泥⑤? ?뚯씪 ?낅줈????젣瑜?泥섎━?섎뒗 REST 而⑦듃濡ㅻ윭.
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final AttachmentInspectionService attachmentInspectionService;
    private final ConversationArchiveIngestService conversationArchiveIngestService;

    /**
     * ?щ윭 ?뚯씪???낅줈?쒗븯怨?AttachmentDto 紐⑸줉??諛섑솚?⑸땲??
     * @param files multipart/form-data 濡??꾩넚???뚯씪??     * @return ?낅줈?쒕맂 ?뚯씪?ㅼ쓽 硫뷀? ?뺣낫
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<AttachmentDto> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        // Delegate to the AttachmentService.  When sessionId is provided cache the
        // attachments against the session so they can be retrieved later via the
        // AttachmentContextHandler.  When not provided, simply save the files.
        if (sessionId == null || sessionId.isBlank()) {
            return attachmentService.saveAll(files);
        } else {
            return attachmentService.saveAll(files, sessionId);
        }
    }

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> inspect(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        List<MultipartFile> safeFiles = files == null ? List.of() : files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();
        List<AttachmentDto> saved = (sessionId == null || sessionId.isBlank())
                ? attachmentService.saveAll(safeFiles)
                : attachmentService.saveAll(safeFiles, sessionId);
        return attachmentInspectionService.inspect(safeFiles, sessionId, saved);
    }

    @PostMapping(value = "/conversation-archive/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ConversationArchiveIngestReport ingestConversationArchive(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        return conversationArchiveIngestService.ingest(files, sessionId);
    }

    /**
     * ?뱀젙 泥⑤?瑜??쒓굅?⑸땲?? ?꾩옱??硫뷀? ??μ냼?먯꽌留??쒓굅?섍퀬 ?뚯씪 ??젣???섑뻾?섏? ?딆뒿?덈떎.
     * @param id 泥⑤? ID
     */
    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable String id,
            @RequestParam(value = "sessionId", required = false) String sessionId
    ) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_session");
        }
        if (!attachmentService.deleteForSession(id, sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment_not_found");
        }
    }
}
