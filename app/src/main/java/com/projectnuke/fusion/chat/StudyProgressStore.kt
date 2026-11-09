package com.projectnuke.fusion.chat

import com.projectnuke.fusion.util.withAtomicTextMutation
import com.projectnuke.fusion.util.writeTextAtomically
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/** One bounded private file per conversation. No document excerpts, answers or grading claims. */
internal class StudyProgressStore(private val root: File) {
    private fun file(id: Long): File { require(id > 0); return File(root, "study_$id.json") }
    fun load(id: Long): StudyProgress = withAtomicTextMutation(file(id)) { read(id) }
    private fun read(id: Long): StudyProgress {
        val file = file(id)
        if (!file.exists()) return StudyProgress(id)
        require(file.length() in 1..65536)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val raw = JSONObject(decoder.decode(ByteBuffer.wrap(file.readBytes())).toString())
        require(raw.getLong("conversationId") == id && raw.getLong("version") == 1L)
        val sessions = raw.getJSONArray("sessions")
        require(sessions.length() <= StudyProgressPolicy.MAX_SESSIONS)
        fun count(o: JSONObject, key: String): Int = o.getLong(key).also { require(it in 0..1000) }.toInt()
        return StudyProgress(id, List(sessions.length()) { i ->
            val s = sessions.getJSONObject(i)
            val topics = s.getJSONArray("topics"); val tokens = s.getJSONArray("tokens")
            require(topics.length() <= StudyProgressPolicy.MAX_TOPICS && tokens.length() <= StudyProgressPolicy.MAX_EVENTS)
            val sessionId = s.getString("id").also { require(it.isNotBlank() && it.length <= 128) }
            StudyProgressSession(sessionId,
                List(topics.length()) { n -> val t = topics.getJSONObject(n); StudyTopic(t.getString("name").also { require(it.isNotBlank() && it.length <= 80) }, t.getBoolean("review"), t.getBoolean("understood")) },
                List(tokens.length()) { n -> tokens.getString(n).also { require(it.isNotBlank() && it.length <= 128) } },
                count(s,"questions"), count(s,"attempts"), count(s,"hints"), s.getBoolean("awaiting"), s.getBoolean("ended"))
        })
    }
    fun update(id: Long, transform: (StudyProgress) -> StudyProgress): StudyProgress = withAtomicTextMutation(file(id)) {
        val state = transform(read(id)); require(state.conversationId == id)
        val sessions = JSONArray()
        state.sessions.forEach { s -> sessions.put(JSONObject().put("id",s.id).put("tokens",JSONArray(s.submittedTokens))
            .put("topics",JSONArray().apply { s.topics.forEach { put(JSONObject().put("name",it.name).put("review",it.review).put("understood",it.understood)) } })
            .put("questions",s.questionsRequested).put("attempts",s.attempts).put("hints",s.hints).put("awaiting",s.awaitingAttempt).put("ended",s.ended)) }
        val text = JSONObject().put("version",1).put("conversationId",id).put("sessions",sessions).toString()
        require(text.toByteArray(Charsets.UTF_8).size <= 65536)
        writeTextAtomically(file(id), text)
        state
    }
    fun delete(id: Long): Boolean = withAtomicTextMutation(file(id)) { !file(id).exists() || file(id).delete() }
}
