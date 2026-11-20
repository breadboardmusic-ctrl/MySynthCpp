package com.example.mysynth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        init { System.loadLibrary("mysynth") }
    }

    private external fun startEngine()
    private external fun stopEngine()
    private external fun noteOn(note: Int)
    private external fun noteOff(note: Int)
    private external fun setSynthParams(
        wave: Int, cutoff: Float, attack: Float, release: Float,
        lfoTarget: Int, lfoRate: Float, lfoDepth: Float
    )
    private external fun setSequencerState(playing: Boolean, bpm: Float)
    private external fun setSequencerStep(stepIndex: Int, midiNote: Int)
    private external fun getCurrentStep(): Int

    // Stato Synth
    private var wave = 1
    private var cutoff = 2500f
    private var attack = 0.02f
    private var release = 0.25f
    private var lfoTarget = 0 // 0=OFF, 1=PITCH, 2=CUTOFF
    private var lfoRate = 4.0f
    private var lfoDepth = 0.5f
    private var octaveShift = 0 // In semitoni (-24 .. +24)

    // Stato Sequencer
    private var seqPlaying = false
    private var seqBpm = 120f
    private val seqNotes = intArrayOf(60, 63, 67, 72, 67, 63, 60, -1)
    private var lastTouchedNote = 60
    private val stepButtons = ArrayList<Button>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val stepIndicatorRunnable = object : Runnable {
        override fun run() {
            val activeStep = if (seqPlaying) getCurrentStep() else -1
            for (i in 0 until 8) {
                val btn = stepButtons.getOrNull(i) ?: continue
                val note = seqNotes[i]
                btn.text = if (note >= 0) midiToName(note) else "---"
                when {
                    i == activeStep -> btn.setBackgroundColor(Color.parseColor("#00E676"))
                    note >= 0 -> btn.setBackgroundColor(Color.parseColor("#37474F"))
                    else -> btn.setBackgroundColor(Color.parseColor("#1C1C24"))
                }
            }
            uiHandler.postDelayed(this, 60)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101016"))
            setPadding(16, 12, 16, 12)
        }

        // --- RIGA 1: OSCILLATORE, FILTRO, ADSR, LFO ---
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f)
        }

        val waveBtn = makeButton("ONDA\nSAW", "#FF5722") { btn ->
            val names = arrayOf("SINE", "SAW", "SQR", "TRI")
            wave = (wave + 1) % 4
            btn.text = "ONDA\n${names[wave]}"
            updateEngine()
        }
        row1.addView(waveBtn)

        val lfoBtn = makeButton("LFO\nOFF", "#3F51B5") { btn ->
            val names = arrayOf("OFF", "PITCH", "CUTOFF")
            lfoTarget = (lfoTarget + 1) % 3
            btn.text = "LFO\n${names[lfoTarget]}"
            btn.setBackgroundColor(
                if (lfoTarget == 0) Color.parseColor("#3F51B5") else Color.parseColor("#9C27B0")
            )
            updateEngine()
        }
        row1.addView(lfoBtn)

        row1.addView(makeSlider("CUTOFF", 5000, 2500) { cutoff = 80f + it; updateEngine() })
        row1.addView(makeSlider("RELEASE", 100, 25) { release = 0.04f + (it / 100f) * 1.8f; updateEngine() })
        row1.addView(makeSlider("LFO RATE", 150, 40) { lfoRate = 0.2f + (it / 10f); updateEngine() })
        row1.addView(makeSlider("LFO DEPTH", 100, 50) { lfoDepth = it / 100f; updateEngine() })

        root.addView(row1)

        // --- RIGA 2: SEQUENCER 8-STEP & OTTAVE ---
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.95f)
        }

        val playSeqBtn = makeButton("SEQ\nPLAY", "#2E7D32") { btn ->
            seqPlaying = !seqPlaying
            btn.text = if (seqPlaying) "SEQ\nSTOP" else "SEQ\nPLAY"
            btn.setBackgroundColor(
                if (seqPlaying) Color.parseColor("#C62828") else Color.parseColor("#2E7D32")
            )
            setSequencerState(seqPlaying, seqBpm)
        }
        row2.addView(playSeqBtn)

        row2.addView(makeSlider("BPM: 120", 160, 60, weight = 1.2f) { p ->
            seqBpm = 60f + p
            setSequencerState(seqPlaying, seqBpm)
        })

        // 8 Pulsanti Step Sequencer
        for (i in 0 until 8) {
            val stepBtn = Button(this).apply {
                text = midiToName(seqNotes[i])
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(2, 2, 2, 2)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.75f)
                lp.setMargins(3, 2, 3, 2)
                layoutParams = lp
                setOnClickListener {
                    // Se lo step ha già l'ultima nota suonata, lo silenzia (-1), altrimenti imposta l'ultima nota
                    seqNotes[i] = if (seqNotes[i] == lastTouchedNote) -1 else lastTouchedNote
                    setSequencerStep(i, seqNotes[i])
                }
            }
            stepButtons.add(stepBtn)
            row2.addView(stepBtn)
        }

        // Controllo Ottava
        val octLabel = TextView(this).apply {
            text = "OCT\n0"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
        }

        val octDown = makeButton("OCT -", "#455A64", weight = 0.65f) {
            if (octaveShift > -24) octaveShift -= 12
            octLabel.text = "OCT\n${octaveShift / 12}"
        }
        val octUp = makeButton("OCT +", "#455A64", weight = 0.65f) {
            if (octaveShift < 24) octaveShift += 12
            octLabel.text = "OCT\n${if (octaveShift > 0) "+" else ""}${octaveShift / 12}"
        }
        row2.addView(octDown)
        row2.addView(octLabel)
        row2.addView(octUp)

        root.addView(row2)

        // --- RIGA 3: TASTIERA MULTI-TOUCH CON GLISSANDO ---
        val keyboardView = PianoKeyboardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.2f)
        }
        root.addView(keyboardView)

        setContentView(root)
    }

    private fun makeButton(label: String, hexColor: String, weight: Float = 0.85f, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            lp.setMargins(4, 2, 4, 2)
            layoutParams = lp
            setOnClickListener { onClick(this) }
        }
    }

    private fun makeSlider(label: String, max: Int, initial: Int, weight: Float = 1f, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 0, 6, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.LTGRAY)
            textSize = 10f
        }
        val sb = SeekBar(this).apply {
            this.max = max
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (label.startsWith("BPM")) tv.text = "BPM: ${60 + p}"
                    onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv)
        box.addView(sb)
        return box
    }

    private fun midiToName(midi: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return "${names[midi % 12]}${(midi / 12) - 1}"
    }

    private fun updateEngine() {
        setSynthParams(wave, cutoff, attack, release, lfoTarget, lfoRate, lfoDepth)
    }

    override fun onResume() {
        super.onResume()
        startEngine()
        updateEngine()
        for (i in 0 until 8) setSequencerStep(i, seqNotes[i])
        setSequencerState(seqPlaying, seqBpm)
        uiHandler.post(stepIndicatorRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(stepIndicatorRunnable)
        stopEngine()
        super.onPause()
    }

    // --- TASTIERA CUSTOM CON SUPPORTO GLISSANDO E MULTI-TOUCH ---
    inner class PianoKeyboardView(context: Context) : View(context) {
        private val whitePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val whitePressedPaint = Paint().apply { color = Color.parseColor("#B0BEC5"); style = Paint.Style.FILL }
        private val blackPaint = Paint().apply { color = Color.parseColor("#1E1E26"); style = Paint.Style.FILL }
        private val blackPressedPaint = Paint().apply { color = Color.parseColor("#FF5722"); style = Paint.Style.FILL }
        private val borderPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val textPaint = Paint().apply { color = Color.DKGRAY; textSize = 28f; textAlign = Paint.Align.CENTER }

        // 2 ottave di tasti bianchi (14 tasti: Do -> Si x2)
        private val whiteOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23)
        private val blackMap = mapOf(
            0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10,
            7 to 13, 8 to 15, 10 to 18, 11 to 20, 12 to 22
        )

        private val activePointers = mutableMapOf<Int, Int>() // pointerId -> midiNote

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val numWhite = whiteOffsets.size
            val keyW = width.toFloat() / numWhite
            val keyH = height.toFloat()
            val pressedNotes = activePointers.values.toSet()

            // Disegna tasti bianchi
            for (i in 0 until numWhite) {
                val midi = 60 + octaveShift + whiteOffsets[i]
                val rect = RectF(i * keyW, 0f, (i + 1) * keyW, keyH)
                canvas.drawRect(rect, if (pressedNotes.contains(midi)) whitePressedPaint else whitePaint)
                canvas.drawRect(rect, borderPaint)
                if (i % 7 == 0) {
                    canvas.drawText("C${(midi / 12) - 1}", rect.centerX(), keyH - 24f, textPaint)
                }
            }

            // Disegna tasti neri
            val blackW = keyW * 0.62f
            val blackH = keyH * 0.58f
            for ((whiteIdx, semitone) in blackMap) {
                val midi = 60 + octaveShift + semitone
                val left = (whiteIdx + 1) * keyW - (blackW / 2f)
                val rect = RectF(left, 0f, left + blackW, blackH)
                canvas.drawRect(rect, if (pressedNotes.contains(midi)) blackPressedPaint else blackPaint)
                canvas.drawRect(rect, borderPaint)
            }
        }

        private fun getNoteAt(x: Float, y: Float): Int {
            val numWhite = whiteOffsets.size
            val keyW = width.toFloat() / numWhite
            val blackW = keyW * 0.62f
            val blackH = height.toFloat() * 0.58f

            // Controlla prima i tasti neri (stanno sopra)
            if (y <= blackH) {
                for ((whiteIdx, semitone) in blackMap) {
                    val left = (whiteIdx + 1) * keyW - (blackW / 2f)
                    if (x >= left && x <= left + blackW) {
                        return 60 + octaveShift + semitone
                    }
                }
            }

            val whiteIdx = (x / keyW).toInt().coerceIn(0, numWhite - 1)
            return 60 + octaveShift + whiteOffsets[whiteIdx]
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = event.actionIndex
                    val id = event.getPointerId(idx)
                    val note = getNoteAt(event.getX(idx), event.getY(idx))
                    activePointers[id] = note
                    lastTouchedNote = note
                    noteOn(note)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    var changed = false
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        val newNote = getNoteAt(event.getX(i), event.getY(i))
                        val oldNote = activePointers[id]
                        if (oldNote != null && oldNote != newNote) {
                            noteOff(oldNote)
                            noteOn(newNote)
                            activePointers[id] = newNote
                            lastTouchedNote = newNote
                            changed = true
                        }
                    }
                    if (changed) invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val idx = event.actionIndex
                    val id = event.getPointerId(idx)
                    activePointers.remove(id)?.let { oldNote ->
                        if (!activePointers.containsValue(oldNote)) {
                            noteOff(oldNote)
                        }
                    }
                    invalidate()
                }
            }
            return true
        }
    }
}
