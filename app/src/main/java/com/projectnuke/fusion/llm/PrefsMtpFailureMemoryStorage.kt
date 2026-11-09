package com.projectnuke.fusion.llm

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed [MtpFailureMemoryStorage] so MTP failure memory
 * survives engine unloads and process restarts.
 */
internal class PrefsMtpFailureMemoryStorage(context: Context) : MtpFailureMemoryStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fusion_mtp_failure_memory", Context.MODE_PRIVATE)

    override fun load(): Map<String, String> = prefs.all
        .mapNotNull { (key, value) ->
            val stringValue = value as? String ?: return@mapNotNull null
            key to stringValue
        }
        .toMap()

    override fun save(entries: Map<String, String>): Boolean =
        prefs.edit().apply {
            clear()
            entries.forEach { (key, value) -> putString(key, value) }
        }.commit()

    override fun clear(): Boolean = prefs.edit().clear().commit()
}
