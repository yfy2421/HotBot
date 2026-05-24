package com.bot.controller;

import com.bot.service.NewsCardRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final NewsCardRenderer newsCardRenderer;

    @GetMapping(value = "/cards/{fileName:.+}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<Resource> getCard(@PathVariable String fileName) {
        Path cardPath = newsCardRenderer.resolveCardPath(fileName);
        if (cardPath == null || !Files.exists(cardPath) || !Files.isRegularFile(cardPath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            Resource resource = new FileSystemResource(cardPath);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .contentLength(Files.size(cardPath))
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}