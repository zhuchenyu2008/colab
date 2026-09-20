package com.zhuchenyu.batterystep;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class BackgroundLogActivity extends Activity {
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_log);

        logText = findViewById(R.id.logText);
        Button backButton = findViewById(R.id.backButton);
        Button refreshButton = findViewById(R.id.refreshLogButton);
        Button clearButton = findViewById(R.id.clearLogButton);

        backButton.setOnClickListener(v -> finish());
        refreshButton.setOnClickListener(v -> refreshLogs());
        clearButton.setOnClickListener(v -> {
            BackgroundLogStore.clear(this);
            BackgroundLogStore.append(this, "日志", "用户清空后台日志");
            refreshLogs();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });

        refreshLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLogs();
    }

    private void refreshLogs() {
        if (logText != null) logText.setText(BackgroundLogStore.readAll(this));
    }
}
