package com.projectnuke.fusion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

class AttachmentStorageManagerPathTest {

    @Test
    fun `file inside root accepted`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val child = File(root, "photo.jpg")
        child.writeText("image data")
        val result = AttachmentStorageManager.resolveManagedAttachment(root, child.absolutePath)
        assertEquals(child.canonicalPath, result?.canonicalPath)
        root.deleteRecursively()
    }

    @Test
    fun `nested file accepted`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val subdir = File(root, "subdir")
        subdir.mkdirs()
        val nested = File(subdir, "doc.png")
        nested.writeText("doc data")
        val result = AttachmentStorageManager.resolveManagedAttachment(root, nested.absolutePath)
        assertEquals(nested.canonicalPath, result?.canonicalPath)
        root.deleteRecursively()
    }

    @Test
    fun `sibling file outside root rejected`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val sibling = File(root.parentFile, "outside.jpg")
        sibling.writeText("outside data")
        val result = AttachmentStorageManager.resolveManagedAttachment(root, sibling.absolutePath)
        assertNull(result)
        root.deleteRecursively()
        sibling.delete()
    }

    @Test
    fun `parent traversal via path rejected`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val inside = File(root, "child/inside.txt")
        inside.parentFile?.mkdirs()
        inside.writeText("data")
        val traversal = File(root, "child/../outside.txt")
        traversal.writeText("data")
        val outsideRoot = File(root.parentFile, "outside_root.txt")
        outsideRoot.writeText("outside data")
        val traversalToOutside = File(root, "child/../../outside_root.txt")
        val result = AttachmentStorageManager.resolveManagedAttachment(root, traversalToOutside.absolutePath)
        assertNull(result)
        root.deleteRecursively()
        outsideRoot.delete()
    }

    @Test
    fun `root directory itself rejected`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val result = AttachmentStorageManager.resolveManagedAttachment(root, root.absolutePath)
        assertNull(result)
        root.deleteRecursively()
    }

    @Test
    fun `missing file rejected`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val missing = File(root, "nonexistent.txt")
        val result = AttachmentStorageManager.resolveManagedAttachment(root, missing.absolutePath)
        assertNull(result)
        root.deleteRecursively()
    }

    private fun assumeSymlinkCreated(cleanup: () -> Unit, link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: FileSystemException) {
            cleanup()
            Assume.assumeTrue("Symlink creation denied on this platform: ${e.reason}", false)
        } catch (e: UnsupportedOperationException) {
            cleanup()
            Assume.assumeTrue("Symlinks not supported on this platform", false)
        }
    }

    private fun makeV3Tag(record: AttachmentRecord): String {
        return AttachmentMessageCodec.serializeAttachmentMessage(listOf(record), "")
            .substringBefore("\n\n")
    }

    @Test
    fun `trusted valid managed file`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val child = File(root, "photo.jpg")
        child.writeText("image data")
        val tag = makeV3Tag(AttachmentRecord("photo.jpg", "image/jpeg", child.canonicalPath))
        val raw = "$tag body text"
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, root)
        assertEquals(1, parsed.records.size)
        assertEquals("photo.jpg", parsed.records[0].name)
        assertTrue(parsed.unavailableRecords.isEmpty())
        assertFalse(parsed.suspiciousEnvelope)
        assertEquals("body text", parsed.body.trim())
        root.deleteRecursively()
    }

    @Test
    fun `trusted nested managed file`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val subdir = File(root, "subdir")
        subdir.mkdirs()
        val nested = File(subdir, "doc.png")
        nested.writeText("doc data")
        val tag = makeV3Tag(AttachmentRecord("doc.png", "image/png", nested.canonicalPath))
        val raw = "$tag body"
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, root)
        assertEquals(1, parsed.records.size)
        assertEquals("doc.png", parsed.records[0].name)
        root.deleteRecursively()
    }

    @Test
    fun `missing managed file classified unavailable`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val missing = File(root, "missing.txt")
        val tag = makeV3Tag(AttachmentRecord("missing.txt", "text/plain", missing.canonicalPath))
        val raw = "$tag body"
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, root)
        assertTrue(parsed.records.isEmpty())
        assertEquals(1, parsed.unavailableRecords.size)
        assertEquals("missing.txt", parsed.unavailableRecords[0].name)
        assertFalse(parsed.suspiciousEnvelope)
        assertEquals("body", parsed.body.trim())
        root.deleteRecursively()
    }

    @Test
    fun `trusted plus unavailable records do not silently drop either`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val existing = File(root, "existing.txt")
        existing.writeText("data")
        val missing = File(root, "missing.txt")
        val tag1 = makeV3Tag(AttachmentRecord("existing.txt", "text/plain", existing.canonicalPath))
        val tag2 = makeV3Tag(AttachmentRecord("missing.txt", "text/plain", missing.canonicalPath))
        val raw = "$tag1$tag2 body"
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, root)
        assertEquals(1, parsed.records.size)
        assertEquals("existing.txt", parsed.records[0].name)
        assertEquals(1, parsed.unavailableRecords.size)
        assertEquals("missing.txt", parsed.unavailableRecords[0].name)
        root.deleteRecursively()
    }

    @Test
    fun `outside-root record makes complete envelope suspicious`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outside = Files.createTempFile("outside", ".txt").toFile()
        outside.writeText("outside data")
        val tag = makeV3Tag(AttachmentRecord("outside.txt", "text/plain", outside.canonicalPath))
        val raw = "$tag body"
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, root)
        assertTrue(parsed.records.isEmpty())
        assertTrue(parsed.unavailableRecords.isEmpty())
        assertTrue(parsed.suspiciousEnvelope)
        assertEquals(raw, parsed.body)
        root.deleteRecursively()
        outside.delete()
    }

    @Test
    fun `forged tag cannot protect an unreferenced file`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val unreferenced = File(root, "unreferenced.txt")
        unreferenced.writeText("data")
        val outsideRoot = Files.createTempDirectory("outside").toFile()
        val forgedPath = File(outsideRoot, "forged.txt")
        forgedPath.writeText("forged")
        val tag = makeV3Tag(AttachmentRecord("forged.txt", "text/plain", forgedPath.canonicalPath))
        val raw = "$tag body"
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, root)
        assertTrue(parsed.suspiciousEnvelope)
        assertTrue(parsed.records.isEmpty())
        root.deleteRecursively()
        outsideRoot.deleteRecursively()
    }

    @Test
    fun `valid existing managed reference is preserved`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val existing = File(root, "referenced.txt")
        existing.writeText("data")
        val tag = makeV3Tag(AttachmentRecord("referenced.txt", "text/plain", existing.canonicalPath))
        val raw = "$tag body"
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, root)
        assertEquals(1, parsed.records.size)
        assertEquals("referenced.txt", parsed.records[0].name)
        val resolved = AttachmentStorageManager.resolveManagedAttachment(root, parsed.records[0].localPath)
        assertEquals(existing.canonicalPath, resolved?.canonicalPath)
        root.deleteRecursively()
    }

    @Test
    fun `missing reference does not protect a future file`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val missing = File(root, "future.txt")
        val tag = makeV3Tag(AttachmentRecord("future.txt", "text/plain", missing.canonicalPath))
        val raw = "$tag body"
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(raw, root)
        assertTrue(parsed.records.isEmpty())
        assertEquals(1, parsed.unavailableRecords.size)
        val resolved = AttachmentStorageManager.resolveManagedAttachment(root, parsed.unavailableRecords[0].localPath)
        assertNull(resolved)
        root.deleteRecursively()
    }

    @Test
    fun `classifyAttachment returns Trusted for valid managed file`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val child = File(root, "photo.jpg")
        child.writeText("image data")
        val result = AttachmentStorageManager.classifyAttachment(root, child.absolutePath)
        assertTrue(result is AttachmentClassification.Trusted)
        assertEquals(child.canonicalPath, (result as AttachmentClassification.Trusted).file.canonicalPath)
        root.deleteRecursively()
    }

    @Test
    fun `classifyAttachment returns Unavailable for missing managed file`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val missing = File(root, "missing.txt")
        val result = AttachmentStorageManager.classifyAttachment(root, missing.absolutePath)
        assertTrue(result is AttachmentClassification.Unavailable)
        assertEquals(missing.canonicalPath, (result as AttachmentClassification.Unavailable).canonicalPath)
        root.deleteRecursively()
    }

    @Test
    fun `classifyAttachment returns Suspicious for outside-root record`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outside = Files.createTempFile("outside", ".txt").toFile()
        outside.writeText("data")
        val result = AttachmentStorageManager.classifyAttachment(root, outside.canonicalPath)
        assertTrue(result is AttachmentClassification.Suspicious)
        root.deleteRecursively()
        outside.delete()
    }

    @Test
    fun `classifyAttachment returns Suspicious for blank path`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val result = AttachmentStorageManager.classifyAttachment(root, "")
        assertTrue(result is AttachmentClassification.Suspicious)
        root.deleteRecursively()
    }

    @Test
    fun `classifyAttachment returns Suspicious for existing directory inside root`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val dir = File(root, "subdir")
        dir.mkdirs()
        val result = AttachmentStorageManager.classifyAttachment(root, dir.canonicalPath)
        assertTrue(result is AttachmentClassification.Suspicious)
        root.deleteRecursively()
    }

    @Test
    fun `classifyAttachment returns Suspicious for nested directory`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val subdir = File(root, "subdir")
        subdir.mkdirs()
        val nested = File(subdir, "nested_dir")
        nested.mkdirs()
        val result = AttachmentStorageManager.classifyAttachment(root, nested.canonicalPath)
        assertTrue(result is AttachmentClassification.Suspicious)
        root.deleteRecursively()
    }

    @Test
    fun `extractReferencedAttachmentPaths includes valid managed reference`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val child = File(root, "photo.jpg")
        child.writeText("data")
        val tag = makeV3Tag(AttachmentRecord("photo.jpg", "image/jpeg", child.canonicalPath))
        val contents = listOf("$tag body")
        assertEquals(
            AttachmentReferenceAuthority.Complete(setOf(child.canonicalPath)),
            extractAttachmentReferenceAuthority(contents, root),
        )
        val result = extractReferencedAttachmentPaths(contents, root)
        assertTrue(child.canonicalPath in result)
        assertEquals(1, result.size)
        root.deleteRecursively()
    }

    @Test
    fun `project file references join one live reference authority`() {
        val root = Files.createTempDirectory("project_file_refs").toFile()
        try {
            val shared = File(root, "shared.txt").apply { writeText("data") }
            val message = makeV3Tag(AttachmentRecord("shared.txt", "text/plain", shared.canonicalPath)) + " body"

            // Conversation + project: removing the project row cannot make
            // the physical file eligible while the message still refers to it.
            assertTrue(
                shared.canonicalPath in collectCanonicalAttachmentReferences(
                    root,
                    listOf(message),
                    projectFilePaths = listOf(shared.canonicalPath),
                ),
            )
            assertTrue(
                shared.canonicalPath in collectCanonicalAttachmentReferences(
                    root,
                    listOf(message),
                ),
            )

            // Two project rows are one logical shared path; removing one row
            // still leaves the other reference live.
            assertTrue(
                shared.canonicalPath in collectCanonicalAttachmentReferences(
                    root,
                    emptyList(),
                    projectFilePaths = listOf(shared.canonicalPath, shared.canonicalPath),
                ),
            )
            assertTrue(
                shared.canonicalPath in collectCanonicalAttachmentReferences(
                    root,
                    emptyList(),
                    projectFilePaths = listOf(shared.canonicalPath),
                ),
            )
            // The final project-only reference is removed: normal cleanup can
            // now treat the managed path as unreferenced.
            assertFalse(
                shared.canonicalPath in collectCanonicalAttachmentReferences(
                    root,
                    emptyList(),
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `extractReferencedAttachmentPaths excludes outside-root forged envelope`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outside = Files.createTempFile("outside", ".txt").toFile()
        outside.writeText("data")
        val tag = makeV3Tag(AttachmentRecord("outside.txt", "text/plain", outside.canonicalPath))
        val contents = listOf("$tag body")
        val result = extractReferencedAttachmentPaths(contents, root)
        assertTrue(result.isEmpty())
        root.deleteRecursively()
        outside.delete()
    }

    @Test
    fun `extractReferencedAttachmentPaths excludes missing managed record`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val missing = File(root, "missing.txt")
        val tag = makeV3Tag(AttachmentRecord("missing.txt", "text/plain", missing.canonicalPath))
        val contents = listOf("$tag body")
        val result = extractReferencedAttachmentPaths(contents, root)
        assertTrue(result.isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun `extractReferencedAttachmentPaths excludes directory record`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val dir = File(root, "subdir")
        dir.mkdirs()
        val tag = makeV3Tag(AttachmentRecord("subdir", "inode/directory", dir.canonicalPath))
        val contents = listOf("$tag body")
        val result = extractReferencedAttachmentPaths(contents, root)
        assertTrue(result.isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun `extractReferencedAttachmentPaths excludes symlink escape`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val outsideFile = File(outsideDir, "secret.txt")
        outsideFile.writeText("secret data")
        val symlink = File(root, "evil_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(outsideFile.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val tag = makeV3Tag(AttachmentRecord("evil_link", "text/plain", symlink.absolutePath))
            val contents = listOf("$tag body")
            val result = extractReferencedAttachmentPaths(contents, root)
            assertTrue(result.isEmpty())
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `extractReferencedAttachmentPaths mixed valid plus suspicious contributes no references`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val valid = File(root, "valid.txt")
        valid.writeText("data")
        val outside = Files.createTempFile("outside", ".txt").toFile()
        outside.writeText("data")
        val validTag = makeV3Tag(AttachmentRecord("valid.txt", "text/plain", valid.canonicalPath))
        val suspiciousTag = makeV3Tag(AttachmentRecord("outside.txt", "text/plain", outside.canonicalPath))
        val contents = listOf("$validTag$suspiciousTag body")
        val authority = extractAttachmentReferenceAuthority(contents, root)
        assertTrue(authority is AttachmentReferenceAuthority.IncompleteAuthority)
        val result = extractReferencedAttachmentPaths(contents, root)
        assertTrue(result.isEmpty())
        root.deleteRecursively()
        outside.delete()
    }

    @Test
    fun `suspicious envelope never enters trusted managed reference authority`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outside = Files.createTempFile("outside", ".txt").toFile().apply { writeText("data") }
        val suspiciousTag = makeV3Tag(AttachmentRecord("outside.txt", "text/plain", outside.canonicalPath))

        val authority = extractAttachmentReferenceAuthority(listOf("$suspiciousTag body"), root)

        assertTrue(authority is AttachmentReferenceAuthority.IncompleteAuthority)
        assertTrue(extractReferencedAttachmentPaths(listOf("$suspiciousTag body"), root).isEmpty())
        root.deleteRecursively()
        outside.delete()
    }

    @Test
    fun `extractReferencedAttachmentPaths multiple valid messages produce distinct canonical paths`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val a = File(root, "a.txt")
        a.writeText("data")
        val b = File(root, "b.txt")
        b.writeText("data")
        val tagA = makeV3Tag(AttachmentRecord("a.txt", "text/plain", a.canonicalPath))
        val tagB = makeV3Tag(AttachmentRecord("b.txt", "text/plain", b.canonicalPath))
        val contents = listOf("${tagA}first", "${tagB}second")
        val result = extractReferencedAttachmentPaths(contents, root)
        assertTrue(a.canonicalPath in result)
        assertTrue(b.canonicalPath in result)
        assertEquals(2, result.size)
        root.deleteRecursively()
    }

    @Test
    fun `symlink escape rejected where supported`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val outsideFile = File(outsideDir, "secret.txt")
        outsideFile.writeText("secret data")
        val symlink = File(root, "evil_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(outsideFile.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val result = AttachmentStorageManager.resolveManagedAttachment(root, symlink.absolutePath)
            assertNull(result)
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `existing symlink to outside regular file returns Suspicious`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val outsideFile = File(outsideDir, "secret.txt")
        outsideFile.writeText("secret data")
        val symlink = File(root, "evil_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(outsideFile.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val result = AttachmentStorageManager.classifyAttachment(root, symlink.absolutePath)
            assertTrue("expected Suspicious for existing symlink to outside file", result is AttachmentClassification.Suspicious)
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `broken symlink to outside missing file returns Suspicious`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val missingFile = File(outsideDir, "nonexistent.txt")
        val symlink = File(root, "broken_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(missingFile.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val result = AttachmentStorageManager.classifyAttachment(root, symlink.absolutePath)
            assertTrue("expected Suspicious for broken symlink", result is AttachmentClassification.Suspicious)
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `symlink to outside directory returns Suspicious`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val symlink = File(root, "dir_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(outsideDir.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val result = AttachmentStorageManager.classifyAttachment(root, symlink.absolutePath)
            assertTrue("expected Suspicious for symlink to directory", result is AttachmentClassification.Suspicious)
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `non-symlink missing managed file remains Unavailable`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val missing = File(root, "normal_missing.txt")
        val result = AttachmentStorageManager.classifyAttachment(root, missing.absolutePath)
        assertTrue("expected Unavailable for regular missing managed file", result is AttachmentClassification.Unavailable)
        assertEquals(missing.canonicalPath, (result as AttachmentClassification.Unavailable).canonicalPath)
        root.deleteRecursively()
    }

    @Test
    fun `cleanup helper excludes symlink v3 record`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val outsideFile = File(outsideDir, "secret.txt")
        outsideFile.writeText("secret data")
        val symlink = File(root, "evil_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(outsideFile.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val tag = makeV3Tag(AttachmentRecord("evil_link", "text/plain", symlink.absolutePath))
            val contents = listOf("$tag body")
            val result = extractReferencedAttachmentPaths(contents, root)
            assertTrue("expected empty references for symlink v3 record", result.isEmpty())
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch empty candidates returns empty records`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val result = validateAttachmentBatch(emptyList(), root)
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch all trusted returns canonical records in order`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val a = File(root, "a.txt")
        a.writeText("data")
        val b = File(root, "b.txt")
        b.writeText("data")
        val candidates = listOf(
            AttachmentCandidate("a.txt", "text/plain", a.absolutePath),
            AttachmentCandidate("b.txt", "text/plain", b.absolutePath)
        )
        val result = validateAttachmentBatch(candidates, root)
        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals(a.canonicalPath, result[0].localPath)
        assertEquals(b.canonicalPath, result[1].localPath)
        assertEquals("a.txt", result[0].name)
        assertEquals("b.txt", result[1].name)
        root.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch one unavailable rejects whole batch`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val valid = File(root, "valid.txt")
        valid.writeText("data")
        val missing = File(root, "missing.txt")
        val candidates = listOf(
            AttachmentCandidate("valid.txt", "text/plain", valid.absolutePath),
            AttachmentCandidate("missing.txt", "text/plain", missing.absolutePath)
        )
        val result = validateAttachmentBatch(candidates, root)
        assertNull("expected null batch when one item is unavailable", result)
        root.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch one directory rejects whole batch`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val valid = File(root, "valid.txt")
        valid.writeText("data")
        val dir = File(root, "subdir")
        dir.mkdirs()
        val candidates = listOf(
            AttachmentCandidate("valid.txt", "text/plain", valid.absolutePath),
            AttachmentCandidate("subdir", "inode/directory", dir.absolutePath)
        )
        val result = validateAttachmentBatch(candidates, root)
        assertNull("expected null batch when one item is a directory", result)
        root.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch one symlink rejects whole batch`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val valid = File(root, "valid.txt")
        valid.writeText("data")
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val outsideFile = File(outsideDir, "secret.txt")
        outsideFile.writeText("data")
        val symlink = File(root, "evil_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(outsideFile.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val candidates = listOf(
                AttachmentCandidate("valid.txt", "text/plain", valid.absolutePath),
                AttachmentCandidate("evil_link", "text/plain", symlink.absolutePath)
            )
            val result = validateAttachmentBatch(candidates, root)
            assertNull("expected null batch when one item is a symlink", result)
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch one broken symlink rejects whole batch`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val valid = File(root, "valid.txt")
        valid.writeText("data")
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val missingFile = File(outsideDir, "nonexistent.txt")
        val symlink = File(root, "broken_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(missingFile.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val candidates = listOf(
                AttachmentCandidate("valid.txt", "text/plain", valid.absolutePath),
                AttachmentCandidate("broken_link", "text/plain", symlink.absolutePath)
            )
            val result = validateAttachmentBatch(candidates, root)
            assertNull("expected null batch when one item is a broken symlink", result)
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch one outside-root item rejects whole batch`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val valid = File(root, "valid.txt")
        valid.writeText("data")
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val outsideFile = File(outsideDir, "outside.txt")
        outsideFile.writeText("data")
        val candidates = listOf(
            AttachmentCandidate("valid.txt", "text/plain", valid.absolutePath),
            AttachmentCandidate("outside.txt", "text/plain", outsideFile.absolutePath)
        )
        val result = validateAttachmentBatch(candidates, root)
        assertNull("expected null batch when one item is outside root", result)
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch duplicate valid candidates remain deterministic`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val f = File(root, "dup.txt")
        f.writeText("data")
        val candidates = listOf(
            AttachmentCandidate("dup.txt", "text/plain", f.absolutePath),
            AttachmentCandidate("dup.txt", "text/plain", f.absolutePath)
        )
        val result = validateAttachmentBatch(candidates, root)
        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals(f.canonicalPath, result[0].localPath)
        assertEquals(f.canonicalPath, result[1].localPath)
        root.deleteRecursively()
    }

    @Test
    fun `validateAttachmentBatch no candidate silently omitted`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val files = (1..5).map { i ->
            File(root, "f$i.txt").also { it.writeText("data$i") }
        }
        val candidates = files.mapIndexed { i, f ->
            AttachmentCandidate("f${i+1}.txt", "text/plain", f.absolutePath)
        }
        val result = validateAttachmentBatch(candidates, root)
        assertNotNull(result)
        assertEquals(5, result!!.size)
        candidates.zip(result).forEach { (expected, actual) ->
            assertEquals(expected.name, actual.name)
        }
        root.deleteRecursively()
    }

    @Test
    fun `cleanup helper excludes broken symlink v3 record`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outsideDir = Files.createTempDirectory("outside").toFile()
        val missingFile = File(outsideDir, "nonexistent.txt")
        val symlink = File(root, "broken_link")
        val created = try {
            Files.createSymbolicLink(symlink.toPath(), Path.of(missingFile.canonicalPath))
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            val tag = makeV3Tag(AttachmentRecord("broken_link", "text/plain", symlink.absolutePath))
            val contents = listOf("$tag body")
            val result = extractReferencedAttachmentPaths(contents, root)
            assertTrue("expected empty references for broken symlink v3 record", result.isEmpty())
            Files.delete(symlink.toPath())
        }
        root.deleteRecursively()
        outsideDir.deleteRecursively()
    }

    @Test
    fun `stored v3 message paths match canonical records attachment IDs and multimodal paths`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val doc = File(root, "doc.txt")
        doc.writeText("data")
        val img = File(root, "photo.jpg")
        img.writeText("image bytes")
        val candidates = listOf(
            AttachmentCandidate("doc.txt", "text/plain", doc.absolutePath),
            AttachmentCandidate("photo.jpg", "image/jpeg", img.absolutePath)
        )
        val validated = validateAttachmentBatch(candidates, root)!!
        val bodyText = "Hello from test"
        val serialized = AttachmentMessageCodec.serializeAttachmentMessage(validated, bodyText)
        assertFalse("v3 message must not contain v2 tag", serialized.contains("fusion_attachment_v2"))
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(serialized, root)
        assertFalse("envelope must not be suspicious", parsed.suspiciousEnvelope)
        assertEquals("parsed record count matches validated", validated.size, parsed.records.size)
        validated.zip(parsed.records).forEach { (expected, actual) ->
            assertEquals("canonical path match", expected.localPath, actual.localPath)
        }
        val attachmentIds = validated.map { it.localPath }
        assertEquals("attachment IDs match validated canonical paths", validated.map { it.localPath }, attachmentIds)
        val multimodalImagePaths = validated.filter { it.mimeType.startsWith("image/") }.map { it.localPath }
        assertEquals("multimodal image paths match image record canonical paths", listOf(img.canonicalPath), multimodalImagePaths)
        root.deleteRecursively()
    }

    @Test
    fun `stored v3 message round-trip preserves snapshot paths`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val doc = File(root, "snapshot_doc.txt")
        doc.writeText("data")
        val candidates = listOf(
            AttachmentCandidate("snapshot_doc.txt", "text/plain", doc.absolutePath)
        )
        val validated = validateAttachmentBatch(candidates, root)!!
        val body = "Snapshot body"
        val serialized = AttachmentMessageCodec.serializeAttachmentMessage(validated, body)
        val parsed = AttachmentMessageCodec.parseTrustedAttachmentMessage(serialized, root)
        assertEquals(1, parsed.records.size)
        assertEquals(validated[0].localPath, parsed.records[0].localPath)
        assertEquals(body, parsed.body.trim())
        root.deleteRecursively()
    }
    @Test
    fun `pending discard deletes direct managed file and unregisters it`() {
        val root = Files.createTempDirectory("discard_root").toFile()
        val file = File(root, "pending.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val result = AttachmentStorageManager.discardPendingAttachmentFile(root, file.absolutePath)

        assertEquals(PendingAttachmentDiscardResult.Deleted, result)
        assertFalse(file.exists())
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        root.deleteRecursively()
    }

    @Test
    fun `pending discard treats absent managed file as settled and unregisters it`() {
        val root = Files.createTempDirectory("discard_root").toFile()
        val file = File(root, "pending.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)
        assertTrue(file.delete())

        val result = AttachmentStorageManager.discardPendingAttachmentFile(root, file.absolutePath)

        assertEquals(PendingAttachmentDiscardResult.AlreadyAbsent, result)
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        root.deleteRecursively()
    }

    @Test
    fun `pending discard failure preserves registration`() {
        val root = Files.createTempDirectory("discard_root").toFile()
        val file = File(root, "pending.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val result = AttachmentStorageManager.discardPendingAttachmentFile(
            attachmentRoot = root,
            path = file.absolutePath,
            deleteFile = { false },
        )

        assertEquals(PendingAttachmentDiscardResult.DeletionFailed, result)
        assertTrue(file.exists())
        assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        AttachmentStorageManager.unregisterPendingAttachment(file.absolutePath)
        root.deleteRecursively()
    }

    @Test
    fun `pending discard rejects sibling prefix path`() {
        val parent = Files.createTempDirectory("discard_parent").toFile()
        val root = File(parent, "attachments").apply { mkdirs() }
        val sibling = File(parent, "attachments_backup").apply { mkdirs() }
        val file = File(sibling, "outside.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val result = AttachmentStorageManager.discardPendingAttachmentFile(root, file.absolutePath)

        assertEquals(PendingAttachmentDiscardResult.InvalidPath, result)
        assertTrue(file.exists())
        assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        AttachmentStorageManager.unregisterPendingAttachment(file.absolutePath)
        parent.deleteRecursively()
    }

    @Test
    fun `pending discard rejects nested target because imports are direct children`() {
        val root = Files.createTempDirectory("discard_root").toFile()
        val nestedDir = File(root, "nested").apply { mkdirs() }
        val file = File(nestedDir, "pending.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val result = AttachmentStorageManager.discardPendingAttachmentFile(root, file.absolutePath)

        assertEquals(PendingAttachmentDiscardResult.InvalidPath, result)
        assertTrue(file.exists())
        assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        AttachmentStorageManager.unregisterPendingAttachment(file.absolutePath)
        root.deleteRecursively()
    }


    @Test
    fun `pending discard rejects a managed directory`() {
        val root = Files.createTempDirectory("discard_root").toFile()
        val directory = File(root, "not_a_file").apply { mkdirs() }
        AttachmentStorageManager.registerPendingAttachment(directory)

        val result = AttachmentStorageManager.discardPendingAttachmentFile(root, directory.absolutePath)

        assertEquals(PendingAttachmentDiscardResult.InvalidTarget, result)
        assertTrue(directory.exists())
        assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(directory.absolutePath))
        AttachmentStorageManager.unregisterPendingAttachment(directory.absolutePath)
        root.deleteRecursively()
    }

    @Test
    fun `pending discard rejects final symlink`() {
        val root = Files.createTempDirectory("discard_root")
        val outside = Files.createTempFile("discard_outside", ".txt")
        val link = root.resolve("link.txt")
        assumeSymlinkCreated({ root.toFile().deleteRecursively(); outside.toFile().delete() }, link, outside)
        AttachmentStorageManager.registerPendingAttachment(link.toFile())

        val result = AttachmentStorageManager.discardPendingAttachmentFile(root.toFile(), link.toString())

        assertEquals(PendingAttachmentDiscardResult.InvalidTarget, result)
        assertTrue(Files.exists(outside))
        AttachmentStorageManager.unregisterPendingAttachment(link.toString())
        root.toFile().deleteRecursively()
        outside.toFile().delete()
    }

    @Test
    fun `pending discard rejects intermediate symlink escape`() {
        val root = Files.createTempDirectory("discard_root")
        val outsideDir = Files.createTempDirectory("discard_outside")
        val outsideFile = outsideDir.resolve("outside.txt")
        Files.writeString(outsideFile, "data")
        val linkDir = root.resolve("linked")
        assumeSymlinkCreated({ root.toFile().deleteRecursively(); outsideDir.toFile().deleteRecursively() }, linkDir, outsideDir)
        val escapedPath = linkDir.resolve("outside.txt")
        AttachmentStorageManager.registerPendingAttachment(escapedPath.toFile())

        val result = AttachmentStorageManager.discardPendingAttachmentFile(root.toFile(), escapedPath.toString())

        assertEquals(PendingAttachmentDiscardResult.InvalidPath, result)
        assertTrue(Files.exists(outsideFile))
        AttachmentStorageManager.unregisterPendingAttachment(escapedPath.toString())
        root.toFile().deleteRecursively()
        outsideDir.toFile().deleteRecursively()
    }

}
