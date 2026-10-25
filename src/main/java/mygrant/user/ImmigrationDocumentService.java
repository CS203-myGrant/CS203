package mygrant.user;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import mygrant.common.DocumentValidationException;
import mygrant.user.dto.ImmigrationDocumentResponse;

@Service
public class ImmigrationDocumentService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    private final UserRepository userRepository;
    private final ImmigrationDocumentRepository documentRepository;

    public ImmigrationDocumentService(
            UserRepository userRepository,
            ImmigrationDocumentRepository documentRepository) {
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public ImmigrationDocumentResponse upload(
            String email,
            String documentTypeValue,
            MultipartFile file) throws IOException {

        validateFile(file);

        ImmigrationDocumentType documentType;
        try {
            documentType = ImmigrationDocumentType.from(documentTypeValue);
        } catch (IllegalArgumentException exception) {
            throw new DocumentValidationException(
                    Map.of("documentType", exception.getMessage())
            );
        }

        User user = currentUser(email);
        String fileName = safeFileName(file.getOriginalFilename());

        ImmigrationDocument document = new ImmigrationDocument(
                user.getId(),
                documentType,
                fileName,
                file.getContentType(),
                file.getSize(),
                file.getBytes()
        );

        ImmigrationDocument saved = documentRepository.save(document);
        return ImmigrationDocumentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<ImmigrationDocumentResponse> list(String email, Pageable pageable) {
        User user = currentUser(email);

        return documentRepository.findByUserIdOrderByUploadedAtDesc(user.getId(), pageable)
                .map(ImmigrationDocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public ImmigrationDocument download(String email, Long documentId) {
        User user = currentUser(email);

        return documentRepository.findByIdAndUserId(documentId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
    }

    private User currentUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalStateException("Authenticated user no longer exists"));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentValidationException(
                    Map.of("file", "A file is required")
            );
        }

        String contentType = file.getContentType();

        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new DocumentValidationException(
                    Map.of("file", "Only PDF, JPEG, and PNG files are allowed")
            );
        }
    }

    private String safeFileName(String originalFileName) {
        String suppliedName = Optional.ofNullable(originalFileName)
                .filter(name -> !name.isBlank())
                .orElse("upload");

        return Paths.get(suppliedName).getFileName().toString();
    }
}