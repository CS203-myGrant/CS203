package mygrant.user.dto;

import java.time.Instant;

import mygrant.user.ImmigrationDocument;
import mygrant.user.ImmigrationDocumentType;

public record ImmigrationDocumentResponse(
        Long id,
        ImmigrationDocumentType documentType,
        String fileName,
        String contentType,
        long sizeBytes,
        Instant uploadedAt
) {
    public static ImmigrationDocumentResponse from(ImmigrationDocument document) {
        return new ImmigrationDocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getFileName(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getUploadedAt()
        );
    }
}