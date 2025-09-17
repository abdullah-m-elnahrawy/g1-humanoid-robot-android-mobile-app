package com.abdullah.g1edu.micstream

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MotionController(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("g1mic_prefs", Context.MODE_PRIVATE)

    private fun getRobotIp(): String? {
        val ip = prefs.getString("robot_ctrl_ip", null)
        return ip?.takeIf { it.isNotBlank() }
    }
    private fun getRobotPort(): Int {
        return prefs.getInt("robot_ctrl_port", 5577)
    }

    private fun sendJson(json: JSONObject) {
        val ip = getRobotIp()
        if (ip == null) {
            Toast.makeText(ctx, "Set Robot Control IP first.", Toast.LENGTH_SHORT).show()
            return
        }
        val port = getRobotPort()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val data = json.toString().toByteArray(Charsets.UTF_8)
                val addr = InetAddress.getByName(ip)
                DatagramSocket().use { sock ->
                    val pkt = DatagramPacket(data, data.size, addr, port)
                    sock.send(pkt)
                }
            } catch (e: Exception) {
                Log.e("MotionController", "sendJson error: ${e.message}", e)
            }
        }
    }

    fun sendMotionFile(file: String) {
        val payload = JSONObject()
            .put("type", "motion")
            .put("file", file)
        sendJson(payload)
        Toast.makeText(ctx, "Requested motion: $file", Toast.LENGTH_SHORT).show()
    }

    fun sendMotionPhrase(phrase: String) {
        val payload = JSONObject()
            .put("type", "motion")
            .put("phrase", phrase)
        sendJson(payload)
        Toast.makeText(ctx, "Requested: $phrase", Toast.LENGTH_SHORT).show()
    }

    fun requestRegistry() {
        val payload = JSONObject().put("type", "get_registry")
        sendJson(payload)
    }

    // Helpers to persist IP/port from UI
    fun saveRobotControl(ip: String, port: Int) {
        prefs.edit()
            .putString("robot_ctrl_ip", ip.trim())
            .putInt("robot_ctrl_port", port)
            .apply()
    }
    fun currentRobotControl(): Pair<String?, Int> {
        return getRobotIp() to getRobotPort()
    }
}
