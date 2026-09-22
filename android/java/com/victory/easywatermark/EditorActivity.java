package com.victory.easywatermark;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** 图片编辑器：裁剪 / 滤镜 / 加字（作用于无水印原图，确认后重合成水印） */
public class EditorActivity extends Activity {

    private EditorView editor;
    private TextView[] aspectChips = new TextView[4];
    private TextView[] filterChips = new TextView[6];
    private int aspectSel = 0;   // 0 自由 1 1:1 2 4:3 3 16:9
    private float[] aspects = {0f, 1f, 4f / 3f, 16f / 9f};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        editor = findViewById(R.id.editor);
        if (PendingPhoto.original == null) {
            finish();
            return;
        }
        editor.setBitmap(PendingPhoto.original);

        // 模式切换
        findViewById(R.id.mode_crop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(EditorView.Mode.CROP); }
        });
        findViewById(R.id.mode_filter).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(EditorView.Mode.FILTER); }
        });
        findViewById(R.id.mode_text).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(EditorView.Mode.TEXT); }
        });
        findViewById(R.id.mode_mosaic).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(EditorView.Mode.MOSAIC); }
        });

        // 顶栏
        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                editor.resetAll();
                aspectSel = 0;
                refreshChips();
                showBars(EditorView.Mode.CROP);
                editor.setMode(EditorView.Mode.CROP);
            }
        });
        findViewById(R.id.btn_done).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyAndFinish(); }
        });

        // 裁剪比例
        aspectChips[0] = findViewById(R.id.aspect_free);
        aspectChips[1] = findViewById(R.id.aspect_1_1);
        aspectChips[2] = findViewById(R.id.aspect_4_3);
        aspectChips[3] = findViewById(R.id.aspect_16_9);
        String[] names = {"自由", "1:1", "4:3", "16:9"};
        for (int i = 0; i < 4; i++) {
            aspectChips[i].setText(names[i]);
            final int idx = i;
            aspectChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    aspectSel = idx;
                    editor.setAspect(aspects[idx]);
                    refreshChips();
                }
            });
        }

        // 滤镜
        filterChips[0] = findViewById(R.id.filter_0);
        filterChips[1] = findViewById(R.id.filter_1);
        filterChips[2] = findViewById(R.id.filter_2);
        filterChips[3] = findViewById(R.id.filter_3);
        filterChips[4] = findViewById(R.id.filter_4);
        filterChips[5] = findViewById(R.id.filter_5);
        String[] fNames = EditorView.filterNames();
        for (int i = 0; i < 6; i++) {
            filterChips[i].setText(fNames[i]);
            final int idx = i;
            filterChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    editor.setFilterIndex(idx);
                    refreshChips();
                }
            });
        }

        // 加字
        findViewById(R.id.btn_input_text).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { inputText(); }
        });

        // 遮挡：马赛克 / 画笔 / 撤销 / 清空
        findViewById(R.id.pen_mosaic).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editor.setPenMosaic(true); refreshChips(); }
        });
        findViewById(R.id.pen_draw).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editor.setPenMosaic(false); refreshChips(); }
        });
        findViewById(R.id.btn_undo).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editor.undoStroke(); }
        });
        findViewById(R.id.btn_clear_strokes).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editor.clearStrokes(); }
        });

        setMode(EditorView.Mode.CROP);
        refreshChips();
    }

    private void setMode(EditorView.Mode m) {
        editor.setMode(m);
        showBars(m);
        refreshChips();
    }

    private void showBars(EditorView.Mode m) {
        findViewById(R.id.aspect_bar).setVisibility(m == EditorView.Mode.CROP ? View.VISIBLE : View.GONE);
        findViewById(R.id.filter_bar).setVisibility(m == EditorView.Mode.FILTER ? View.VISIBLE : View.GONE);
        findViewById(R.id.text_bar).setVisibility(m == EditorView.Mode.TEXT ? View.VISIBLE : View.GONE);
        findViewById(R.id.mosaic_bar).setVisibility(m == EditorView.Mode.MOSAIC ? View.VISIBLE : View.GONE);
    }

    private void refreshChips() {
        int sel = 0xFF7C3AED;
        for (int i = 0; i < 4; i++) {
            aspectChips[i].getBackground().mutate().clearColorFilter();
            aspectChips[i].invalidate();
        }
        aspectChips[aspectSel].getBackground().mutate().setColorFilter(sel, android.graphics.PorterDuff.Mode.SRC_IN);
        aspectChips[aspectSel].invalidate();
        for (int i = 0; i < 6; i++) {
            filterChips[i].getBackground().mutate().clearColorFilter();
            filterChips[i].invalidate();
        }
        if (editor.getMode() == EditorView.Mode.FILTER) {
            filterChips[editor.getFilterIndex()].getBackground().mutate()
                    .setColorFilter(sel, android.graphics.PorterDuff.Mode.SRC_IN);
            filterChips[editor.getFilterIndex()].invalidate();
        }
        ((TextView) findViewById(R.id.pen_mosaic)).setTextColor(
                editor.getMode() == EditorView.Mode.MOSAIC && editor.isPenMosaic() ? sel : 0xFFFFFFFF);
        ((TextView) findViewById(R.id.pen_draw)).setTextColor(
                editor.getMode() == EditorView.Mode.MOSAIC && !editor.isPenMosaic() ? sel : 0xFFFFFFFF);
    }

    private void inputText() {
        final EditText input = new EditText(this);
        input.setHint("输入要叠加的文字");
        input.setText(editor.getText());
        new AlertDialog.Builder(this)
                .setTitle("加字")
                .setView(input)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        editor.setText(input.getText().toString());
                        if (editor.getText() != null) toast("拖动照片上的文字可调整位置");
                    }
                })
                .setNegativeButton("清除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        editor.setText(null);
                    }
                })
                .show();
    }

    private void applyAndFinish() {
        io(new Runnable() {
            @Override public void run() {
                Bitmap edited = editor.apply();
                if (edited == null) edited = PendingPhoto.original;
                PendingPhoto.original = edited;
                PendingPhoto.watermarked = Watermark.render(
                        edited, PendingPhoto.date, PendingPhoto.code, PendingPhoto.params);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast("已应用编辑");
                        finish();
                    }
                });
            }
        });
    }

    private void io(final Runnable r) {
        new Thread(r).start();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
