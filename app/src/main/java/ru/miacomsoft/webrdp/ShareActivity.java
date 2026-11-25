package ru.miacomsoft.webrdp;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ShareActivity extends Activity {
    private static final String TAG = "ShareActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String action = intent.getAction();

        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                new Thread(() -> processUris(List.of(uri))).start();
            } else finish();
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) {
                new Thread(() -> processUris(uris)).start();
            } else finish();
        } else {
            finish();
        }
    }

    private void processUris(List<Uri> uris) {
        File cacheDir = new File(getCacheDir(), "shared_files");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        String finalName;
        if (uris.size() == 1) {
            Uri uri = uris.get(0);
            DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
            if (doc != null && doc.isDirectory()) {
                finalName = "directory_" + System.currentTimeMillis() + ".zip";
                File zipFile = new File(cacheDir, finalName);
                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                    zipDirectory(doc, "", zos);
                } catch (Exception e) { Log.e(TAG, "Zip dir error", e); }
            } else {
                finalName = getFileName(uri);
                File dest = new File(cacheDir, finalName);
                try (InputStream is = getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(dest)) {
                    if (is != null) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                } catch (Exception e) { Log.e(TAG, "Copy file error", e); }
            }
        } else {
            finalName = "archive_" + System.currentTimeMillis() + ".zip";
            File zipFile = new File(cacheDir, finalName);
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                for (Uri uri : uris) {
                    DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
                    if (doc != null) {
                        if (doc.isDirectory()) {
                            zipDirectory(doc, doc.getName() + "/", zos);
                        } else {
                            String name = getFileName(uri);
                            zos.putNextEntry(new ZipEntry(name));
                            try (InputStream is = getContentResolver().openInputStream(uri)) {
                                if (is != null) {
                                    byte[] buf = new byte[8192]; int n;
                                    while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                                }
                            }
                            zos.closeEntry();
                        }
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Zip error", e); }
        }

        Intent svc = new Intent(this, ScreenStreamService.class);
        svc.setAction(ScreenStreamService.ACTION_SHARE_FILE);
        svc.putExtra("filename", finalName);
        startService(svc);

        runOnUiThread(this::finish);
    }

    private void zipDirectory(DocumentFile dir, String basePath, ZipOutputStream zos) throws Exception {
        for (DocumentFile file : dir.listFiles()) {
            if (file.isDirectory()) {
                zipDirectory(file, basePath + file.getName() + "/", zos);
            } else {
                zos.putNextEntry(new ZipEntry(basePath + file.getName()));
                try (InputStream is = getContentResolver().openInputStream(file.getUri())) {
                    if (is != null) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result != null ? result : "file_" + System.currentTimeMillis();
    }
}