package com.abdullah.g1edu.micstream

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MotionController(private val ctx: Context) {
    companion object {
        private const val TAG = "MotionController"
        private const val DEFAULT_CTRL_GROUP = "239.255.0.2"
        private const val DEFAULT_CTRL_PORT  = 5577
        private const val PREFS = "g1.motion.prefs"
        private const val KEY_ROBOT_ID = "robot_id"
        private const val DEFAULT_ROBOT_ID = "hasan"
    }

    private val io = Executors.newSingleThreadExecutor()

    fun currentRobotId(): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROBOT_ID, DEFAULT_ROBOT_ID) ?: DEFAULT_ROBOT_ID

    fun saveRobotId(id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROBOT_ID, id.ifBlank { DEFAULT_ROBOT_ID }).apply()
    }

    fun sendMotionFile(file: String) {
        val j = JSONObject()
            .put("type", "motion")
            .put("file", file)
            .put("robot_id", currentRobotId())
        sendJson(j)
    }

    fun sendMotionPhrase(phrase: String) {
        val j = JSONObject()
            .put("type", "motion")
            .put("phrase", phrase)
            .put("robot_id", currentRobotId())
        sendJson(j)
    }

    fun ping() {
        val j = JSONObject().put("type", "ping").put("robot_id", currentRobotId())
        sendJson(j)
    }

    private fun sendJson(payload: JSONObject) {
        val bytes = payload.toString().toByteArray(Charset.forName("UTF-8"))
        io.execute {
            var s: DatagramSocket? = null
            try {
                val group = InetAddress.getByName(DEFAULT_CTRL_GROUP)
                val pkt = DatagramPacket(bytes, bytes.size, group, DEFAULT_CTRL_PORT)
                s = DatagramSocket()
                s.send(pkt)
                Log.i(TAG, "Sent ${payload.optString("type")} to $DEFAULT_CTRL_GROUP:$DEFAULT_CTRL_PORT (${bytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "send failed", e)
                Toast.makeText(ctx, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                try { s?.close() } catch (_: Throwable) {}
            }
        }
    }
}

