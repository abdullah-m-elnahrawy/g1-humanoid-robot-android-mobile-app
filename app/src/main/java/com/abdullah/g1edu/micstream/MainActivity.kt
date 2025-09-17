package com.abdullah.g1edu.micstream

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

// Defaults matching your robot’s listener (mobile multicast pipeline)
private const val defaultGroupIp = "239.255.0.1"
private const val defaultPort = 5555

class MainActivity : AppCompatActivity() {

    private lateinit var streamer: AudioStreamer
    private lateinit var motion: MotionController

    private lateinit var txtGroupIp: EditText
    private lateinit var txtPort: EditText
    private lateinit var chkUnicast: CheckBox
    private lateinit var btnPTT: Button

    // NEW: robot control target
    private lateinit var txtRobotIp: EditText
    private lateinit var txtRobotPort: EditText
    private lateinit var btnSaveRobot: Button

    // NEW: motion buttons
    private lateinit var btnShake: Button
    private lateinit var btnSalute: Button
    private lateinit var btnWelcome: Button

    private val reqPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Mic permission required.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        motion = MotionController(this)

        txtGroupIp = findViewById(R.id.txtGroupIp)
        txtPort    = findViewById(R.id.txtPort)
        chkUnicast = findViewById(R.id.chkUnicast)
        btnPTT     = findViewById(R.id.btnPTT)

        txtRobotIp   = findViewById(R.id.txtRobotIp)
        txtRobotPort = findViewById(R.id.txtRobotPort)
        btnSaveRobot = findViewById(R.id.btnSaveRobot)

        btnShake  = findViewById(R.id.btnShake)
        btnSalute = findViewById(R.id.btnSalute)
        btnWelcome= findViewById(R.id.btnWelcome)

        // Restore last robot control target
        val (curIp, curPort) = motion.currentRobotControl()
        txtRobotIp.setText(curIp ?: "")
        txtRobotPort.setText(curPort.toString())

        btnSaveRobot.setOnClickListener {
            val ip = txtRobotIp.text.toString()
            val p  = txtRobotPort.text.toString().toIntOrNull() ?: 5577
            motion.saveRobotControl(ip, p)
            Toast.makeText(this, "Saved Robot Control $ip:$p", Toast.LENGTH_SHORT).show()
        }

        // Initialize UI defaults for audio target
        txtGroupIp.setText(defaultGroupIp)
        txtPort.setText(defaultPort.toString())

        // PTT: create a streamer with current UI values on press, stop on release
        btnPTT.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!haveMicPermission()) requestMic()

                    // Stop any existing instance before creating a new one
                    if (::streamer.isInitialized) {
                        try { streamer.stop() } catch (_: Throwable) {}
                    }

                    val groupIp: String = txtGroupIp.text.toString().ifBlank { defaultGroupIp }
                    val port: Int = txtPort.text.toString().toIntOrNull() ?: defaultPort
                    val useUnicast: Boolean = chkUnicast.isChecked
                    // Ensure non-null for constructor
                    val unicastIp: String = if (useUnicast) groupIp else ""

                    // New API: configure via constructor
                    streamer = AudioStreamer(
                        context = this,
                        groupIp = groupIp,
                        port = port,
                        useUnicast = useUnicast,
                        unicastIp = unicastIp,           // always non-null
                        preferVoiceComm = true,
                        onLog = { msg -> android.util.Log.d("G1MicStream", msg) },
                        onState = { /* state -> update status UI if desired */ }
                    )
                    streamer.start()

                    btnPTT.text = getString(R.string.ptt_speaking)
                    btnPTT.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (::streamer.isInitialized) {
                        try { streamer.stop() } catch (_: Throwable) {}
                    }
                    btnPTT.text = getString(R.string.ptt_hold)
                    btnPTT.isPressed = false
                }
            }
            true
        }

        // NEW: motion buttons -> send UDP JSON to robot control port
        btnShake.setOnClickListener {
            motion.sendMotionFile("shake_hands.seq")
        }
        btnSalute.setOnClickListener {
            motion.sendMotionFile("military_salute.seq")
        }
        btnWelcome.setOnClickListener {
            motion.sendMotionFile("welcome_visitors.seq")
        }
    }

    private fun haveMicPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    private fun requestMic() {
        reqPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        try { if (::streamer.isInitialized) streamer.stop() } catch (_: Throwable) {}
        super.onDestroy()
    }
}

