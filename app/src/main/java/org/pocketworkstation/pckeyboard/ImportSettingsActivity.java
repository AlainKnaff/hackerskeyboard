package org.pocketworkstation.pckeyboard;

import lu.knaff.alain.son_of_hacker.R;

import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.ComponentActivity;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipInputStream;

public class ImportSettingsActivity extends ComponentActivity {
    private final static String TAG = "Importer";
    private ActivityResultLauncher<String[]> importLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
	super.onCreate(savedInstanceState);
	importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    new Thread(() -> {
                            try {
                                lockedReadZipFromUri(uri);
                            } catch (Throwable t) {
                                Log.e(TAG, "could not read", t);
                                showError("importing setting", t);
                            }
                    }).start();
                } else
		    finish();
            }
        );
        importLauncher.launch(new String[]{"application/zip"});
    }

    private void showError(String action, Throwable t) {
        runOnUiThread(()-> {
                new AlertDialog.Builder(this)
                    .setTitle("Error while "+action)
                    .setMessage(t.toString())
                    .setPositiveButton(R.string.ok, (d, w) -> {
			    d.dismiss();
			    finish();
		    })
                    .show();

            });
    }

    private void lockedReadZipFromUri(Uri uri) throws IOException
    {
        // commit any pending shared preferences write, in order to
        // prevent our restored files to be overwritten immediately
        var prefs = PreferenceManager.getDefaultSharedPreferences(this);

        boolean saved = prefs.edit().commit();
        if (!saved) {
            throw new IOException("Could not flush SharedPreferences to disk");
        }

        // similarly, make sure that there aren't any db writes in the
        // pipeline which might clobber our freshly imported data
        synchronized(AutoDictionary.dbLock) {
            synchronized(UserBigramDictionary.dbLock) {
                readZipFromUri(uri);

                // restart the app to force re-read from disk
                Runtime.getRuntime().exit(0);
            }
        }
    }

    private void readZipFromUri(Uri uri) throws IOException {
        Log.i(TAG, "readZipFromUri");
        File dataDir = new File(getApplicationInfo().dataDir);
        String rootPath = dataDir.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[8192];

        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("Could not open the selected ZIP");
        }

        try (ZipInputStream zip = new ZipInputStream(input)) {
            Log.i(TAG, "in try");
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                Log.i(TAG, "Considering whether to restore "+name);
                // Only restore the folders included in this backup.
                if (!(name.startsWith("shared_prefs/")
                      || name.startsWith("databases/")
                      || name.startsWith("files/"))) {
                    Log.i(TAG, "Skipping");
                    zip.closeEntry();
                    continue;
                }

		name=name.replace("org.pocketworkstation.pckeyboard",
				  "lu.knaff.alain.son_of_hacker");

                File target = new File(dataDir, name);
                String targetPath = target.getCanonicalPath();

                // Reject paths that escape the app's data directory.
                if (!targetPath.startsWith(rootPath)) {
                    throw new IOException("Invalid ZIP entry: " + name);
                }

                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) {
                        throw new IOException("Could not create directory: " + target);
                    }
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Could not create directory: " + parent);
                    }

                    try (FileOutputStream output = new FileOutputStream(target)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                    }
                }

                zip.closeEntry();
            }
        }
    }

    private void restartApp() {
    }
}
