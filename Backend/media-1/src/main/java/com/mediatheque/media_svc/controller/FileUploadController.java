package com.mediatheque.media_svc.controller;

import com.mediatheque.media_svc.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileStorageService fileStorageService;

    @PostMapping
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file) {
        try {
            System.out.println(" UPLOAD RECU ");
            System.out.println("Content-Type: " + file.getContentType());
            System.out.println("Size: " + file.getSize());
            System.out.println("Name: " + file.getOriginalFilename());
            String url = fileStorageService.store(file);
            System.out.println("UPLOAD OK : " + url + " ");
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            System.err.println("UPLOAD ERREUR : " + e.getMessage() + " ");
            e.printStackTrace();
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Upload échoué : " + e.getMessage()));
        }
    }
}