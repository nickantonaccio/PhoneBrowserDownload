package com.example.phonebrowserdownload;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 100;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button start;
    private Uri selected;
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 700); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String uri = getPreferences(MODE_PRIVATE).getString("folder", null);
        if (uri != null) selected = Uri.parse(uri);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 35, 28, 20);
        TextView title = new TextView(this);
        title.setText("Phone Browser Download");
        title.setTextSize(23);
        root.addView(title);
        TextView description = new TextView(this);
        description.setText("Share a selected folder for read-only downloads over your local Wi-Fi network. The other computer needs only a web browser.\n");
        description.setTextSize(15);
        root.addView(description);
        Button choose = new Button(this);
        choose.setText("1. Choose folder to share");
        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, PICK_FOLDER);
        });
        root.addView(choose);
        start = new Button(this);
        start.setText("2. Start sharing");
        start.setOnClickListener(v -> {
            if (selected == null) return;
            Intent i = new Intent(this, PhoneServerService.class);
            i.setAction(PhoneServerService.START);
            i.putExtra("folder", selected.toString());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        });
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("Stop sharing");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, PhoneServerService.class);
            i.setAction(PhoneServerService.STOP);
            startService(i);
        });
        root.addView(stop);
        status = new TextView(this);
        status.setTextSize(16);
        status.setTextIsSelectable(true);
        status.setMovementMethod(new ScrollingMovementMethod());
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }
    @Override protected void onPause() { handler.removeCallbacks(refreshTask); super.onPause(); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                selected = uri;
                getPreferences(MODE_PRIVATE).edit().putString("folder", uri.toString()).apply();
                refresh();
            } catch (SecurityException e) {
                status.setText("Folder permission denied. Choose a different folder.\n" + e.getMessage());
            }
        }
    }

    private void refresh() {
        PhoneServerService.Info info = PhoneServerService.info;
        start.setEnabled(selected != null && !info.running);
        String folder = selected == null ? "No folder chosen." : "Folder selected (Android document picker permission granted).";
        if (info.running) {
            status.setText(folder + "\n\nSHARING IS ON\n\n" +
                "On the other computer, enter one of these addresses:\n" + info.addresses +
                "\nUsername: phone\nPassword: " + info.password +
                "\n\nBoth devices must be on the same local network. Keep the phone connected to Wi-Fi. " +
                "A foreground service keeps sharing active until you stop it or Android ends the service.\n\n" +
                "Security: Local HTTP is not encrypted. Use only on a trusted private network; stop sharing when finished.");
        } else {
            status.setText(folder + "\n\nSharing is OFF.\n" + (info.error == null ? "" : "\nLast error: " + info.error));
        }
    }
}
