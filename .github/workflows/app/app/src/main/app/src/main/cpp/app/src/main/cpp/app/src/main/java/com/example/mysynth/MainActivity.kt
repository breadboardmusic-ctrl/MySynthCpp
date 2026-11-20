package com.example.mysynth

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
    private external fun setParams(wave: Int, cutoff: Float, attack: Float, release: Float)

    private var wave = 1
    private var cutoff = 2500f
    private var attack = 0.02f
    private var release = 0.3f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121218"))
            setPadding(24, 24, 24, 24)
        }

        // Barra controlli superiori
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val waveBtn = Button(this).apply {
            text = "ONDA: SAW"
            setBackgroundColor(Color.parseColor("#FF5722"))
            setTextColor(Color.WHITE)
            val names = arrayOf("SINE", "SAW", "SQUARE", "TRI")
            setOnClickListener {
                wave = (wave + 1) % 4
                text = "ONDA: ${names[wave]}"
                updateEngine()
            }
        }
        controls.addView(waveBtn)

        fun addSlider(label: String, max: Int, initial: Int, onChange: (Int) -> Unit) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 0, 12, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.LTGRAY)
                textSize = 12f
            }
            val sb = SeekBar(this).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        onChange(p)
                        updateEngine()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            box.addView(tv)
            box.addView(sb)
            controls.addView(box)
        }

        addSlider("FILTRO CUTOFF", 5000, 2500) { cutoff = 100f + it }
        addSlider("ATTACK", 100, 2) { attack = 0.01f + (it / 100f) * 1.5f }
        addSlider("RELEASE", 100, 30) { release = 0.05f + (it / 100f) * 2.0f }

        root.addView(controls)

        // Tastiera a 1 ottava (Do4 -> Do5)
        val keyboard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f
            )
        }

        val notes = listOf(
            Pair("DO", 60), Pair("DO#", 61), Pair("RE", 62), Pair("RE#", 63),
            Pair("MI", 64), Pair("FA", 65), Pair("FA#", 66), Pair("SOL", 67),
            Pair("SOL#", 68), Pair("LA", 69), Pair("LA#", 70), Pair("SI", 71), Pair("DO5", 72)
        )

        for ((name, midi) in notes) {
            val isSharp = name.contains("#")
            val key = Button(this).apply {
                text = name
                setTextColor(if (isSharp) Color.WHITE else Color.BLACK)
                setBackgroundColor(if (isSharp) Color.parseColor("#2A2A35") else Color.WHITE)
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                params.setMargins(4, 4, 4, 4)
                layoutParams = params

                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.alpha = 0.6f
                            noteOn(midi)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.alpha = 1.0f
                            noteOff(midi)
                        }
                    }
                    true
                }
            }
            keyboard.addView(key)
        }

        root.addView(keyboard)
        setContentView(root)
    }

    private fun updateEngine() {
        setParams(wave, cutoff, attack, release)
    }

    override fun onResume() { super.onResume(); startEngine(); updateEngine() }
    override fun onPause() { stopEngine(); super.onPause() }
}
