package com.example.lms.plugin.image.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class FileSystemImageStorage {

    private static final System.Logger LOG = System.getLogger(FileSystemImageStorage.class.getName());

    private static final long DEFAULT_MAX_DOWNLOAD_BYTES = 10L * 1024L * 1024L;

    private final ImageStorageProperties props;

    @Qualifier("openaiWebClient")
    private final WebClient openaiWebClient;

    public record Stored(String absolutePath, String publicUrl) {}

    public Stored saveBase64Png(String b64, String hint) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(b64);
        return write(bytes, ".png");
    }

    public Stored downloadToStorage(String url, String hint) throws IOException {
        URI uri = requireHttpsUri(url);
        try {
            Stored stored = openaiWebClient.get()
                    .uri(uri)
                    .exchangeToMono(response -> {
                        HttpStatusCode status = response.statusCode();
                        if (status.isError()) {
                            return response.releaseBody()
                                    .then(Mono.error(new ImageDownloadException("image download failed: status=" + status.value())));
                        }

                        MediaType contentType = response.headers().contentType().orElse(null);
                        String extension = extensionFor(contentType);
                        if (extension == null) {
                            return response.releaseBody()
                                    .then(Mono.error(new ImageDownloadException("unsupported image content type")));
                        }

                        long limit = maxDownloadBytes(props);
                        long contentLength = response.headers().contentLength().orElse(-1L);
                        if (contentLength > limit) {
                            return response.releaseBody()
                                    .then(Mono.error(new ImageDownloadException("downloaded image exceeds configured size limit")));
                        }

                        try {
                            Destination destination = prepareDestination(extension);
                            return writeStream(response.bodyToFlux(DataBuffer.class), destination, limit);
                        } catch (IOException ex) {
                            LOG.log(System.Logger.Level.DEBUG,
                                    "Image storage download preparation skipped stage=prepare_destination errorType="
                                            + ex.getClass().getSimpleName());
                            return response.releaseBody().then(Mono.error(new ImageDownloadException(ex)));
                        }
                    })
                    .block();
            if (stored == null) {
                throw new IOException("image body is empty");
            }
            return stored;
        } catch (ImageDownloadException ex) {
            throw ex.toIOException();
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof ImageDownloadException imageEx) {
                throw imageEx.toIOException();
            }
            if (ex.getCause() instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("image download failed", ex);
        }
    }

    public static Path resolveRoot(ImageStorageProperties props) {
        String root = props == null ? null : props.root();
        if (root == null || root.isBlank()) {
            return Paths.get(System.getProperty("user.home"), "Pictures", "AbandonWare", "img")
                    .toAbsolutePath()
                    .normalize();
        }
        return Paths.get(root).toAbsolutePath().normalize();
    }

    public static String publicPrefix(ImageStorageProperties props) {
        String prefix = props == null ? null : props.publicPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "/generated-images/";
        }
        prefix = prefix.trim().replace('\\', '/');
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix;
    }

    public static long maxDownloadBytes(ImageStorageProperties props) {
        Long configured = props == null ? null : props.maxDownloadBytes();
        if (configured == null || configured <= 0) {
            return DEFAULT_MAX_DOWNLOAD_BYTES;
        }
        return configured;
    }

    private Stored write(byte[] bytes, String extension) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("image body is empty");
        }

        Destination destination = prepareDestination(extension);
        Files.write(destination.path(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return destination.toStored();
    }

    private Mono<Stored> writeStream(Flux<DataBuffer> body, Destination destination, long limit) {
        AtomicLong total = new AtomicLong();
        Flux<DataBuffer> limited = body.handle((buffer, sink) -> {
            long next = total.addAndGet(buffer.readableByteCount());
            if (next > limit) {
                DataBufferUtils.release(buffer);
                sink.error(new ImageDownloadException("downloaded image exceeds configured size limit"));
                return;
            }
            sink.next(buffer);
        });

        return DataBufferUtils.write(limited, destination.path(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .thenReturn(destination.toStored())
                .doOnError(error -> deleteQuietly(destination.path()));
    }

    private Destination prepareDestination(String extension) throws IOException {
        String safeExtension = normalizeExtension(extension);
        Path root = resolveRoot(props);
        Path dir = root.resolve(LocalDate.now().toString()).normalize();
        if (!dir.startsWith(root)) {
            throw new IOException("image storage directory escaped root");
        }
        Files.createDirectories(dir);

        Path dst = dir.resolve(UUID.randomUUID() + safeExtension).normalize();
        if (!dst.startsWith(root)) {
            throw new IOException("image storage path escaped root");
        }

        String publicUrl = publicPrefix(props) + dir.getFileName() + "/" + dst.getFileName();
        return new Destination(dst, publicUrl);
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return ".png";
        }
        String ext = extension.trim().toLowerCase();
        return ext.startsWith(".") ? ext : "." + ext;
    }

    private static String extensionFor(MediaType mediaType) {
        if (mediaType == null) {
            return null;
        }
        if (mediaType.isCompatibleWith(MediaType.IMAGE_PNG)) {
            return ".png";
        }
        if (mediaType.isCompatibleWith(MediaType.IMAGE_JPEG)) {
            return ".jpg";
        }
        if ("image".equalsIgnoreCase(mediaType.getType())
                && "webp".equalsIgnoreCase(mediaType.getSubtype())) {
            return ".webp";
        }
        return null;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Image storage cleanup skipped stage=delete_temp errorType=" + ex.getClass().getSimpleName());
        }
    }

    private static URI requireHttpsUri(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("image URL is blank");
        }
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IOException("image download URL must be https");
            }
            if (uri.getUserInfo() != null) {
                throw new IOException("image download URL must not contain user info");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid image download URL", ex);
        }
    }

    private record Destination(Path path, String publicUrl) {
        Stored toStored() {
            return new Stored(path.toAbsolutePath().toString(), publicUrl);
        }
    }

    private static final class ImageDownloadException extends RuntimeException {
        ImageDownloadException(String message) {
            super(message);
        }

        ImageDownloadException(IOException cause) {
            super(cause.getMessage(), cause);
        }

        IOException toIOException() {
            Throwable cause = getCause();
            if (cause instanceof IOException ioEx) {
                return ioEx;
            }
            return new IOException(getMessage(), this);
        }
    }
}
