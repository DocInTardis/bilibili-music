package com.example.bilibilimusic.controller;

import com.example.bilibilimusic.service.media.Mp3DownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/media/mp3")
@RequiredArgsConstructor
@Slf4j
public class Mp3DownloadController {

    private static final long CHUNK_SIZE = 1024 * 1024;

    private final Mp3DownloadService downloadService;

    @PostMapping
    public ResponseEntity<?> download(@RequestBody Mp3DownloadRequest request) {
        try {
            Mp3DownloadService.Mp3DownloadResult result = downloadService.downloadMp3(request.bvid, request.url);
            Path file = result.file();
            long size = safeSize(file);
            String streamUrl = "/api/media/mp3/" + result.bvid();
            String downloadUrl = "/api/media/mp3/" + result.bvid() + "/download";
            return ResponseEntity.ok(new Mp3DownloadResponse(
                result.bvid(),
                file.toString(),
                streamUrl,
                downloadUrl,
                size
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("MP3_UNAVAILABLE", e.getMessage()));
        } catch (Exception e) {
            log.error("MP3 下载失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("MP3_FAILED", "下载 MP3 失败"));
        }
    }

    @GetMapping("/{bvid}")
    public ResponseEntity<ResourceRegion> stream(@PathVariable String bvid,
                                                 @RequestHeader HttpHeaders headers) throws IOException {
        Path file = downloadService.resolveMp3Path(bvid);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        ResourceRegion region = resourceRegion(resource, headers);
        MediaType mediaType = MediaType.valueOf("audio/mpeg");
        HttpStatus status = headers.getRange().isEmpty() ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;
        return ResponseEntity.status(status)
            .contentType(mediaType)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .body(region);
    }

    @GetMapping("/{bvid}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable String bvid) throws IOException {
        Path file = downloadService.resolveMp3Path(bvid);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        String fileName = bvid + ".mp3";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
            .contentLength(resource.contentLength())
            .body(resource);
    }

    private ResourceRegion resourceRegion(Resource resource, HttpHeaders headers) throws IOException {
        long contentLength = resource.contentLength();
        List<HttpRange> ranges = headers.getRange();
        if (ranges.isEmpty()) {
            long length = Math.min(CHUNK_SIZE, contentLength);
            return new ResourceRegion(resource, 0, length);
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        long rangeLength = Math.min(CHUNK_SIZE, end - start + 1);
        return new ResourceRegion(resource, start, rangeLength);
    }

    private long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            log.warn("读取文件大小失败: {}", file, e);
            return -1L;
        }
    }

    public static class Mp3DownloadRequest {
        public String bvid;
        public String url;
    }

    public static class Mp3DownloadResponse {
        public String bvid;
        public String path;
        public String streamUrl;
        public String downloadUrl;
        public long size;

        public Mp3DownloadResponse(String bvid, String path, String streamUrl, String downloadUrl, long size) {
            this.bvid = bvid;
            this.path = path;
            this.streamUrl = streamUrl;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }
    }

    public static class ErrorResponse {
        public String error;
        public String message;

        public ErrorResponse(String error, String message) {
            this.error = error;
            this.message = message;
        }
    }
}
