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

// Multicast defaults for the mic stream (match robot listener)
private const val defaultGroupIp = "239.255.0.1"
private const val defaultPort = 5555

class MainActivity : AppCompatActivity() {

    private lateinit var streamer: AudioStreamer
    private lateinit var motion: MotionController

    // UI
    private lateinit var txtGroupIp: EditText
    private lateinit var txtPort: EditText
    private lateinit var chkUnicast: CheckBox
    private lateinit var btnPTT: Button

    // Robot ID (for control multicast)
    private lateinit var txtRobotId: EditText
    private lateinit var btnSaveRobot: Button

    // Motion buttons
    private lateinit var btnShake: Button
    private lateinit var btnSalute: Button
    private lateinit var btnWelcome: Button

    private val reqPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_LONG).show()
    }

    private fun haveMicPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestMic() {
        reqPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        motion = MotionController(this)

        txtGroupIp = findViewById(R.id.txtGroupIp)
        txtPort    = findViewById(R.id.txtPort)
        chkUnicast = findViewById(R.id.chkUnicast)
        btnPTT     = findViewById(R.id.btnPTT)

        txtRobotId   = findViewById(R.id.txtRobotId)
        btnSaveRobot = findViewById(R.id.btnSaveRobot)

        btnShake   = findViewById(R.id.btnShake)
        btnSalute  = findViewById(R.id.btnSalute)
        btnWelcome = findViewById(R.id.btnWelcome)

        // Pre-fill (and effectively ignore) IP/port UI — multicast only
        txtGroupIp.setText(defaultGroupIp)
        txtPort.setText(defaultPort.toString())
        chkUnicast.visibility = View.GONE

        // Restore & save robot_id
        txtRobotId.setText(motion.currentRobotId())
        btnSaveRobot.setOnClickListener {
            val id = txtRobotId.text.toString().ifBlank { "hasan" }
            motion.saveRobotId(id)
            Toast.makeText(this, "Saved Robot ID: $id", Toast.LENGTH_SHORT).show()
        }

        // Build the AudioStreamer using your constructor signature
        streamer = AudioStreamer(
            /* ctx = */ this,
            /* groupIp = */ defaultGroupIp,
            /* port = */ defaultPort,
            /* useUnicast = */ false,
            /* unicastIp = */ "",
            /* preferVoiceComm = */ true,
            /* onLog = */ { _ -> },
            /* onState = */ { _ -> }
        )

        // PTT
        btnPTT.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!haveMicPermission()) requestMic()
                    streamer.start()
                    btnPTT.text = getString(R.string.ptt_speaking)
                    btnPTT.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    streamer.stop()
                    btnPTT.text = getString(R.string.ptt_hold)
                    btnPTT.isPressed = false
                }
            }
            true
        }

        // Motion buttons → multicast JSON (no IP typing), includes robot_id
        btnShake.setOnClickListener  { motion.sendMotionFile("shake_hands.seq") }
        btnSalute.setOnClickListener { motion.sendMotionFile("military_salute.seq") }
        btnWelcome.setOnClickListener{ motion.sendMotionFile("welcome_visitors.seq") }
    }

    override fun onDestroy() {
        try { streamer.stop() } catch (_: Throwable) {}
        super.onDestroy()
    }
}

