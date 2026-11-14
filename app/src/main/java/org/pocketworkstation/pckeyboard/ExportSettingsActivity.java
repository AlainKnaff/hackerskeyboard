package org.pocketworkstation.pckeyboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.preference.PreferenceManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.TextView.BufferType;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.app.PendingIntent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.ComponentActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipInputStream;

public class ExportSettingsActivity extends ComponentActivity {
    private ActivityResultLauncher<String> exportLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

	exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"),
            uri -> {
                if (uri != null) {
                    new Thread(() -> {
                            try {
                                lockedWriteZipToUri(uri);
				finish();
                            } catch (Throwable t) {
                                showError("exporting setting", t);
                            }
                    }).start();
                }
            }
        );
        exportLauncher.launch("hk-settings.zip");
    }

    private void showError(String action, Throwable t) {
        runOnUiThread(()-> {
                new AlertDialog.Builder(this)
                    .setTitle("Error while "+action)
                    .setMessage(t.toString())
                    .setPositiveButton(R.string.ok, (d, w) -> d.dismiss() )
                    .show();

            });
    }

    private void lockedWriteZipToUri(Uri uri) throws IOException {
        // commit any pending shared preferences write
        var prefs = PreferenceManager.getDefaultSharedPreferences(this);

        boolean saved = prefs.edit().commit();
        if (!saved) {
            throw new IOException("Could not flush SharedPreferences to disk");
        }
        // lock both databases to before write
        synchronized(AutoDictionary.dbLock) {
            synchronized(UserBigramDictionary.dbLock) {
                writeZipToUri(uri);
            }
        }
    }

    private void writeZipToUri(Uri uri) throws IOException {

        OutputStream output = getContentResolver().openOutputStream(uri);
        if (output == null) {
            throw new IOException("Could not open output stream");
        }

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            File dataDir = new File(getApplicationInfo().dataDir);

            addDirectoryToZip(dataDir, new File(dataDir, "shared_prefs"), zip);
            addDirectoryToZip(dataDir, new File(dataDir, "databases"), zip);
        }
    }

    private void addDirectoryToZip(File root,
                                   File directory,
                                   ZipOutputStream zip)
        throws IOException
    {
        File[] files = directory.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[8192];

        for (File file : files) {
            if (file.isDirectory()) {
                addDirectoryToZip(root, file, zip);
            } else {
                String entryName = root.toURI()
		    .relativize(file.toURI())
		    .getPath();

                zip.putNextEntry(new ZipEntry(entryName));

                try (FileInputStream input = new FileInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        zip.write(buffer, 0, count);
                    }
                }

                zip.closeEntry();
            }
        }
    }
}
