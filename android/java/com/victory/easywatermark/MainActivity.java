package com.victory.easywatermark;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 水印相机 —— 相机主界面 v1.3
 * 拍摄辅助（闪光/定时/网格/点击对焦）、罗盘方位角、防伪指纹、拍摄历史、批量补印入口
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback, LocationListener, SensorEventListener {

    private static final int REQ_PERMS = 1;
    private static final int REQ_LOGO = 2;
    private static final int MAX_SIDE = 3200;
    private static final String PREFS = "settings";
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LEN = 13;

    // ---- 相机 ----
    private Camera camera;
    private int facing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private int jpegRotation = 90;
    private boolean previewing = false;
    private boolean capturing = false;
    private SurfaceView surfaceView;
    private int flashMode = 0;            // 0关 1开 2自动
    private int timerSec = 0;             // 0 / 3 / 10
    private boolean counting = false;

    // ---- UI ----
    private TextView wmTime, wmDate, wmAddr, wmGeo, countdown, btnFlash, btnTimer, btnGrid;
    private com.victory.easywatermark.GridOverlay gridOverlay;

    // ---- 定位 / 传感器 ----
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Location lastFix;
    private String resolvedAddr;
    private float azimuth = -1f;          // -1 表示不可用

    // ---- 设置 ----
    private SharedPreferences prefs;
    private float fontScale = 1.0f;
    private float alpha = 1.0f;
    private boolean exifOn = true;
    private boolean dualSave = true;
    private int template = Watermark.TEMPLATE_MARK;
    private boolean showAddr = true, showGeo = true, showVerify = true;
    private String note = "";
    private String logoUri = null;

    // ---- 工具 ----
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final SimpleDateFormat fmtTimeBig = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private final SimpleDateFormat fmtDateLine = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private final SimpleDateFormat fmtWeek = new SimpleDateFormat("EEEE", Locale.CHINA);

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            wmTime.setText(fmtTimeBig.format(now));
            wmDate.setText(fmtWeek.format(now) + " / " + fmtDateLine.format(now));
            main.postDelayed(this, 1000);
        }
    };

    // ============================================================
    // 生命周期
    // ============================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadSettings();

        surfaceView = findViewById(R.id.surface);
        wmTime = findViewById(R.id.wm_time);
        wmDate = findViewById(R.id.wm_date);
        wmAddr = findViewById(R.id.wm_addr);
        wmGeo = findViewById(R.id.wm_geo);
        countdown = findViewById(R.id.countdown);
        btnFlash = findViewById(R.id.btn_flash);
        btnTimer = findViewById(R.id.btn_timer);
        btnGrid = findViewById(R.id.btn_grid);
        gridOverlay = findViewById(R.id.grid);

        surfaceView.getHolder().addCallback(this);
        surfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // 点击对焦
        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { tapFocus(e.getX(), e.getY()); }
                return false;
            }
        });

        findViewById(R.id.btn_shutter).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { takePhoto(); }
        });
        findViewById(R.id.btn_switch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchCamera(); }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        btnFlash.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleFlash(); }
        });
        btnTimer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTimer(); }
        });
        btnGrid.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleGrid(); }
        });
        findViewById(R.id.btn_history).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, HistoryActivity.class)); }
        });

        btnFlash.setText(new String[]{"⚡关", "⚡开", "⚡自动"}[flashMode]);
        btnTimer.setText(timerSec > 0 ? "⏱" + timerSec + "s" : "⏱关");
        gridOverlay.setVisibility(prefs.getBoolean("showGrid", false) ? View.VISIBLE : View.GONE);
        btnGrid.setText(prefs.getBoolean("showGrid", false) ? "▦开" : "▦网格");

        applySettingsToPreview();
        requestPermissions(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS) return;
        boolean camOk = false, locOk = false;
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.CAMERA.equals(perms[i]) && results[i] == PackageManager.PERMISSION_GRANTED) camOk = true;
            if ((Manifest.permission.ACCESS_FINE_LOCATION.equals(perms[i])
                    || Manifest.permission.ACCESS_COARSE_LOCATION.equals(perms[i]))
                    && results[i] == PackageManager.PERMISSION_GRANTED) locOk = true;
        }
        if (!camOk) {
            toast("未授予相机权限，应用无法使用");
            finish();
            return;
        }
        if (!locOk) toast("未授予定位权限，水印将不包含位置信息");
        if (!previewing && surfaceView.getHolder().getSurface() != null
                && surfaceView.getHolder().getSurface().isValid()) {
            openCamera();
        }
        if (locOk) startLocation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(clockTick);
        if (sensorManager == null) sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            Sensor rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rv != null) sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && !previewing && surfaceView.getHolder().getSurface() != null
                && surfaceView.getHolder().getSurface().isValid()) {
            openCamera();
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocation();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(clockTick);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        releaseCamera();
        stopLocation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ============================================================
    // 罗盘（旋转矢量传感器）
    // ============================================================
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] r = new float[9];
            SensorManager.getRotationMatrixFromVector(r, event.values);
            float[] o = new float[3];
            SensorManager.getOrientation(r, o);
            float deg = (float) Math.toDegrees(o[0]);
            azimuth = (deg % 360 + 360) % 360;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ============================================================
    // 拍摄辅助
    // ============================================================
    private void cycleFlash() {
        flashMode = (flashMode + 1) % 3;
        btnFlash.setText(new String[]{"⚡关", "⚡开", "⚡自动"}[flashMode]);
        applyFlash();
    }

    private void applyFlash() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            List<String> supported = p.getSupportedFlashModes();
            if (supported == null) return;
            String mode = flashMode == 1 ? Camera.Parameters.FLASH_MODE_ON
                    : flashMode == 2 ? Camera.Parameters.FLASH_MODE_AUTO
                    : Camera.Parameters.FLASH_MODE_OFF;
            if (supported.contains(mode)) {
                p.setFlashMode(mode);
                camera.setParameters(p);
            }
        } catch (Exception ignored) { }
    }

    private void cycleTimer() {
        timerSec = timerSec == 0 ? 3 : timerSec == 3 ? 10 : 0;
        btnTimer.setText(timerSec > 0 ? "⏱" + timerSec + "s" : "⏱关");
    }

    private void cycleGrid() {
        boolean on = !prefs.getBoolean("showGrid", false);
        prefs.edit().putBoolean("showGrid", on).apply();
        gridOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
        btnGrid.setText(on ? "▦开" : "▦网格");
    }

    private void tapFocus(float vx, float vy) {
        if (camera == null || !previewing) return;
        try {
            Camera.Parameters p = camera.getParameters();
            if (p.getMaxNumFocusAreas() <= 0) return;
            float fx = vx / surfaceView.getWidth();
            float fy = vy / surfaceView.getHeight();
            int cx = (int) clamp(fy * 2000 - 1000, -1000, 1000);
            int cy = (int) clamp((1 - fx) * 2000 - 1000, -1000, 1000);
            android.graphics.Rect rect = new android.graphics.Rect(cx - 100, cy - 100, cx + 100, cy + 100);
            List<Camera.Area> areas = new ArrayList<Camera.Area>();
            areas.add(new Camera.Area(rect, 800));
            p.setFocusAreas(areas);
            camera.setParameters(p);
            camera.autoFocus(null);
        } catch (Exception ignored) { }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ============================================================
    // 设置（滚动面板：11 项）
    // ============================================================
    private void loadSettings() {
        fontScale = prefs.getFloat("fontScale", 1.0f);
        alpha = prefs.getFloat("alpha", 1.0f);
        exifOn = prefs.getBoolean("exifOn", true);
        dualSave = prefs.getBoolean("dualSave", true);
        template = prefs.getInt("template", Watermark.TEMPLATE_MARK);
        showAddr = prefs.getBoolean("showAddr", true);
        showGeo = prefs.getBoolean("showGeo", true);
        showVerify = prefs.getBoolean("showVerify", true);
        note = prefs.getString("note", "");
        logoUri = prefs.getString("logoUri", null);
    }

    private void applySettingsToPreview() {
        wmTime.setTextSize(30 * (fontScale == 0.5f ? 0.75f : fontScale == 1.5f ? 1.25f : 1.0f));
        wmTime.setAlpha(alpha);
        wmDate.setAlpha(alpha);
        wmAddr.setAlpha(alpha);
        wmGeo.setAlpha(alpha);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final TextView[] rows = new TextView[11];
        for (int i = 0; i < 11; i++) {
            TextView tv = new TextView(this);
            tv.setPadding(0, pad / 2, 0, pad / 2);
            tv.setTextSize(15);
            final int idx = i;
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onSettingClick(idx, rows); }
            });
            rows[i] = tv;
            box.addView(tv);
        }
        refreshRows(rows);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(sv)
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { applySettingsToPreview(); }
                })
                .show();
    }

    private void onSettingClick(int idx, TextView[] rows) {
        switch (idx) {
            case 0:
                fontScale = fontScale == 0.5f ? 1.0f : fontScale == 1.0f ? 1.5f : 0.5f;
                prefs.edit().putFloat("fontScale", fontScale).apply();
                break;
            case 1:
                alpha = alpha >= 1.0f ? 0.85f : alpha >= 0.85f ? 0.7f : alpha >= 0.7f ? 0.5f : 1.0f;
                prefs.edit().putFloat("alpha", alpha).apply();
                break;
            case 2:
                exifOn = !exifOn;
                prefs.edit().putBoolean("exifOn", exifOn).apply();
                break;
            case 3:
                dualSave = !dualSave;
                prefs.edit().putBoolean("dualSave", dualSave).apply();
                break;
            case 4:
                template = (template + 1) % 3;
                prefs.edit().putInt("template", template).apply();
                break;
            case 5:
                showAddr = !showAddr;
                prefs.edit().putBoolean("showAddr", showAddr).apply();
                break;
            case 6:
                showGeo = !showGeo;
                prefs.edit().putBoolean("showGeo", showGeo).apply();
                break;
            case 7:
                showVerify = !showVerify;
                prefs.edit().putBoolean("showVerify", showVerify).apply();
                break;
            case 8: // 备注
                inputNote(rows);
                return;
            case 9: // Logo
                pickLogo(rows);
                return;
            case 10: // 恢复默认
                prefs.edit().putBoolean("showAddr", true).putBoolean("showGeo", true)
                        .putBoolean("showVerify", true).putString("note", "").putInt("template", 0).apply();
                loadSettings();
                break;
        }
        refreshRows(rows);
    }

    private void inputNote(final TextView[] rows) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("如：巡检记录 / 项目A（留空不显示）");
        input.setText(note);
        new AlertDialog.Builder(this)
                .setTitle("自定义备注")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        note = input.getText().toString().trim();
                        prefs.edit().putString("note", note).apply();
                        refreshRows(rows);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickLogo(final TextView[] rows) {
        new AlertDialog.Builder(this)
                .setTitle("自定义 Logo")
                .setItems(new String[]{"从相册选择 PNG", "清除 Logo"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            it.addCategory(Intent.CATEGORY_OPENABLE);
                            it.setType("image/*");
                            startActivityForResult(it, REQ_LOGO);
                        } else {
                            logoUri = null;
                            prefs.edit().remove("logoUri").apply();
                            refreshRows(rows);
                        }
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(data.getData(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            logoUri = data.getData().toString();
            prefs.edit().putString("logoUri", logoUri).apply();
            toast("Logo 已设置");
        }
    }

    private void refreshRows(TextView[] rows) {
        String[] tplNames = {"马克式（左下信息块）", "极简式（两行小字）", "通栏式（底部全宽条）"};
        rows[0].setText("水印字号：" + (fontScale == 0.5f ? "小号" : fontScale == 1.5f ? "大号" : "标准") + "（点击切换）");
        rows[1].setText("水印透明度：" + Math.round(alpha * 100) + "%（点击切换）");
        rows[2].setText("EXIF GPS 写入：" + (exifOn ? "开" : "关") + "（点击切换）");
        rows[3].setText("双版本保存：" + (dualSave ? "开（水印版+无水印原图）" : "关（仅水印版）") + "（点击切换）");
        rows[4].setText("水印模板：" + tplNames[template] + "（点击切换）");
        rows[5].setText("显示地址：" + (showAddr ? "开" : "关") + "（点击切换）");
        rows[6].setText("显示坐标/海拔/方位：" + (showGeo ? "开" : "关") + "（点击切换）");
        rows[7].setText("显示真实性验证行：" + (showVerify ? "开" : "关") + "（点击切换）");
        rows[8].setText("自定义备注：" + (note == null || note.isEmpty() ? "未设置" : note) + "（点击设置）");
        rows[9].setText("自定义 Logo：" + (logoUri != null ? "已设置" : "未设置") + "（点击设置）");
        rows[10].setText("恢复字段默认显示（点击）");
    }

    // ============================================================
    // 相机（Camera1 API，最大分辨率）
    // ============================================================
    private void openCamera() {
        releaseCamera();
        try {
            camera = Camera.open(facing);
            Camera.Parameters p = camera.getParameters();

            Camera.Size bestPic = largest(p.getSupportedPictureSizes());
            p.setPictureSize(bestPic.width, bestPic.height);
            p.setJpegQuality(98);
            Camera.Size bestPrev = closestAspect(p.getSupportedPreviewSizes(), bestPic, 1920);
            if (bestPrev != null) p.setPreviewSize(bestPrev.width, bestPrev.height);

            List<String> focusModes = p.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(p);
            applyFlash();

            Camera.CameraInfo ci = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraIdOf(facing), ci);
            jpegRotation = (ci.orientation - displayRotationDegrees() + 360) % 360;
            camera.setDisplayOrientation(previewOrientation());
            camera.setPreviewDisplay(surfaceView.getHolder());
            camera.startPreview();
            previewing = true;
        } catch (Exception e) {
            toast("相机启动失败：" + e.getMessage());
            releaseCamera();
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignored) { }
            try { camera.release(); } catch (Exception ignored) { }
            camera = null;
        }
        previewing = false;
        capturing = false;
    }

    private void switchCamera() {
        facing = (facing == Camera.CameraInfo.CAMERA_FACING_BACK)
                ? Camera.CameraInfo.CAMERA_FACING_FRONT
                : Camera.CameraInfo.CAMERA_FACING_BACK;
        openCamera();
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private void takePhoto() {
        if (camera == null || !previewing || capturing || counting) return;
        if (timerSec > 0) {
            counting = true;
            countdown.setVisibility(View.VISIBLE);
            countDown(timerSec);
            return;
        }
        doCapture();
    }

    private void countDown(final int sec) {
        if (sec <= 0) {
            countdown.setVisibility(View.GONE);
            counting = false;
            doCapture();
            return;
        }
        countdown.setText(String.valueOf(sec));
        main.postDelayed(new Runnable() {
            @Override public void run() { countDown(sec - 1); }
        }, 1000);
    }

    private void doCapture() {
        if (camera == null || !previewing || capturing) return;
        capturing = true;
        toast("正在拍摄…");
        camera.takePicture(null, null, new Camera.PictureCallback() {
            @Override public void onPictureTaken(final byte[] data, Camera cam) {
                io.execute(new Runnable() {
                    @Override public void run() { processCapture(data); }
                });
            }
        });
    }

    /** 后台线程：解码 → 旋转/镜像 → 限长边 → 合成水印 → 防伪指纹 → 历史 → 结果页 */
    private void processCapture(byte[] data) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            int sample = 1;
            while (Math.max(opts.outWidth, opts.outHeight) / sample > MAX_SIDE) sample *= 2;
            opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (src == null) throw new IllegalStateException("照片解码失败");

            Matrix m = new Matrix();
            m.postRotate(jpegRotation);
            if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) m.postScale(-1, 1);
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (rotated != src) src.recycle();

            Bitmap out = rotated;
            int w = rotated.getWidth(), h = rotated.getHeight();
            int longest = Math.max(w, h);
            if (longest > MAX_SIDE) {
                float k = MAX_SIDE / (float) longest;
                out = Bitmap.createScaledBitmap(rotated, Math.round(w * k), Math.round(h * k), true);
                if (out != rotated) rotated.recycle();
            }

            Watermark.Params p = new Watermark.Params();
            p.fontScale = fontScale;
            p.alpha = alpha;
            p.template = template;
            p.showAddr = showAddr;
            p.showGeo = showGeo;
            p.showVerify = showVerify;
            p.note = (note != null && !note.isEmpty()) ? note : null;
            p.addr = Watermark.cleanAddress(resolvedAddr);
            if (lastFix != null) {
                p.lat = lastFix.getLatitude();
                p.lon = lastFix.getLongitude();
                p.alt = lastFix.getAltitude();
            }
            if (azimuth >= 0) p.azimuth = (double) azimuth;
            if (logoUri != null) p.logo = loadLogo();

            Date when = new Date();
            String code = newCode();
            String fp = PhotoStore.fingerprint(code, when, p);
            Bitmap watermarked = Watermark.render(out, when, code, p);

            PendingPhoto.set(out, watermarked, when, code, fp, p);
            HistoryStore.add(this, watermarked, code, when.getTime(), p.addr);

            runOnUiThread(new Runnable() {
                @Override public void run() {
                    capturing = false;
                    startActivity(new Intent(MainActivity.this, ResultActivity.class));
                }
            });
        } catch (final Exception e) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    capturing = false;
                    toast("拍摄失败：" + e.getMessage());
                    openCamera();
                }
            });
        }
    }

    private Bitmap loadLogo() {
        try {
            InputStream is = getContentResolver().openInputStream(Uri.parse(logoUri));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, o);
            is.close();
            int s = 1;
            while (o.outWidth / s > 512) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            is = getContentResolver().openInputStream(Uri.parse(logoUri));
            Bitmap b = BitmapFactory.decodeStream(is, null, o);
            is.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // 定位 + 地址解析
    // ============================================================
    private void startLocation() {
        if (locationManager == null) locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) return;
        try {
            List<String> providers = locationManager.getProviders(true);
            for (String pro : providers) {
                if (LocationManager.GPS_PROVIDER.equals(pro)
                        || LocationManager.NETWORK_PROVIDER.equals(pro)
                        || LocationManager.PASSIVE_PROVIDER.equals(pro)) {
                    locationManager.requestLocationUpdates(pro, 3000L, 0f, this, Looper.getMainLooper());
                }
            }
        } catch (SecurityException ignored) { }
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch (SecurityException ignored) { }
    }

    private void stopLocation() {
        if (locationManager != null) locationManager.removeUpdates(this);
    }

    @Override
    public void onLocationChanged(Location loc) {
        lastFix = loc;
        boolean needAddr = resolvedAddr == null;
        updateLocUi();
        if (needAddr) {
            final double lat = loc.getLatitude(), lon = loc.getLongitude();
            io.execute(new Runnable() {
                @Override public void run() { resolveAddress(lat, lon); }
            });
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    private void resolveAddress(final double lat, final double lon) {
        String addr = null;
        try {
            if (Geocoder.isPresent()) {
                List<android.location.Address> list = new Geocoder(this, Locale.CHINA)
                        .getFromLocation(lat, lon, 1);
                if (list != null && !list.isEmpty()) {
                    android.location.Address a = list.get(0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(a.getAddressLine(i));
                    }
                    addr = sb.toString();
                }
            }
        } catch (Exception ignored) { }
        if (addr == null) addr = nominatimReverse(lat, lon);
        if (addr != null) resolvedAddr = addr;
        runOnUiThread(new Runnable() {
            @Override public void run() { updateLocUi(); }
        });
    }

    private String nominatimReverse(double lat, double lon) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=zh-CN&lat=" + lat + "&lon=" + lon);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "WatermarkCamera/1.3");
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            return o.optString("display_name", null);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void updateLocUi() {
        if (lastFix == null) {
            wmAddr.setText("📍 正在获取定位…");
            wmGeo.setText("");
        } else {
            StringBuilder geo = new StringBuilder("🌐 ")
                    .append(String.format(Locale.US, "%.5f, %.5f", lastFix.getLatitude(), lastFix.getLongitude()));
            double alt = lastFix.getAltitude();
            if (alt > 1.0) geo.append(" · 🏔️ ").append(String.format(Locale.US, "%.0fm", alt));
            if (azimuth >= 0) geo.append(" · 🧭 ").append(String.format(Locale.US, "%.0f°", azimuth)).append(Watermark.dirName((double) azimuth));
            wmGeo.setText(geo.toString());
            wmAddr.setText("📍 " + (resolvedAddr != null
                    ? Watermark.cleanAddress(resolvedAddr)
                    : "已定位（地址解析中）"));
        }
    }

    // ============================================================
    // 几何与尺寸工具
    // ============================================================
    private int displayRotationDegrees() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private int previewOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraIdOf(facing), info);
        int degrees = displayRotationDegrees();
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    private int cameraIdOf(int wantFacing) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int id = 0; id < Camera.getNumberOfCameras(); id++) {
            Camera.getCameraInfo(id, info);
            if (info.facing == wantFacing) return id;
        }
        return 0;
    }

    private Camera.Size largest(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        for (Camera.Size s : sizes) {
            if ((long) s.width * s.height > (long) best.width * best.height) best = s;
        }
        return best;
    }

    private Camera.Size closestAspect(List<Camera.Size> sizes, Camera.Size target, int maxW) {
        double targetRatio = target.width / (double) target.height;
        Camera.Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Camera.Size s : sizes) {
            if (s.width > maxW) continue;
            double ratio = s.width / (double) s.height;
            double score = Math.abs(ratio - targetRatio) * 10000 - s.width * s.height / 1e6;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best != null ? best : sizes.get(0);
    }

    // SurfaceHolder.Callback
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null && !previewing) {
            try {
                camera.setPreviewDisplay(holder);
                camera.startPreview();
                previewing = true;
            } catch (Exception e) { openCamera(); }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
