package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A migration as an ordered list of single SQL statements. Room migrations
 * execute statements one-by-one via [SupportSQLiteDatabase.execSQL], so each
 * entry here must be a single statement (no semicolon-joined scripts).
 *
 * Exposing the SQL list independently of the Room [Migration] object lets
 * JVM-side tests replay the migration against an in-memory SQLite (sqlite-jdbc)
 * without needing the Android framework.
 */
data class MigrationSql(val statements: List<String>)

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        BenchmarkResultEntity::class,
        MessageEvidenceEntity::class,
        ProjectEntity::class,
        ProjectConversationEntity::class,
        ProjectFileEntity::class,
        SubmissionCommitWitnessEntity::class,
        ResponseVersionCommitWitnessEntity::class,
        SavedKnowledgeEntity::class,
        ProjectTaskEntity::class,
        StudyReviewCardEntity::class,
        BranchSynthesisSourceEntity::class,
        InboxItemEntity::class,
        ExternalHandoffReceiptEntity::class,
        KnowledgeTagEntity::class,
        SavedKnowledgeTagLinkEntity::class,
        InboxItemTagLinkEntity::class,
        KnowledgeCollectionEntity::class,
        CollectionSavedKnowledgeLinkEntity::class,
        CollectionInboxLinkEntity::class,
        DocumentHighlightSourceEntity::class,
        ProjectDecisionEntity::class,
        SavedKnowledgeNoteSourceEntity::class,
        ConversationCheckpointEntity::class,
        ProjectWorkPlanEntity::class,
        ProjectWorkPlanStepEntity::class,
        ProjectWorkPlanResultSourceEntity::class,
        ProjectWorkPlanGenerationWitnessEntity::class,
        ReminderRecoveryCursorEntity::class,
    ],
    version = 31,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knowledgeSearchDao(): KnowledgeSearchDao
    abstract fun chatDao(): ChatDao
    abstract fun knowledgeBacklinkDao(): KnowledgeBacklinkDao
    abstract fun researchPackDao(): ResearchPackDao
    abstract fun benchmarkDao(): BenchmarkDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun projectDao(): ProjectDao
    abstract fun submissionCommitWitnessDao(): SubmissionCommitWitnessDao
    abstract fun responseVersionCommitWitnessDao(): ResponseVersionCommitWitnessDao
    abstract fun savedKnowledgeDao(): SavedKnowledgeDao
    abstract fun projectTaskDao(): ProjectTaskDao
    abstract fun studyReviewCardDao(): StudyReviewCardDao
    abstract fun branchSynthesisSourceDao(): BranchSynthesisSourceDao
    abstract fun inboxItemDao(): InboxItemDao
    abstract fun externalHandoffReceiptDao(): ExternalHandoffReceiptDao
    abstract fun knowledgeOrganizationDao(): KnowledgeOrganizationDao
    abstract fun documentHighlightSourceDao(): DocumentHighlightSourceDao
    abstract fun projectDecisionDao(): ProjectDecisionDao
    abstract fun researchRefreshDao(): ResearchRefreshDao
    abstract fun savedKnowledgeNoteSourceDao(): SavedKnowledgeNoteSourceDao
    abstract fun conversationCheckpointDao(): ConversationCheckpointDao
    abstract fun projectKnowledgeDigestDao(): ProjectKnowledgeDigestDao
    abstract fun projectWorkPlanDao(): ProjectWorkPlanDao
    abstract fun projectWorkPlanResultSourceDao(): ProjectWorkPlanResultSourceDao
    abstract fun projectWorkPlanGenerationWitnessDao(): ProjectWorkPlanGenerationWitnessDao
    abstract fun reminderRecoveryCursorDao(): ReminderRecoveryCursorDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        internal val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration1To2Sql.statements) {
                    db.execSQL(statement)
                }
            }
        }

        /** Exact ordered SQL used by [Migration1To2] and host SQLite replay. */
        val Migration1To2Sql = MigrationSql(
            listOf(
                "ALTER TABLE conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE conversations ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0",
            ),
        )

        internal val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration2To3Sql.statements) {
                    db.execSQL(statement)
                }
            }
        }

        /** Exact ordered SQL used by [Migration2To3] and host SQLite replay. */
        val Migration2To3Sql = MigrationSql(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS benchmark_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        modelName TEXT NOT NULL,
                        modelPath TEXT,
                        accelerator TEXT NOT NULL,
                        actualBackend TEXT,
                        mtpEnabled INTEGER NOT NULL,
                        mtpStatus TEXT NOT NULL,
                        maxTokens INTEGER NOT NULL,
                        temperature REAL NOT NULL,
                        topK INTEGER NOT NULL,
                        topP REAL NOT NULL,
                        reasoningEnabled INTEGER NOT NULL,
                        webSearchEnabled INTEGER NOT NULL,
                        promptLabel TEXT NOT NULL,
                        promptText TEXT NOT NULL,
                        modelLoadingMs INTEGER,
                        firstTokenLatencyMs INTEGER,
                        totalGenerationMs INTEGER NOT NULL,
                        estimatedOutputTokens INTEGER NOT NULL,
                        totalTokensPerSecond REAL NOT NULL,
                        decodeTokensPerSecond REAL,
                        success INTEGER NOT NULL,
                        errorMessage TEXT,
                        appVersion TEXT,
                        deviceModel TEXT,
                        androidVersion TEXT
                    )
                """.trimIndent(),
            ),
        )

        /**
         * Phase 7: benchmark_results gains the immutable runtime execution
         * snapshot columns so persisted results carry the requested/applied
         * runtime truth rather than a single nullable backend string.
         *
         * Migrated compatibly: [actualBackend] is renamed to
         * [BenchmarkResultEntity.selectedTextBackend] and [mtpEnabled] to
         * [BenchmarkResultEntity.mtpRequested] using SQLite RENAME COLUMN
         * (bundled by androidx.sqlite). New columns all use NOT NULL DEFAULT
         * so historical rows fill in safe placeholders. No destructive table
         * replacement is performed.
         *
         * Phase F: [BenchmarkResultEntity.initializedWithMtp] is derived for
         * existing rows from the renamed `mtpRequested` and the existing
         * `mtpStatus` string rather than left at the DEFAULT 0 placeholder.
         * The placeholder is still applied at ADD COLUMN time so the column
         * has a deterministic value during the ALTER, and then updated to the
         * derived value before the migration completes.
         */
        internal val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in BenchmarkResultsMigration3To4Sql.statements) {
                    db.execSQL(statement)
                }
            }
        }

        /**
         * Phase F: the list of SQL statements executed by [Migration3To4]
         * in declaration order. Exposed as a public list so JVM-side tests
         * can replay the migration against an in-memory SQLite (sqlite-jdbc)
         * without needing the Android framework. The contract is that each
         * entry must be a single SQL statement; multi-statement scripts must
         * be split into separate entries.
         */
        val BenchmarkResultsMigration3To4Sql: MigrationSql = MigrationSql(
            listOf(
                // Rename ambiguous conceptual columns.
                "ALTER TABLE benchmark_results RENAME COLUMN actualBackend TO selectedTextBackend",
                "ALTER TABLE benchmark_results RENAME COLUMN mtpEnabled TO mtpRequested",
                // Add the new runtime snapshot columns.
                "ALTER TABLE benchmark_results ADD COLUMN selectedVisionBackend TEXT",
                "ALTER TABLE benchmark_results ADD COLUMN samplerBackend TEXT NOT NULL DEFAULT 'UNKNOWN'",
                "ALTER TABLE benchmark_results ADD COLUMN fallbackEventCodes TEXT",
                "ALTER TABLE benchmark_results ADD COLUMN initializedWithMtp INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE benchmark_results ADD COLUMN nativeTtftSeconds REAL",
                "ALTER TABLE benchmark_results ADD COLUMN nativePrefillTokensPerSecond REAL",
                "ALTER TABLE benchmark_results ADD COLUMN nativeDecodeTokensPerSecond REAL",
                "ALTER TABLE benchmark_results ADD COLUMN nativePrefillTokenCount INTEGER",
                "ALTER TABLE benchmark_results ADD COLUMN nativeDecodeTokenCount INTEGER",
                "ALTER TABLE benchmark_results ADD COLUMN nativeInitTimeSeconds REAL",
                // Derive `initializedWithMtp` for historical rows.
                "UPDATE benchmark_results SET initializedWithMtp = " +
                    "CASE WHEN mtpRequested = 1 AND mtpStatus = 'INITIALIZED_WITH_MTP_REQUEST' " +
                    "THEN 1 ELSE 0 END",
            )
        )

        /**
         * Phase G: adds the message_evidence table for durable evidence
         * reports. The table has a primary key on messageId with a
         * foreign key to messages(id) ON DELETE CASCADE.
         */
        internal val Migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration4To5Sql.statements) {
                    db.execSQL(statement)
                }
            }
        }

/**
          * Phase G: the list of SQL statements executed by [Migration4To5]
          * in declaration order. Exposed as a public list so JVM-side tests
          * can replay the migration against an in-memory SQLite (sqlite-jdbc)
          * without needing the Android framework.
          */
        val Migration4To5Sql: Migration4To5Statements = Migration4To5Statements(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS message_evidence (
                        messageId INTEGER NOT NULL,
                        answerSha256 TEXT NOT NULL,
                        schemaVersion INTEGER NOT NULL,
                        verifierVersion TEXT NOT NULL,
                        checkedAt INTEGER NOT NULL,
                        reportJson TEXT NOT NULL,
                        PRIMARY KEY (messageId),
                        FOREIGN KEY (messageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_message_evidence_messageId` ON `message_evidence` (`messageId`)"
            )
        )

        /**
         * Phase H: Projects v1 adds the projects table and the
         * project_conversations relation (conversationId PRIMARY KEY, so one
         * conversation belongs to at most one project). Both foreign keys
         * cascade: deleting a conversation drops its relation, and deleting
         * a project drops its relations while its conversations remain in
         * normal history. Non-destructive: existing rows are untouched.
         */
        internal val Migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration5To6Sql.statements) {
                    db.execSQL(statement)
                }
            }
        }

        /**
         * Phase H: the list of SQL statements executed by [Migration5To6]
         * in declaration order. Exposed as a public list so JVM-side tests
         * can replay the migration against an in-memory SQLite (sqlite-jdbc)
         * without needing the Android framework. The contract is that each
         * entry must be a single SQL statement; multi-statement scripts must
         * be split into separate entries.
         */
        val Migration5To6Sql: Migration5To6Statements = Migration5To6Statements(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent(),
                """
                    CREATE TABLE IF NOT EXISTS `project_conversations` (
                        `conversationId` INTEGER NOT NULL,
                        `projectId` INTEGER NOT NULL,
                        `assignedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`),
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_project_conversations_projectId` ON `project_conversations` (`projectId`)"
            )
        )

        /**
         * Projects v2 adds explicit user-authored instructions and the
         * bounded shared local-file reference table. Existing projects and
         * memberships remain untouched; no physical files are copied or
         * removed by this migration.
         */
        internal val Migration6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration6To7Sql.statements) {
                    db.execSQL(statement)
                }
            }
        }

        val Migration6To7Sql: Migration6To7Statements = Migration6To7Statements(
            listOf(
                "ALTER TABLE `projects` ADD COLUMN `instructions` TEXT NOT NULL DEFAULT ''",
                """
                    CREATE TABLE IF NOT EXISTS `project_files` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `projectId` INTEGER NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_project_files_projectId` ON `project_files` (`projectId`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_project_files_projectId_localPath` ON `project_files` (`projectId`, `localPath`)",
            )
        )

        /**
         * Phase B: crash-consistency witnesses for user submissions and
         * assistant response-version structure. The submission witness has
         * no message foreign key by design so it remains historical proof
         * after conversation deletion. The response witness cascades with
         * its assistant message because it has no recovery value afterward.
         */
        internal val Migration7To8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration7To8Sql.statements) {
                    db.execSQL(statement)
                }
            }
        }

        val Migration7To8Sql: Migration7To8Statements = Migration7To8Statements(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `submission_commit_witnesses` (
                        `token` TEXT NOT NULL,
                        `draftKey` INTEGER NOT NULL,
                        `committedConversationId` INTEGER NOT NULL,
                        `userMessageId` INTEGER NOT NULL,
                        `capturedRawInput` TEXT NOT NULL,
                        `committedPathsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`token`)
                    )
                """.trimIndent(),
                """
                    CREATE TABLE IF NOT EXISTS `response_version_commit_witnesses` (
                        `messageId` INTEGER NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `targetAssistantMessageId` INTEGER NOT NULL,
                        `groupId` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_response_version_commit_witnesses_conversationId` ON `response_version_commit_witnesses` (`conversationId`)",
            )
        )

        internal val Migration8To9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration8To9Sql.statements) {
                    db.execSQL(statement)
                }
            }
        }

        val Migration8To9Sql: Migration8To9Statements = Migration8To9Statements(
            listOf(
                "ALTER TABLE conversations ADD COLUMN branchedFromConversationId INTEGER",
                "ALTER TABLE conversations ADD COLUMN branchedFromMessageId INTEGER",
            ),
        )

        internal val Migration9To10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration9To10Sql.statements) db.execSQL(statement)
            }
        }

        val Migration9To10Sql = MigrationSql(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `saved_knowledge` (
                        `id` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `projectId` INTEGER,
                        `title` TEXT NOT NULL,
                        `savedText` TEXT NOT NULL,
                        `provenanceType` TEXT NOT NULL,
                        `sourceConversationId` INTEGER,
                        `sourceMessageId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_saved_knowledge_projectId` ON `saved_knowledge` (`projectId`)",
                "CREATE INDEX IF NOT EXISTS `index_saved_knowledge_scope_updatedAt` ON `saved_knowledge` (`scope`, `updatedAt`)",
            ),
        )

        internal val Migration10To11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration10To11Sql.statements) db.execSQL(statement)
            }
        }

        val Migration10To11Sql = MigrationSql(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `project_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `projectId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `sourceConversationId` INTEGER,
                        `sourceMessageId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_project_tasks_projectId` ON `project_tasks` (`projectId`)",
                "CREATE INDEX IF NOT EXISTS `index_project_tasks_projectId_state_updatedAt` ON `project_tasks` (`projectId`, `state`, `updatedAt`)",
            ),
        )

        internal val Migration11To12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration11To12Sql.statements) db.execSQL(statement)
            }
        }

        val Migration11To12Sql = MigrationSql(
            listOf(
                "ALTER TABLE `project_tasks` ADD COLUMN `reminderAt` INTEGER",
                "ALTER TABLE `project_tasks` ADD COLUMN `reminderEnabled` INTEGER NOT NULL DEFAULT 0",
                "CREATE INDEX IF NOT EXISTS `index_project_tasks_reminderEnabled_reminderAt` ON `project_tasks` (`reminderEnabled`, `reminderAt`)",
            ),
        )

        internal val Migration12To13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration12To13Sql.statements) db.execSQL(statement)
            }
        }

        val Migration12To13Sql = MigrationSql(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `study_review_cards` (
                        `id` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `projectId` INTEGER,
                        `front` TEXT NOT NULL,
                        `back` TEXT NOT NULL,
                        `sourceKind` TEXT NOT NULL,
                        `sourceConversationId` INTEGER,
                        `sourceMessageId` INTEGER,
                        `sourceSavedKnowledgeId` TEXT,
                        `sourceProjectId` INTEGER,
                        `sourceProjectFileId` INTEGER,
                        `reviewCount` INTEGER NOT NULL,
                        `lastRating` TEXT,
                        `dueAt` INTEGER NOT NULL,
                        `lastReviewedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `reminderAt` INTEGER,
                        `reminderEnabled` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_study_review_cards_scope_projectId_dueAt` ON `study_review_cards` (`scope`, `projectId`, `dueAt`)",
                "CREATE INDEX IF NOT EXISTS `index_study_review_cards_projectId` ON `study_review_cards` (`projectId`)",
                "CREATE INDEX IF NOT EXISTS `index_study_review_cards_reminderEnabled_reminderAt` ON `study_review_cards` (`reminderEnabled`, `reminderAt`)",
            ),
        )

        internal val Migration13To14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration13To14Sql.statements) db.execSQL(statement)
            }
        }

        val Migration13To14Sql = MigrationSql(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `branch_synthesis_sources` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `synthesisConversationId` INTEGER NOT NULL,
                        `sourceConversationId` INTEGER NOT NULL,
                        `sourceTitle` TEXT NOT NULL,
                        `sourceOrdinal` INTEGER NOT NULL,
                        `intent` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`synthesisConversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent(),
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_branch_synthesis_sources_synthesisConversationId_sourceOrdinal` ON `branch_synthesis_sources` (`synthesisConversationId`, `sourceOrdinal`)",
                "CREATE INDEX IF NOT EXISTS `index_branch_synthesis_sources_sourceConversationId` ON `branch_synthesis_sources` (`sourceConversationId`)",
            ),
        )

        internal val Migration14To15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration14To15Sql.statements) db.execSQL(statement)
            }
        }

        val Migration14To15Sql = MigrationSql(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `inbox_items` (
                        `id` TEXT NOT NULL,
                        `captureType` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `originalUri` TEXT,
                        `managedFileId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `processingState` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_inbox_items_updatedAt_id` ON `inbox_items` (`updatedAt`, `id`)",
                "CREATE INDEX IF NOT EXISTS `index_inbox_items_captureType` ON `inbox_items` (`captureType`)",
            ),
        )

        internal val Migration15To16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration15To16Sql.statements) db.execSQL(statement)
            }
        }

        val Migration15To16Sql = MigrationSql(
            listOf(
                "ALTER TABLE `inbox_items` ADD COLUMN `managedMimeType` TEXT",
                "ALTER TABLE `inbox_items` ADD COLUMN `sourceHandoffEventId` TEXT",
                "ALTER TABLE `project_files` ADD COLUMN `sourceHandoffEventId` TEXT",
                "CREATE INDEX IF NOT EXISTS `index_inbox_items_sourceHandoffEventId` ON `inbox_items` (`sourceHandoffEventId`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_project_files_sourceHandoffEventId` ON `project_files` (`sourceHandoffEventId`)",
                """
                    CREATE TABLE IF NOT EXISTS `external_handoff_receipts` (
                        `eventId` TEXT NOT NULL,
                        `destination` TEXT NOT NULL,
                        `targetProjectId` INTEGER,
                        `selectedAt` INTEGER NOT NULL,
                        `settledAt` INTEGER,
                        `outcome` TEXT NOT NULL,
                        PRIMARY KEY(`eventId`)
                    )
                """.trimIndent(),
            ),
        )

        internal val Migration16To17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration16To17Sql.statements) db.execSQL(statement)
            }
        }

        val Migration16To17Sql = MigrationSql(
            listOf(
                "ALTER TABLE `saved_knowledge` ADD COLUMN `sourceUrl` TEXT",
                "ALTER TABLE `saved_knowledge` ADD COLUMN `sourcePageTitle` TEXT",
                "ALTER TABLE `saved_knowledge` ADD COLUMN `retrievedAt` INTEGER",
            ),
        )

        internal val Migration17To18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration17To18Sql.statements) db.execSQL(statement)
            }
        }

        val Migration17To18Sql = MigrationSql(
            listOf(
                "CREATE TABLE IF NOT EXISTS `knowledge_tags` (`tagKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`tagKey`))",
                "CREATE INDEX IF NOT EXISTS `index_knowledge_tags_updatedAt` ON `knowledge_tags` (`updatedAt`)",
                "CREATE TABLE IF NOT EXISTS `saved_knowledge_tags` (`itemId` TEXT NOT NULL, `tagKey` TEXT NOT NULL, PRIMARY KEY(`itemId`, `tagKey`), FOREIGN KEY(`itemId`) REFERENCES `saved_knowledge`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`tagKey`) REFERENCES `knowledge_tags`(`tagKey`) ON UPDATE CASCADE ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_saved_knowledge_tags_tagKey` ON `saved_knowledge_tags` (`tagKey`)",
                "CREATE TABLE IF NOT EXISTS `inbox_item_tags` (`itemId` TEXT NOT NULL, `tagKey` TEXT NOT NULL, PRIMARY KEY(`itemId`, `tagKey`), FOREIGN KEY(`itemId`) REFERENCES `inbox_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`tagKey`) REFERENCES `knowledge_tags`(`tagKey`) ON UPDATE CASCADE ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_inbox_item_tags_tagKey` ON `inbox_item_tags` (`tagKey`)",
                "CREATE TABLE IF NOT EXISTS `knowledge_collections` (`collectionId` TEXT NOT NULL, `collectionKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`collectionId`))",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_collections_collectionKey` ON `knowledge_collections` (`collectionKey`)",
                "CREATE INDEX IF NOT EXISTS `index_knowledge_collections_updatedAt` ON `knowledge_collections` (`updatedAt`)",
                "CREATE TABLE IF NOT EXISTS `collection_saved_knowledge` (`collectionId` TEXT NOT NULL, `itemId` TEXT NOT NULL, PRIMARY KEY(`collectionId`, `itemId`), FOREIGN KEY(`collectionId`) REFERENCES `knowledge_collections`(`collectionId`) ON UPDATE CASCADE ON DELETE CASCADE, FOREIGN KEY(`itemId`) REFERENCES `saved_knowledge`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_collection_saved_knowledge_itemId` ON `collection_saved_knowledge` (`itemId`)",
                "CREATE TABLE IF NOT EXISTS `collection_inbox_items` (`collectionId` TEXT NOT NULL, `itemId` TEXT NOT NULL, PRIMARY KEY(`collectionId`, `itemId`), FOREIGN KEY(`collectionId`) REFERENCES `knowledge_collections`(`collectionId`) ON UPDATE CASCADE ON DELETE CASCADE, FOREIGN KEY(`itemId`) REFERENCES `inbox_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_collection_inbox_items_itemId` ON `collection_inbox_items` (`itemId`)",
            ),
        )

        internal val Migration18To19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration18To19Sql.statements) db.execSQL(statement)
            }
        }

        val Migration18To19Sql = MigrationSql(
            listOf(
                "CREATE TABLE IF NOT EXISTS `document_highlight_sources` (`savedKnowledgeId` TEXT NOT NULL, `projectId` INTEGER NOT NULL, `projectFileId` INTEGER NOT NULL, `pageNumber` INTEGER, `sourceChunkId` TEXT, `sourceContentHashHex` TEXT, `userNote` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`savedKnowledgeId`), FOREIGN KEY(`savedKnowledgeId`) REFERENCES `saved_knowledge`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`projectFileId`) REFERENCES `project_files`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_document_highlight_sources_projectId` ON `document_highlight_sources` (`projectId`)",
                "CREATE INDEX IF NOT EXISTS `index_document_highlight_sources_projectFileId` ON `document_highlight_sources` (`projectFileId`)",
                "CREATE TRIGGER IF NOT EXISTS `delete_saved_highlight_when_source_removed` AFTER DELETE ON `document_highlight_sources` BEGIN DELETE FROM `saved_knowledge` WHERE `id` = OLD.`savedKnowledgeId` AND `provenanceType` = 'DOCUMENT_HIGHLIGHT'; END",
            ),
        )

        internal val Migration19To20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration19To20Sql.statements) db.execSQL(statement)
            }
        }

        val Migration19To20Sql = MigrationSql(
            listOf(
                "CREATE TABLE IF NOT EXISTS `project_decisions` (`id` TEXT NOT NULL, `projectId` INTEGER NOT NULL, `title` TEXT NOT NULL, `rationale` TEXT NOT NULL, `status` TEXT NOT NULL, `supersedesDecisionId` TEXT, `sourceConversationId` INTEGER, `sourceMessageId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`supersedesDecisionId`) REFERENCES `project_decisions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                "CREATE INDEX IF NOT EXISTS `index_project_decisions_projectId_status_updatedAt` ON `project_decisions` (`projectId`, `status`, `updatedAt`)",
                "CREATE INDEX IF NOT EXISTS `index_project_decisions_projectId_createdAt` ON `project_decisions` (`projectId`, `createdAt`)",
                "CREATE INDEX IF NOT EXISTS `index_project_decisions_supersedesDecisionId` ON `project_decisions` (`supersedesDecisionId`)",
            ),
        )

        internal val Migration20To21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration20To21Sql.statements) db.execSQL(statement)
            }
        }

        val Migration20To21Sql = MigrationSql(
            listOf(
                "ALTER TABLE `conversations` ADD COLUMN `refreshedFromConversationId` INTEGER",
                "ALTER TABLE `conversations` ADD COLUMN `refreshedFromMessageId` INTEGER",
                "CREATE INDEX IF NOT EXISTS `index_conversations_refreshedFromConversationId_refreshedFromMessageId` ON `conversations` (`refreshedFromConversationId`, `refreshedFromMessageId`)",
            ),
        )

        internal val Migration21To22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration21To22Sql.statements) db.execSQL(statement)
            }
        }

        val Migration21To22Sql = MigrationSql(
            listOf(
                "CREATE TABLE IF NOT EXISTS `saved_knowledge_note_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, `sourceKey` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `conversationId` INTEGER, `projectId` INTEGER, `projectFileId` INTEGER, `savedKnowledgeId` TEXT, `sourceTitle` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`noteId`) REFERENCES `saved_knowledge`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_saved_knowledge_note_sources_noteId_orderIndex` ON `saved_knowledge_note_sources` (`noteId`, `orderIndex`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_knowledge_note_sources_noteId_sourceKey` ON `saved_knowledge_note_sources` (`noteId`, `sourceKey`)",
            ),
        )

        internal val Migration22To23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration22To23Sql.statements) db.execSQL(statement)
            }
        }

        val Migration22To23Sql = MigrationSql(
            listOf(
                "CREATE TABLE IF NOT EXISTS `conversation_checkpoints` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `messageId` INTEGER NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `note` TEXT NOT NULL, FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_conversation_checkpoints_conversationId_createdAt` ON `conversation_checkpoints` (`conversationId`, `createdAt`)",
                "CREATE INDEX IF NOT EXISTS `index_conversation_checkpoints_messageId` ON `conversation_checkpoints` (`messageId`)",
            ),
        )

        internal val Migration23To24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration23To24Sql.statements) db.execSQL(statement)
            }
        }

        val Migration23To24Sql = MigrationSql(
            listOf(
                "CREATE TABLE IF NOT EXISTS `project_work_plans` (`id` TEXT NOT NULL, `projectId` INTEGER NOT NULL, `title` TEXT NOT NULL, `goal` TEXT NOT NULL, `desiredOutput` TEXT NOT NULL, `constraintText` TEXT NOT NULL, `status` TEXT NOT NULL, `currentStepPosition` INTEGER NOT NULL, `archivedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plans_projectId` ON `project_work_plans` (`projectId`)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plans_projectId_status_updatedAt` ON `project_work_plans` (`projectId`, `status`, `updatedAt`)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plans_projectId_archivedAt` ON `project_work_plans` (`projectId`, `archivedAt`)",
                "CREATE TABLE IF NOT EXISTS `project_work_plan_steps` (`id` TEXT NOT NULL, `planId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `instruction` TEXT NOT NULL, `type` TEXT NOT NULL, `state` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL DEFAULT 0, `activeAttemptId` TEXT, `resultKind` TEXT, `resultId` TEXT, `previousResultRefs` TEXT NOT NULL DEFAULT '', `linkedTaskId` INTEGER, `interrupted` INTEGER NOT NULL DEFAULT 0, `failureKind` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`planId`) REFERENCES `project_work_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`linkedTaskId`) REFERENCES `project_tasks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_steps_planId` ON `project_work_plan_steps` (`planId`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_project_work_plan_steps_planId_position` ON `project_work_plan_steps` (`planId`, `position`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_project_work_plan_steps_linkedTaskId` ON `project_work_plan_steps` (`linkedTaskId`)",
            ),
        )

        internal val Migration24To25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration24To25Sql.statements) db.execSQL(statement)
            }
        }

        /**
         * Findings D + G: durable SELECTION/EXTRACTION provenance. One row
         * per accepted specialized result keyed by the collision-resistant
         * result id, carrying the bounded JSON of actual selected source
         * identities. Non-destructive: existing plans, steps, and receipts
         * are untouched; the new table starts empty.
         */
        val Migration24To25Sql = MigrationSql(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `project_work_plan_result_sources` (
                        `resultId` TEXT NOT NULL,
                        `planId` TEXT NOT NULL,
                        `stepId` TEXT NOT NULL,
                        `attemptId` TEXT NOT NULL,
                        `projectId` INTEGER NOT NULL,
                        `stepType` TEXT NOT NULL,
                        `resultKind` TEXT NOT NULL,
                        `extractionType` TEXT,
                        `sourcesJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`resultId`)
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_result_sources_planId` ON `project_work_plan_result_sources` (`planId`)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_result_sources_stepId` ON `project_work_plan_result_sources` (`stepId`)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_result_sources_attemptId` ON `project_work_plan_result_sources` (`attemptId`)",
            ),
        )

        internal val Migration25To26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration25To26Sql.statements) db.execSQL(statement)
            }
        }

        /**
         * Issues 1, 2 + 4: one coherent v25 to v26 migration.
         *
         * - The result-source table is rebuilt attempt-scoped
         *   (`PRIMARY KEY(attemptId)`) with cascade ownership to its Work
         *   Plan and step. Valid owned v25 rows are preserved; the content
         *   fingerprint stays a non-unique indexed column so
         *   repeated identical selections across attempts never collide.
         *   Rows whose owning plan or step was already deleted in v25 are
         *   intentionally pruned by the INSERT predicates: v26 makes these
         *   ownership relationships cascading foreign keys, so orphaned
         *   provenance is not retained as an unowned record.
         * - Every v25 result-source row with an extant owner is copied; rows
         *   orphaned by earlier v25 plan/step deletion are intentionally
         *   excluded, matching the new cascade ownership contract.
         * - The narrow generation-witness table is created for exact
         *   request → assistant provenance.
         *
         * Non-destructive for valid owned data: plans, steps, and prior
         * result rows with extant owners survive; owner orphans are pruned.
         */
        val Migration25To26Sql = MigrationSql(
            listOf(
                """
                    CREATE TABLE IF NOT EXISTS `project_work_plan_result_sources_new` (
                        `attemptId` TEXT NOT NULL,
                        `resultId` TEXT NOT NULL,
                        `planId` TEXT NOT NULL,
                        `stepId` TEXT NOT NULL,
                        `projectId` INTEGER NOT NULL,
                        `stepType` TEXT NOT NULL,
                        `resultKind` TEXT NOT NULL,
                        `extractionType` TEXT,
                        `sourcesJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`attemptId`),
                        FOREIGN KEY(`planId`) REFERENCES `project_work_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`stepId`) REFERENCES `project_work_plan_steps`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent(),
                """
                    INSERT INTO `project_work_plan_result_sources_new`
                    SELECT `attemptId`, `resultId`, `planId`, `stepId`, `projectId`, `stepType`, `resultKind`, `extractionType`, `sourcesJson`, `createdAt`
                    FROM `project_work_plan_result_sources`
                    WHERE `planId` IN (SELECT `id` FROM `project_work_plans`)
                      AND `stepId` IN (SELECT `id` FROM `project_work_plan_steps`)
                """.trimIndent(),
                "DROP TABLE `project_work_plan_result_sources`",
                "ALTER TABLE `project_work_plan_result_sources_new` RENAME TO `project_work_plan_result_sources`",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_result_sources_planId` ON `project_work_plan_result_sources` (`planId`)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_result_sources_stepId` ON `project_work_plan_result_sources` (`stepId`)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_result_sources_resultId` ON `project_work_plan_result_sources` (`resultId`)",
                """
                    CREATE TABLE IF NOT EXISTS `project_work_plan_generation_witnesses` (
                        `attemptId` TEXT NOT NULL,
                        `requestId` TEXT NOT NULL,
                        `planId` TEXT NOT NULL,
                        `stepId` TEXT NOT NULL,
                        `projectId` INTEGER NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `userMessageId` INTEGER NOT NULL,
                        `assistantMessageId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`attemptId`)
                    )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_generation_witnesses_requestId` ON `project_work_plan_generation_witnesses` (`requestId`)",
                "CREATE INDEX IF NOT EXISTS `index_project_work_plan_generation_witnesses_stepId` ON `project_work_plan_generation_witnesses` (`stepId`)",
            ),
        )

        internal val Migration26To27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration26To27Sql.statements) db.execSQL(statement)
            }
        }

        val Migration26To27Sql = MigrationSql(
            listOf(
                "ALTER TABLE `project_tasks` ADD COLUMN `reminderGeneration` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `study_review_cards` ADD COLUMN `reminderGeneration` INTEGER NOT NULL DEFAULT 0",
            ),
        )

        internal val Migration27To28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration27To28Sql.statements) db.execSQL(statement)
            }
        }

        /** Durable retry debt for clearing posted notifications after terminal reminder changes. */
        val Migration27To28Sql = MigrationSql(
            listOf(
                "ALTER TABLE `project_tasks` ADD COLUMN `notificationCleanupPending` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `study_review_cards` ADD COLUMN `notificationCleanupPending` INTEGER NOT NULL DEFAULT 0",
                // One-time cleanup covers notifications posted by prior app
                // versions whose reminder rows were already consumed.
                "UPDATE `project_tasks` SET `notificationCleanupPending` = 1",
                "UPDATE `study_review_cards` SET `notificationCleanupPending` = 1",
            ),
        )

        internal val Migration28To29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration28To29Sql.statements) db.execSQL(statement)
            }
        }

        /** Durable deletion intents keep alarm/notification identity recoverable across process death. */
        val Migration28To29Sql = MigrationSql(
            listOf(
                "ALTER TABLE `projects` ADD COLUMN `deletionPending` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `project_tasks` ADD COLUMN `deletionPending` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `study_review_cards` ADD COLUMN `deletionPending` INTEGER NOT NULL DEFAULT 0",
            ),
        )

        internal val Migration29To30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration29To30Sql.statements) db.execSQL(statement)
            }
        }

        /** Persisted keyset positions prevent bounded retries from starving later reminders. */
        val Migration29To30Sql = MigrationSql(
            listOf(
                "CREATE TABLE IF NOT EXISTS `reminder_recovery_cursors` (`recoveryKey` TEXT NOT NULL, `cursor` TEXT NOT NULL, PRIMARY KEY(`recoveryKey`))",
            ),
        )

        internal val Migration30To31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in Migration30To31Sql.statements) db.execSQL(statement)
            }
        }

        /** Keep the entered/reopenable URL distinct from the fetched content origin. */
        val Migration30To31Sql = MigrationSql(
            listOf(
                "ALTER TABLE `inbox_items` ADD COLUMN `effectiveSourceUri` TEXT",
                "ALTER TABLE `saved_knowledge` ADD COLUMN `effectiveSourceUrl` TEXT",
            ),
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fusion.db"
                )
                    .addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7, Migration7To8, Migration8To9, Migration9To10, Migration10To11, Migration11To12, Migration12To13, Migration13To14, Migration14To15, Migration15To16, Migration16To17, Migration17To18, Migration18To19, Migration19To20, Migration20To21, Migration21To22, Migration22To23, Migration23To24, Migration24To25, Migration25To26, Migration26To27, Migration27To28, Migration28To29, Migration29To30, Migration30To31)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(Migration18To19Sql.statements.last())
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
