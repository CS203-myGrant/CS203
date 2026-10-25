package mygrant.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import org.springdoc.core.annotations.ParameterObject;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import mygrant.user.dto.ImmigrationDocumentResponse;

@RestController
@Tag(name = "Profile")
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
    @Operation(summary = "Upload an immigration document",
            description = "Stores a PDF, JPEG or PNG of up to 10 MB against the signed-in user.")
    @ApiResponse(responseCode = "201", description = "Uploaded")
    @ApiResponse(responseCode = "400", description = "The document type is missing or unknown, the file is "
            + "missing or empty, or it is not a PDF, JPEG or PNG")
    @ApiResponse(responseCode = "413", description = "The upload is larger than 10 MB")
    public ResponseEntity<ImmigrationDocumentResponse> upload(
            Authentication authentication,
            @Parameter(description = "Kind of document", schema = @Schema(allowableValues = {"PASSPORT",
                    "VISA_STAMP", "I20", "DS2019", "I94", "EAD", "I797", "I140", "PERM", "OTHER"}))
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
    @Operation(summary = "List the signed-in user's documents", description = "Newest upload first.")
    @ApiResponse(responseCode = "200", description = "A page of documents")
    @ApiResponse(responseCode = "400", description = "Unknown sort property")
    public Page<ImmigrationDocumentResponse> list(
            Authentication authentication,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return documentService.list(authentication.getName(), pageable);
    }

    @GetMapping("/api/v1/profiles/me/documents/{documentId}/download")
    @Operation(summary = "Download one of the signed-in user's documents",
            description = "Returned as an attachment with the type it was uploaded with.")
    @ApiResponse(responseCode = "200", description = "The file",
            content = @Content(mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "400", description = "No document with this id belongs to the user")
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