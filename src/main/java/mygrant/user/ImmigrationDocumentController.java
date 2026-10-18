package mygrant.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import mygrant.user.dto.ImmigrationDocumentResponse;

@RestController
public class ImmigrationDocumentController {

    private final ImmigrationDocumentService documentService;

    public ImmigrationDocumentController(
            ImmigrationDocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(
            value = "/api/v1/profiles/me/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ImmigrationDocumentResponse> upload(
            Authentication authentication,
            @RequestParam String documentType,
            @RequestPart MultipartFile file) throws IOException {

        ImmigrationDocumentResponse response = documentService.upload(
                authentication.getName(),
                documentType,
                file
        );

        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/api/v1/profiles/me/documents")
    public List<ImmigrationDocumentResponse> list(
            Authentication authentication) {
        return documentService.list(authentication.getName());
    }

    @GetMapping("/api/v1/profiles/me/documents/{documentId}/download")
    public ResponseEntity<byte[]> download(
            Authentication authentication,
            @PathVariable Long documentId) {

        ImmigrationDocument document = documentService.download(
                authentication.getName(),
                documentId
        );

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(document.getFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(document.getContent());
    }
}