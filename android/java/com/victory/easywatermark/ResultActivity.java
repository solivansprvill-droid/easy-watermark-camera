package com.victory.easywatermark;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Locale;

/** 拍摄结果页：预览水印成片，可编辑原图或保存（水印版+原图双版本） */
public class ResultActivity extends Activity {

    private static final SimpleDateFormat FMT_FILE =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);

    private ImageView img;
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        img = findViewById(R.id.result_img);

        findViewById(R.id.btn_retake).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PendingPhoto.clear();
                finish();
            }
        });
        findViewById(R.id.btn_edit).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ResultActivity.this, EditorActivity.class));
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();   // 从编辑器返回后刷新成片
    }

    private void refresh() {
        if (PendingPhoto.watermarked == null) {
            finish();
            return;
        }
        img.setImageBitmap(PendingPhoto.watermarked);
    }

    private void save() {
        if (saving || PendingPhoto.watermarked == null) return;
        saving = true;
        final boolean dualSave = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("dualSave", true);
        final boolean exifOn = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("exifOn", true);
        final String base = "WATERMARK_" + FMT_FILE.format(PendingPhoto.date);
        final Watermark.Params p = PendingPhoto.params;

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Uri wmUri = PhotoStore.save(ResultActivity.this,
                            PendingPhoto.watermarked, "Watermarked", base + ".jpg");
                    Uri origUri = null;
                    if (dualSave) {
                        origUri = PhotoStore.save(ResultActivity.this,
                                PendingPhoto.original, "Original", base + "_original.jpg");
                    }
                    if (exifOn) {
                        PhotoStore.writeExifAll(ResultActivity.this, wmUri, p,
                                PendingPhoto.date, PendingPhoto.fingerprint);
                        if (origUri != null) {
                            PhotoStore.writeExifAll(ResultActivity.this, origUri, p,
                                    PendingPhoto.date, PendingPhoto.fingerprint);
                        }
                    }
                    final String msg = dualSave
                            ? "已保存水印版 + 无水印原图（相册/水印相机）"
                            : "已保存到相册「水印相机」";
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(ResultActivity.this, msg, Toast.LENGTH_SHORT).show();
                            PendingPhoto.clear();
                            finish();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            saving = false;
                            Toast.makeText(ResultActivity.this,
                                    "保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }
}
