package com.sashkolearn.analyzeagent.domain.service;

import com.sashkolearn.analyzeagent.util.VectorUtils;
import com.sashkolearn.analyzeagent.config.NotesConfig;
import com.sashkolearn.analyzeagent.domain.entity.Attachment;
import com.sashkolearn.analyzeagent.domain.entity.Note;
import com.sashkolearn.analyzeagent.domain.repository.AttachmentRepository;
import com.sashkolearn.analyzeagent.domain.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    private final NotesConfig notesConfig;
    private final NoteRepository noteRepository;
    private final AttachmentRepository attachmentRepository;
    private final ImageReferenceParserService imageParser;
    private final ClaudeVisionService claudeVisionService;
    private final EmbeddingService embeddingService;

    /**
     * Processes image attachments for the given notes.
     * Extracts image references, analyzes them with Claude Vision,
     * and stores descriptions and embeddings.
     *
     * @param changedNoteIds list of note IDs to process
     * @return processing result
     */
    @Transactional
    public AttachmentResult processAttachmentsForNotes(List<UUID> changedNoteIds) {
        log.info("Processing attachments for {} notes", changedNoteIds.size());

        Path notesPath = Paths.get(notesConfig.getPath());
        Path imgPath = notesPath.resolve("img");

        int processed = 0;
        int skipped = 0;
        int errors = 0;

        for (UUID noteId : changedNoteIds) {
            var noteOpt = noteRepository.findById(noteId);
            if (noteOpt.isEmpty()) {
                log.warn("Note not found: {}", noteId);
                continue;
            }

            Note note = noteOpt.get();
            List<String> imageRefs = imageParser.extractImageReferences(note.getContent());

            for (String imageFileName : imageRefs) {
                try {
                    if (attachmentRepository.existsByFileName(imageFileName)) {
                        log.debug("Image already processed, skipping: {}", imageFileName);
                        skipped++;
                        continue;
                    }

                    Path imagePath = imgPath.resolve(imageFileName);
                    if (!Files.exists(imagePath)) {
                        log.warn("Image not found: {}", imagePath);
                        errors++;
                        continue;
                    }

                    String description = claudeVisionService.describeImage(imagePath, note.getContent());

                    Attachment attachment = Attachment.builder()
                            .fileName(imageFileName)
                            .noteId(noteId)
                            .filePath(imagePath.toString())
                            .description(description)
                            .build();
                    attachmentRepository.save(attachment);

                    if (description != null && !description.isEmpty()) {
                        float[] embedding = embeddingService.generateEmbedding(description);
                        String embeddingStr = VectorUtils.toVectorString(embedding);
                        attachmentRepository.updateEmbedding(imageFileName, embeddingStr);
                    }

                    processed++;
                    log.info("Processed image: {}", imageFileName);

                } catch (Exception e) {
                    log.error("Failed to process image {}: {}", imageFileName, e.getMessage());
                    errors++;
                }
            }
        }

        AttachmentResult result = new AttachmentResult(processed, skipped, errors);
        log.info("Attachment processing completed: {}", result);
        return result;
    }

    /**
     * Gets all attachment descriptions for a note (for embedding enrichment).
     *
     * @param noteId the note ID
     * @return list of attachments with descriptions
     */
    public List<Attachment> getAttachmentsForNote(UUID noteId) {
        return attachmentRepository.findByNoteId(noteId);
    }

    public record AttachmentResult(int processed, int skipped, int errors) {
    }
}
