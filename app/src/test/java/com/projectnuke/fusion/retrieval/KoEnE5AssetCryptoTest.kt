package com.projectnuke.fusion.retrieval

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cryptographic asset identity on the JVM (no Android runtime needed):
 * the packaged files must exactly match the pinned SHA-256 values, and any
 * corruption must fail closed.
 */
class KoEnE5AssetCryptoTest {

    private fun packaged(name: String): File = listOf(
        File("src/main/assets/koen-e5/$name"),
        File("app/src/main/assets/koen-e5/$name"),
    ).single { it.isFile }

    @Test
    fun `packaged model matches pinned hash and size`() {
        val model = packaged("model_qint8_arm64.onnx")
        assertEquals(KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SIZE_BYTES, model.length())
        assertEquals(
            KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SHA256,
            KoEnE5AssetProvisioner.sha256Hex(model),
        )
    }

    @Test
    fun `packaged tokenizer matches pinned hash and size`() {
        val tokenizer = packaged("tokenizer.json")
        assertEquals(KoEnE5ModelPolicy.TOKENIZER_JSON_SIZE_BYTES, tokenizer.length())
        assertEquals(
            KoEnE5ModelPolicy.TOKENIZER_JSON_SHA256,
            KoEnE5AssetProvisioner.sha256Hex(tokenizer),
        )
    }

    @Test
    fun `verified provider reports Available for pinned files`() {
        val provider = VerifiedFileKoEnE5AssetProvider(
            packaged("model_qint8_arm64.onnx"),
            packaged("tokenizer.json"),
        )
        val model = provider.modelAsset() as KoEnE5AssetState.Available
        val tokenizer = provider.tokenizerAsset() as KoEnE5AssetState.Available
        assertEquals(KoEnE5ModelPolicy.MODEL_QINT8_ARM64_SIZE_BYTES, model.sizeBytes)
        assertEquals(KoEnE5ModelPolicy.TOKENIZER_JSON_SIZE_BYTES, tokenizer.sizeBytes)
    }

    @Test
    fun `single flipped byte fails closed`() {
        val tmp = File.createTempFile("koen-e5-corrupt", ".json")
        try {
            val bytes = packaged("tokenizer.json").readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
            tmp.writeBytes(bytes)
            assertFalse(
                KoEnE5AssetProvisioner.isValid(
                    tmp,
                    KoEnE5ModelPolicy.TOKENIZER_JSON_SHA256,
                    KoEnE5ModelPolicy.TOKENIZER_JSON_SIZE_BYTES,
                ),
            )
            val provider = VerifiedFileKoEnE5AssetProvider(
                packaged("model_qint8_arm64.onnx"),
                tmp,
            )
            val state = provider.tokenizerAsset()
            assertTrue(state is KoEnE5AssetState.Missing)
            assertTrue((state as KoEnE5AssetState.Missing).reason.contains("HASH_MISMATCH"))
        } finally {
            tmp.delete()
        }
    }
}
