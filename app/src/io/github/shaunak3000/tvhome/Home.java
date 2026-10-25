package io.github.shaunak3000.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class Home extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("x80k home · test build");
        text.setTextColor(Color.WHITE);
        text.setTextSize(32);
        text.setGravity(Gravity.CENTER);
        text.setBackgroundColor(Color.BLACK);
        setContentView(text);
    }

    // A home screen has nowhere to go back to.
    @Override
    public void onBackPressed() {
    }
}
