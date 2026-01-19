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
    public ResponseEntity<Mp3DownloadResponse> download(@RequestBody Mp3DownloadRequest request) {
        Mp3DownloadService.Mp3DownloadResult result = downloadService.downloadMp3(request.bvid, request.url);
        Path file = result.file();
        long size = safeSize(file);
        return ResponseEntity.ok(new Mp3DownloadResponse(
            result.bvid(),
            file.toString(),
            "/api/media/mp3/" + result.bvid(),
            size
        ));
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
        public String downloadUrl;
        public long size;

        public Mp3DownloadResponse(String bvid, String path, String downloadUrl, long size) {
            this.bvid = bvid;
            this.path = path;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }
    }
}
