package com.sashkolearn.analyzeagent.domain.service;

import com.sashkolearn.analyzeagent.config.NotesConfig;
import com.sashkolearn.analyzeagent.domain.entity.Attachment;
import com.sashkolearn.analyzeagent.domain.entity.Note;
import com.sashkolearn.analyzeagent.domain.repository.AttachmentRepository;
import com.sashkolearn.analyzeagent.domain.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class NoteSyncService {

    private final NotesConfig notesConfig;
    private final NoteRepository noteRepository;
    private final AttachmentRepository attachmentRepository;
    private final EmbeddingService embeddingService;

    /**
     * Synchronizes all markdown files from notes folder
     *
     * Process:
     * 1. Scans folder recursively
     * 2. Loads new/updated files into DB
     * 3. Deletes records from DB if file doesn't exist on disk
     *
     * @return sync result
     */
    @Transactional
    public SyncResult syncNotes() {
        log.info("Starting notes synchronization from: {}", notesConfig.getPath());

        Path notesPath = Paths.get(notesConfig.getPath());

        if (!Files.exists(notesPath) || !Files.isDirectory(notesPath)) {
            throw new IllegalStateException("Notes directory does not exist: " + notesPath);
        }

        List<Path> markdownFiles = findAllMarkdownFiles(notesPath);
        log.info("Found {} markdown files", markdownFiles.size());

        // Collect all file paths from disk
        Set<String> diskFilePaths = markdownFiles.stream()
            .map(path -> path.toAbsolutePath().toString())
            .collect(Collectors.toSet());

        int newNotes = 0;
        int updatedNotes = 0;
        int skippedNotes = 0;
        int errorNotes = 0;
        List<UUID> changedNoteIds = new ArrayList<>();

        // Sync files from disk to DB
        for (Path file : markdownFiles) {
            try {
                SyncActionResult actionResult = syncSingleNote(file);
                switch (actionResult.action()) {
                    case CREATED -> {
                        newNotes++;
                        changedNoteIds.add(actionResult.noteId());
                    }
                    case UPDATED -> {
                        updatedNotes++;
                        changedNoteIds.add(actionResult.noteId());
                    }
                    case SKIPPED -> skippedNotes++;
                }
            } catch (Exception e) {
                log.error("Failed to sync note: {}", file, e);
                errorNotes++;
            }
        }

        // Delete notes from DB that no longer exist on disk
        int deletedNotes = deleteNotesNotOnDisk(diskFilePaths);

        SyncResult result = new SyncResult(
            markdownFiles.size(),
            newNotes,
            updatedNotes,
            skippedNotes,
            errorNotes,
            deletedNotes,
            changedNoteIds
        );

        log.info("Sync completed: {}", result);
        return result;
    }

    /**
     * Generates embeddings for all notes without embeddings
     */
    @Transactional
    public int generateMissingEmbeddings() {
        List<Note> notesWithoutEmbedding = noteRepository.findNotesWithoutEmbedding();
        log.info("Found {} notes without embeddings", notesWithoutEmbedding.size());

        if (notesWithoutEmbedding.isEmpty()) {
            return 0;
        }

        int batchSize = notesConfig.getSync().getBatchSize();
        int processedCount = 0;

        // Process in batches to optimize OpenAI API calls
        for (int i = 0; i < notesWithoutEmbedding.size(); i += batchSize) {
            int end = Math.min(i + batchSize, notesWithoutEmbedding.size());
            List<Note> batch = notesWithoutEmbedding.subList(i, end);

            log.info("Processing batch {}/{} (size: {})",
                (i / batchSize) + 1,
                (notesWithoutEmbedding.size() + batchSize - 1) / batchSize,
                batch.size()
            );

            try {
                processBatchEmbeddings(batch);
                processedCount += batch.size();
            } catch (Exception e) {
                log.error("Failed to process batch starting at index {}", i, e);
                // Continue with next batch
            }
        }

        log.info("Generated embeddings for {} notes", processedCount);
        return processedCount;
    }

    /**
     * Finds all .md files in folder recursively
     */
    private List<Path> findAllMarkdownFiles(Path rootPath) {
        List<Path> markdownFiles = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.toString().toLowerCase().endsWith(".md"))
                 .forEach(markdownFiles::add);
        } catch (IOException e) {
            log.error("Failed to walk directory tree: {}", rootPath, e);
            throw new RuntimeException("Failed to scan notes directory", e);
        }

        return markdownFiles;
    }

    /**
     * Synchronizes a single note file
     */
    private SyncActionResult syncSingleNote(Path file) throws IOException {
        String absolutePath = file.toAbsolutePath().toString();
        String fileName = file.getFileName().toString();
        String content = Files.readString(file);
        long fileSize = Files.size(file);

        // Check if note already exists
        var existingNote = noteRepository.findByFilePath(absolutePath);

        if (existingNote.isPresent()) {
            Note note = existingNote.get();

            // Check if file size changed (simple change detection)
            if (note.getFileSize().equals(fileSize)) {
                log.debug("Note unchanged, skipping: {}", fileName);
                return new SyncActionResult(SyncAction.SKIPPED, null);
            }

            // Update existing note
            note.setContent(content);
            note.setFileSize(fileSize);
            noteRepository.save(note);
            noteRepository.clearEmbedding(note.getId()); // Reset embedding, will generate later

            log.info("Updated note: {}", fileName);
            return new SyncActionResult(SyncAction.UPDATED, note.getId());
        } else {
            // Create new note
            Note newNote = Note.builder()
                .fileName(fileName)
                .filePath(absolutePath)
                .content(content)
                .fileSize(fileSize)
                .build();

            noteRepository.save(newNote);
            log.info("Created new note: {}", fileName);
            return new SyncActionResult(SyncAction.CREATED, newNote.getId());
        }
    }

    /**
     * Deletes notes from DB that no longer exist on disk
     */
    private int deleteNotesNotOnDisk(Set<String> diskFilePaths) {
        List<Note> allNotes = noteRepository.findAll();
        List<Note> notesToDelete = new ArrayList<>();

        for (Note note : allNotes) {
            if (!diskFilePaths.contains(note.getFilePath())) {
                notesToDelete.add(note);
                log.info("Note file no longer exists, marking for deletion: {}", note.getFileName());
            }
        }

        if (!notesToDelete.isEmpty()) {
            noteRepository.deleteAll(notesToDelete);
            log.info("Deleted {} notes from database", notesToDelete.size());
        }

        return notesToDelete.size();
    }

    /**
     * Processes batch of notes for embedding generation.
     * Enriches note content with attachment descriptions before generating embeddings.
     */
    private void processBatchEmbeddings(List<Note> notes) {
        // Collect texts for batch request, enriched with attachment descriptions
        List<String> texts = notes.stream()
            .map(this::getEnrichedContent)
            .toList();

        // Generate embeddings in one API call
        List<float[]> embeddings = embeddingService.generateEmbeddingsBatch(texts);

        // Update notes with embeddings via native query
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            float[] embedding = embeddings.get(i);
            String embeddingStr = floatArrayToVectorString(embedding);
            noteRepository.updateEmbedding(note.getId(), embeddingStr);
            log.debug("Generated embedding for: {}", note.getFileName());
        }
    }

    /**
     * Enriches note content with attachment descriptions for better semantic embeddings.
     */
    private String getEnrichedContent(Note note) {
        StringBuilder enriched = new StringBuilder(note.getContent());

        List<Attachment> attachments = attachmentRepository.findByNoteId(note.getId());
        for (Attachment att : attachments) {
            if (att.getDescription() != null && !att.getDescription().isEmpty()) {
                enriched.append("\n\n[Image: ").append(att.getFileName()).append("]\n");
                enriched.append(att.getDescription());
            }
        }

        return enriched.toString();
    }

    /**
     * Converts float array to PostgreSQL vector string format "[0.1,0.2,...]"
     */
    private String floatArrayToVectorString(float[] floats) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < floats.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(floats[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private enum SyncAction {
        CREATED, UPDATED, SKIPPED
    }

    private record SyncActionResult(SyncAction action, UUID noteId) {
    }

    public record SyncResult(
        int totalFiles,
        int newNotes,
        int updatedNotes,
        int skippedNotes,
        int errorNotes,
        int deletedNotes,
        List<UUID> changedNoteIds
    ) {
    }
}
