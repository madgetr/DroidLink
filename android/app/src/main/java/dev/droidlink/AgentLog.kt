package dev.droidlink

import org.json.JSONArray
import org.json.JSONObject

/** Conversation/mission log shown in the control panel. The laptop agent posts to it. */
class AgentLog {
    private val items = ArrayDeque<JSONObject>()
    private var seq = 0L
    @Volatile var lastAgentPost = 0L     // to show whether the agent is running
        private set

    fun add(who: String, text: String, fromAgent: Boolean): JSONObject = synchronized(items) {
        if (fromAgent) lastAgentPost = System.currentTimeMillis()
        val item = JSONObject().put("seq", seq++).put("ts", now()).put("who", who).put("text", text.take(500))
        items.addLast(item)
        while (items.size > 200) items.removeFirst()
        item
    }

    fun since(n: Long): JSONObject = synchronized(items) {
        JSONObject().put("next", seq).put("items", JSONArray(items.filter { it.getLong("seq") >= n }))
    }
}
