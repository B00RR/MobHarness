package com.example;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Runs independently in the fixture APK, without the target APK's Kotlin runtime. */
public class ScreenFixture extends Activity {
    private LinearLayout layout;
    private int steps, toggles;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        setContentView(layout);
        button("Archivio", () -> button("Dettagli", this::option));
    }
    private void button(String label, Runnable next) {
        layout.removeAllViews();
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(v -> { steps++; next.run(); });
        layout.addView(button);
    }
    private void option() {
        layout.removeAllViews();
        TextView count = new TextView(this);
        Switch item = new Switch(this);
        item.setText("Avvisi");
        item.setChecked(getIntent().getBooleanExtra("enabled", false));
        Runnable update = () -> count.setText("passi=" + steps + "; modifiche=" + toggles);
        item.setOnCheckedChangeListener((v,on) -> { toggles++; update.run(); });
        update.run();
        layout.addView(item);
        layout.addView(count);
    }
}
