package com.abdullah.g1edu.micstream

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * UI now exposes TWO isolated PTT buttons:
 *  - 🤖 Robot: streams to BuildConfig.ROBOT_GROUP_IP:ROBOT_PORT (239.255.0.1:5555)
 *  - 🗄️ Database: streams to BuildConfig.DB_GROUP_IP:DB_PORT (239.255.0.3:5556)
 *
 * Only one streamer runs at a time; pressing one stops the other.
 * Motion buttons use emoji icons for a language-agnostic UI.
 */
class MainActivity : AppCompatActivity() {

    // Two independent streamers (isolated multicast groups)
    private lateinit var streamerRobot: AudioStreamer
    private lateinit var streamerDb: AudioStreamer

    private lateinit var motion: MotionController

    // (Kept for compatibility; not used for routing anymore, and hidden in UI)
    private lateinit var txtGroupIp: EditText
    private lateinit var txtPort: EditText
    private lateinit var chkUnicast: CheckBox

    // PTT buttons
    private lateinit var btnPTTRobot: Button
    private lateinit var btnPTTDb: Button

    // Robot ID (for control multicast)
    private lateinit var txtRobotId: EditText
    private lateinit var btnSaveRobot: Button

    // Motion buttons (icons only)
    private lateinit var btnShake: Button
    private lateinit var btnSalute: Button
    private lateinit var btnWelcome: Button

    private val reqPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, getString(R.string.err_mic_perm), Toast.LENGTH_LONG).show()
        }
        // If needed you can auto-retry start on next press; we keep UX simple.
    }

    private fun haveMicPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        reqPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        motion = MotionController(this)

        // Bind UI
        txtGroupIp = findViewById(R.id.txtGroupIp)
        txtPort    = findViewById(R.id.txtPort)
        chkUnicast = findViewById(R.id.chkUnicast)

        btnPTTRobot = findViewById(R.id.btnPTTRobot)
        btnPTTDb    = findViewById(R.id.btnPTTDb)

        txtRobotId   = findViewById(R.id.txtRobotId)
        btnSaveRobot = findViewById(R.id.btnSaveRobot)

        btnShake   = findViewById(R.id.btnShake)
        btnSalute  = findViewById(R.id.btnSalute)
        btnWelcome = findViewById(R.id.btnWelcome)

        // Hide legacy inputs (we route via constants)
        txtGroupIp.visibility = View.GONE
        txtPort.visibility = View.GONE
        chkUnicast.visibility = View.GONE

        // Restore & save robot_id
        txtRobotId.setText(motion.currentRobotId())
        btnSaveRobot.setOnClickListener {
            val id = txtRobotId.text.toString().ifBlank { "hasan" }
            motion.saveRobotId(id)
            Toast.makeText(this, getString(R.string.toast_saved_robot_id, id), Toast.LENGTH_SHORT).show()
        }

        // Build streamers (each goes to a different multicast group)
        streamerRobot = AudioStreamer(
            context = this,
            groupIp = BuildConfig.ROBOT_GROUP_IP,
            port = BuildConfig.ROBOT_PORT,
            useUnicast = false,
            unicastIp = "",
            preferVoiceComm = true,
            onLog = { /* no-op */ },
            onState = { /* no-op */ }
        )
        streamerDb = AudioStreamer(
            context = this,
            groupIp = BuildConfig.DB_GROUP_IP,
            port = BuildConfig.DB_PORT,
            useUnicast = false,
            unicastIp = "",
            preferVoiceComm = true,
            onLog = { /* no-op */ },
            onState = { /* no-op */ }
        )

        // Wire PTT listeners (mutually exclusive)
        btnPTTRobot.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!haveMicPermission()) {
                        requestMic()
                        return@setOnTouchListener true
                    }
                    if (streamerDb.isRunning()) streamerDb.stop()
                    streamerRobot.start()
                    btnPTTRobot.text = getString(R.string.ptt_robot_speaking)
                    btnPTTRobot.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    streamerRobot.stop()
                    btnPTTRobot.text = getString(R.string.ptt_robot_hold)
                    btnPTTRobot.isPressed = false
                }
            }
            true
        }

        btnPTTDb.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!haveMicPermission()) {
                        requestMic()
                        return@setOnTouchListener true
                    }
                    if (streamerRobot.isRunning()) streamerRobot.stop()
                    streamerDb.start()
                    btnPTTDb.text = getString(R.string.ptt_db_speaking)
                    btnPTTDb.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    streamerDb.stop()
                    btnPTTDb.text = getString(R.string.ptt_db_hold)
                    btnPTTDb.isPressed = false
                }
            }
            true
        }

        // Motion buttons → multicast JSON (includes robot_id)
        btnShake.setOnClickListener  { motion.sendMotionFile("shake_hands.seq") }
        btnSalute.setOnClickListener { motion.sendMotionFile("military_salute.seq") }
        btnWelcome.setOnClickListener{ motion.sendMotionFile("welcome_visitors.seq") }
    }

    override fun onDestroy() {
        try { streamerRobot.stop() } catch (_: Throwable) {}
        try { streamerDb.stop() } catch (_: Throwable) {}
        super.onDestroy()
    }
}

