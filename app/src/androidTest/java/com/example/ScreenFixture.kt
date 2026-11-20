package com.example

import android.app.Activity
import android.os.Bundle
import android.widget.*

/** External fixture: belongs to the test APK, never the MobHarn APK. */
class ScreenFixture : Activity() {
    private var steps = 0
    private var toggles = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(layout)
        fun button(label: String, next: () -> Unit) {
            layout.removeAllViews()
            layout.addView(Button(this).apply { text = label; setOnClickListener { steps++; next() } })
        }
        button("Archivio") {
            button("Dettagli") {
                layout.removeAllViews()
                val count = TextView(this).apply { text = "passi=$steps; modifiche=$toggles" }
                layout.addView(Switch(this).apply {
                    text = "Avvisi"
                    isChecked = intent.getBooleanExtra("enabled", false)
                    setOnCheckedChangeListener { _, _ -> toggles++; count.text = "passi=$steps; modifiche=$toggles" }
                })
                layout.addView(count)
            }
        }
    }
}
