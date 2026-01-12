package com.sashkolearn.analyzeagent.domain.service;

import com.sashkolearn.analyzeagent.domain.model.FullSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class NoteSyncOrchestrator {

    private final NoteSyncService noteSyncService;
    private final AttachmentService attachmentService;
    private final LinkService linkService;

    /**
     * Performs full synchronization:
     * 1. Syncs note files
     * 2. Processes image attachments
     * 3. Generates embeddings (enriched with attachment descriptions)
     * 4. Builds graph links
     *
     * @param progressCallback callback for sending progress to Telegram
     * @return synchronization result
     */
    public FullSyncResult performFullSync(Consumer<String> progressCallback) {
        log.info("Starting full notes synchronization");

        try {
            // Phase 1: Sync files
            progressCallback.accept("📁 1/4 Scanning markdown files...");
            NoteSyncService.SyncResult syncResult = noteSyncService.syncNotes();
            progressCallback.accept(
                String.format("📁 1/4 Scanned: %d files (%d new, %d updated, %d deleted)",
                    syncResult.totalFiles(), syncResult.newNotes(), syncResult.updatedNotes(), syncResult.deletedNotes())
            );

            // Phase 2: Process attachments
            progressCallback.accept("🖼️ 2/4 Processing image attachments...");
            AttachmentService.AttachmentResult attachmentResult = attachmentService.processAttachmentsForNotes(syncResult.changedNoteIds());
            progressCallback.accept(
                String.format("🖼️ 2/4 Processed %d images (%d skipped, %d errors)",
                    attachmentResult.processed(), attachmentResult.skipped(), attachmentResult.errors())
            );

            // Phase 3: Generate embeddings (now enriched with attachment descriptions)
            progressCallback.accept("🤖 3/4 Generating AI embeddings...");
            int embeddingsGenerated = noteSyncService.generateMissingEmbeddings();
            progressCallback.accept(
                String.format("🤖 3/4 Generated %d embeddings", embeddingsGenerated)
            );

            // Phase 4: Build links (only for changed notes)
            progressCallback.accept("🔗 4/4 Building wikilink graph...");
            LinkService.LinkBuildResult linkResult = linkService.buildLinksForChangedNotes(syncResult.changedNoteIds());
            progressCallback.accept(
                String.format("🔗 4/4 Updated links for %d notes (%d links, %d broken)",
                    syncResult.changedNoteIds().size(), linkResult.totalLinks(), linkResult.brokenLinks())
            );

            // Create result
            FullSyncResult result = new FullSyncResult(
                new FullSyncResult.SyncStats(
                    syncResult.totalFiles(),
                    syncResult.newNotes(),
                    syncResult.updatedNotes(),
                    syncResult.skippedNotes(),
                    syncResult.deletedNotes()
                ),
                embeddingsGenerated,
                new FullSyncResult.LinkStats(
                    linkResult.totalNotes(),
                    linkResult.totalLinks(),
                    linkResult.brokenLinks()
                )
            );

            log.info("Full sync completed successfully");
            return result;

        } catch (Exception e) {
            log.error("Full sync failed", e);
            progressCallback.accept("❌ Error: " + e.getMessage());
            throw new RuntimeException("Full sync failed: " + e.getMessage(), e);
        }
    }
}
