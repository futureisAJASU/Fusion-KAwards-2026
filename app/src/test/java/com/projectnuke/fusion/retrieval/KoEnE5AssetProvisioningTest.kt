package com.projectnuke.fusion.retrieval

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoEnE5AssetProvisioningTest {

    private val payload = "ko-en-e5 provisioning payload".toByteArray()

    @Test
    fun `valid existing destination avoids copy`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin").apply { writeBytes(payload) }
            var opened = false

            val result = copyVerified(
                destination,
                openInput = {
                    opened = true
                    ByteArrayInputStream("replacement".toByteArray())
                },
            )

            assertEquals(destination, result)
            assertFalse(opened)
            assertArrayEquals(payload, destination.readBytes())
        }
    }

    @Test
    fun `concurrent operations receive distinct staging paths`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            val created = Collections.synchronizedList(mutableListOf<File>())
            val bothCreated = CountDownLatch(2)
            val factory: (File, String) -> File = { parent, baseName ->
                File.createTempFile("$baseName.", ".tmp", parent).also { staging ->
                    created += staging
                    bothCreated.countDown()
                    assertTrue(bothCreated.await(5, TimeUnit.SECONDS))
                }
            }
            val outcomes = arrayOfNulls<File>(2)

            val workers = (0..1).map { index ->
                thread(start = true) {
                    outcomes[index] = copyVerified(
                        destination,
                        stagingFileFactory = factory,
                    )
                }
            }
            workers.forEach { it.join(10_000) }

            assertEquals(2, created.distinct().size)
            assertTrue(outcomes.all { it == destination })
            assertTrue(KoEnE5AssetProvisioner.isValid(destination, sha256(payload), payload.size.toLong()))
        }
    }

    @Test
    fun `concurrent loser reuses valid winner and cleans its own staging`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            val created = Collections.synchronizedList(mutableListOf<File>())
            val bothCreated = CountDownLatch(2)
            val bothVerified = CountDownLatch(2)
            val moveCount = AtomicInteger()
            val factory: (File, String) -> File = { parent, baseName ->
                File.createTempFile("$baseName.", ".tmp", parent).also { staging ->
                    created += staging
                    bothCreated.countDown()
                    assertTrue(bothCreated.await(5, TimeUnit.SECONDS))
                }
            }
            val verifier: (File, String, Long) -> Boolean = { file, sha256, size ->
                val valid = KoEnE5AssetProvisioner.isValid(file, sha256, size)
                if (file != destination) {
                    bothVerified.countDown()
                    assertTrue(bothVerified.await(5, TimeUnit.SECONDS))
                }
                valid
            }
            val outcomes = arrayOfNulls<File>(2)

            val workers = (0..1).map { index ->
                thread(start = true) {
                    outcomes[index] = copyVerified(
                        destination,
                        stagingFileFactory = factory,
                        verify = verifier,
                        move = { source, target ->
                            moveCount.incrementAndGet()
                            KoEnE5AssetProvisioner.moveStagedAtomically(source, target)
                        },
                    )
                }
            }
            workers.forEach { it.join(10_000) }

            assertEquals(2, created.distinct().size)
            assertTrue(outcomes.all { it == destination })
            assertEquals(1, moveCount.get())
            assertTrue(created.none(File::exists))
            assertTrue(KoEnE5AssetProvisioner.isValid(destination, sha256(payload), payload.size.toLong()))
        }
    }

    @Test
    fun `move failure reuses a valid destination installed by another operation`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            var staging: File? = null

            val result = copyVerified(
                destination,
                stagingFileFactory = { parent, baseName ->
                    File.createTempFile("$baseName.", ".tmp", parent).also { staging = it }
                },
                move = { _, target ->
                    target.writeBytes(payload)
                    throw IOException("another operation installed destination")
                },
            )

            assertEquals(destination, result)
            assertTrue(KoEnE5AssetProvisioner.isValid(destination, sha256(payload), payload.size.toLong()))
            assertFalse(requireNotNull(staging).exists())
        }
    }

    @Test
    fun `move failure without a valid destination remains failure`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            val invalidBytes = "invalid destination".toByteArray()

            val result = copyVerified(
                destination,
                move = { _, target ->
                    target.writeBytes(invalidBytes)
                    throw IOException("move failed")
                },
            )

            assertNull(result)
            assertFalse(KoEnE5AssetProvisioner.isValid(destination, sha256(payload), payload.size.toLong()))
        }
    }

    @Test
    fun `different destinations can enter adoption independently`() {
        withTempDirectory { directory ->
            val destinations = listOf(File(directory, "one.bin"), File(directory, "two.bin"))
            val bothStaged = CountDownLatch(2)
            val bothAdopting = CountDownLatch(2)
            val factory: (File, String) -> File = { parent, baseName ->
                File.createTempFile("$baseName.", ".tmp", parent).also {
                    bothStaged.countDown()
                    assertTrue(bothStaged.await(5, TimeUnit.SECONDS))
                }
            }
            val outcomes = arrayOfNulls<File>(2)

            val workers = destinations.mapIndexed { index, destination ->
                thread(start = true) {
                    outcomes[index] = copyVerified(
                        destination,
                        stagingFileFactory = factory,
                        move = { source, target ->
                            bothAdopting.countDown()
                            assertTrue(bothAdopting.await(5, TimeUnit.SECONDS))
                            KoEnE5AssetProvisioner.moveStagedAtomically(source, target)
                        },
                    )
                }
            }
            workers.forEach { it.join(10_000) }

            assertTrue(outcomes.indices.all { outcomes[it] == destinations[it] })
            assertTrue(destinations.all { KoEnE5AssetProvisioner.isValid(it, sha256(payload), payload.size.toLong()) })
        }
    }

    @Test
    fun `oversized input fails and deletes owned staging`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            var staging: File? = null
            val oversized = payload + byteArrayOf(1)

            val result = copyVerified(
                destination,
                input = oversized,
                expectedSize = payload.size.toLong(),
                stagingFileFactory = { parent, baseName ->
                    File.createTempFile("$baseName.", ".tmp", parent).also { staging = it }
                },
            )

            assertNull(result)
            assertFalse(requireNotNull(staging).exists())
        }
    }

    @Test
    fun `undersized input fails verification and deletes staging`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            var staging: File? = null
            val expected = payload + byteArrayOf(2)

            val result = copyVerified(
                destination,
                expectedSize = expected.size.toLong(),
                expectedSha256 = sha256(expected),
                stagingFileFactory = { parent, baseName ->
                    File.createTempFile("$baseName.", ".tmp", parent).also { staging = it }
                },
            )

            assertNull(result)
            assertFalse(requireNotNull(staging).exists())
        }
    }

    @Test
    fun `wrong SHA fails verification and deletes staging`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            var staging: File? = null

            val result = copyVerified(
                destination,
                expectedSha256 = "0".repeat(64),
                stagingFileFactory = { parent, baseName ->
                    File.createTempFile("$baseName.", ".tmp", parent).also { staging = it }
                },
            )

            assertNull(result)
            assertFalse(requireNotNull(staging).exists())
        }
    }

    @Test
    fun `write exception cleans only owned staging`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            var staging: File? = null

            val result = copyVerified(
                destination,
                stagingFileFactory = { parent, baseName ->
                    File.createTempFile("$baseName.", ".tmp", parent).also { staging = it }
                },
                openOutput = { throw IOException("write failed") },
            )

            assertNull(result)
            assertFalse(requireNotNull(staging).exists())
            assertFalse(destination.exists())
        }
    }

    @Test
    fun `staging creation failure fails soft`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")

            val result = copyVerified(
                destination,
                stagingFileFactory = { _, _ -> throw IOException("staging unavailable") },
            )

            assertNull(result)
            assertFalse(destination.exists())
        }
    }

    @Test
    fun `fsync failure does not replace target`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin").apply { writeBytes("old".toByteArray()) }
            var staging: File? = null

            val result = copyVerified(
                destination,
                stagingFileFactory = { parent, baseName ->
                    File.createTempFile("$baseName.", ".tmp", parent).also { staging = it }
                },
                syncOutput = { throw IOException("fsync failed") },
            )

            assertNull(result)
            assertEquals("old", destination.readText())
            assertFalse(requireNotNull(staging).exists())
        }
    }

    @Test
    fun `successful adoption replaces invalid existing target`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin").apply { writeBytes("invalid".toByteArray()) }

            val result = copyVerified(destination)

            assertEquals(destination, result)
            assertArrayEquals(payload, destination.readBytes())
        }
    }

    @Test
    fun `successful adoption leaves no staging file`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            var staging: File? = null

            val result = copyVerified(
                destination,
                stagingFileFactory = { parent, baseName ->
                    File.createTempFile("$baseName.", ".tmp", parent).also { staging = it }
                },
            )

            assertEquals(destination, result)
            assertFalse(requireNotNull(staging).exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun `atomic move not supported uses replacement fallback`() {
        withTempDirectory { directory ->
            val source = File(directory, "source.tmp").apply { writeBytes(payload) }
            val destination = File(directory, "asset.bin")
            var atomicAttempts = 0
            var replacementAttempts = 0

            KoEnE5AssetProvisioner.moveStagedAtomically(
                source,
                destination,
                atomicMove = { _, _ ->
                    atomicAttempts += 1
                    throw AtomicMoveNotSupportedException("source", "destination", "forced")
                },
                replacementMove = { from, to ->
                    replacementAttempts += 1
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                },
            )

            assertEquals(1, atomicAttempts)
            assertEquals(1, replacementAttempts)
            assertArrayEquals(payload, destination.readBytes())
            assertFalse(source.exists())
        }
    }

    @Test
    fun `failed operation never deletes another operation staging`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            val foreign = File(directory, "foreign.tmp").apply { writeBytes("foreign".toByteArray()) }
            var owned: File? = null

            val result = copyVerified(
                destination,
                stagingFileFactory = { parent, baseName ->
                    File.createTempFile("$baseName.", ".tmp", parent).also { owned = it }
                },
                openOutput = { throw IOException("owned write failed") },
            )

            assertNull(result)
            assertFalse(requireNotNull(owned).exists())
            assertTrue(foreign.exists())
        }
    }

    @Test
    fun `post-adoption verification is authoritative`() {
        withTempDirectory { directory ->
            val destination = File(directory, "asset.bin")
            var adopted = false
            val verifier: (File, String, Long) -> Boolean = { file, sha, size ->
                if (file == destination && adopted) false
                else KoEnE5AssetProvisioner.isValid(file, sha, size)
            }

            val result = copyVerified(
                destination,
                verify = verifier,
                move = { source, target ->
                    KoEnE5AssetProvisioner.moveStagedAtomically(source, target)
                    adopted = true
                },
            )

            assertNull(result)
            assertFalse(destination.exists())
        }
    }

    private fun copyVerified(
        destination: File,
        input: ByteArray = payload,
        expectedSize: Long = input.size.toLong(),
        expectedSha256: String = sha256(input),
        openInput: () -> InputStream = { ByteArrayInputStream(input) },
        stagingFileFactory: (File, String) -> File = { parent, baseName ->
            File.createTempFile("$baseName.", ".tmp", parent)
        },
        verify: (File, String, Long) -> Boolean = { file, sha, size ->
            KoEnE5AssetProvisioner.isValid(file, sha, size)
        },
        openOutput: (File) -> FileOutputStream = ::FileOutputStream,
        syncOutput: (FileOutputStream) -> Unit = { output -> output.fd.sync() },
        move: (File, File) -> Unit = { source, target ->
            KoEnE5AssetProvisioner.moveStagedAtomically(source, target)
        },
    ): File? = KoEnE5AssetProvisioner.copyVerifiedFromStream(
        dest = destination,
        expectedSha256 = expectedSha256,
        expectedSize = expectedSize,
        openInput = openInput,
        stagingFileFactory = stagingFileFactory,
        verify = verify,
        openOutput = openOutput,
        syncOutput = syncOutput,
        move = move,
    )

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("koen-e5-provisioning").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
