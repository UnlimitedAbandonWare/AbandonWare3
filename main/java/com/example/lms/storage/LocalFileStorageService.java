// src/main/java/com/example/lms/storage/LocalFileStorageService.java
package com.example.lms.storage;

import com.example.lms.storage.FileStorageService;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Service
@RequiredArgsConstructor
public class LocalFileStorageService implements FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    /** application.yml 에서 설정 → 기본값은 project-root/uploads */
    @Value("${lms.upload-dir:uploads}")
    private String rootDir;

    @Value("${lms.upload.max-bytes:10485760}")
    private long maxBytes;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".md", ".pdf", ".png", ".jpg", ".jpeg", ".webp", ".csv", ".json");

    @Override
    public String save(MultipartFile file, String subPath) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }

        if (maxBytes > 0 && file.getSize() > maxBytes) {
            throw new IllegalArgumentException("파일 크기 제한을 초과했습니다.");
        }

        String originalName = StringUtils.cleanPath(String.valueOf(file.getOriginalFilename()));
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("허용되지 않은 파일 형식입니다.");
        }

        Path root = Path.of(rootDir).toAbsolutePath().normalize();
        Path targetDir = root.resolve(safeSubPath(subPath)).normalize();
        if (!targetDir.startsWith(root)) {
            throw new IllegalArgumentException("허용되지 않은 업로드 경로입니다.");
        }
        String filename = UUID.randomUUID() + extension;
        Path targetFile = targetDir.resolve(filename).normalize();
        if (!targetFile.startsWith(root)) {
            throw new IllegalArgumentException("허용되지 않은 업로드 파일 경로입니다.");
        }

        try {
            Files.createDirectories(targetDir);
            Files.copy(file.getInputStream(), targetFile);
            String relative = root.relativize(targetFile).toString().replace('\\', '/');
            log.info("파일 저장 완료 root={} relative={}", root.getFileName(), relative);
            return "/uploads/" + relative;
        } catch (IOException e) {
            log.error("파일 저장 실패. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            throw new RuntimeException("파일 저장 실패", e);
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String safeSubPath(String subPath) {
        if (subPath == null || subPath.isBlank()) {
            return "";
        }
        return subPath.replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("[^A-Za-z0-9._/-]", "_");
    }
}
