package uz.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Groq {
    private val history = JSONArray()

    private const val SYSTEM = "Sen Jarvis ismli ovozli yordamchisan. Foydalanuvchi bilan o'zbek tilida " +
        "(lotin yozuvida) gaplash, agar u boshqa tilda so'rasa o'sha tilda javob ber. " +
        "Javoblar juda qisqa bo'lsin (1-3 gap), chunki ular ovoz bilan o'qiladi. " +
        "Markdown, ro'yxat va emoji ishlatma."

    @Synchronized
    fun ask(apiKey: String, model: String, userText: String): String {
        history.put(JSONObject().put("role", "user").put("content", userText))
        while (history.length() > 12) history.remove(0)

        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM))
        for (i in 0 until history.length()) msgs.put(history.get(i))

        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("max_completion_tokens", 1024)
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")

        val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                history.remove(history.length() - 1)
                return "Xato $code. " + when (code) {
                    401 -> "Groq kaliti noto'g'ri."
                    429 -> "Limit tugadi, birozdan keyin urinib ko'ring."
                    else -> "Model nomini tekshiring."
                }
            }
            val reply = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "").trim()
            history.put(JSONObject().put("role", "assistant").put("content", reply))
            return if (reply.isEmpty()) "Javob bo'sh keldi." else reply
        } catch (e: Exception) {
            history.remove(history.length() - 1)
            return "Internetga ulanib bo'lmadi."
        } finally {
            conn.disconnect()
        }
    }
}
