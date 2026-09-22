package com.victory.easywatermark;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 好用水印相机 —— 参考 lxsfful/simple-watermark-camera 功能集重制
 *
 * - 硬件最大分辨率拍摄（选最大画幅）
 * - 马克排版水印：大字时间 + 星期/日期 + 紫色 Tag + 📍🌐☀️ 字段图标行
 * - 短边自适应比例（横竖屏都不截断）
 * - 三档字号 / 四档透明度（设置面板，SharedPreferences 持久化）
 * - Open-Meteo 天气（免密钥，30 分钟 TTL 缓存）
 * - 详细地址（清洗"中国"前缀）
 * - EXIF GPS 元数据写入（可选）
 * - 双版本保存：水印版 + 无水印原图（可选）
 * - 纯 android.jar 实现，零第三方依赖
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback, LocationListener {

    private static final int REQ_PERMS = 1;
    private static final int MAX_SIDE = 4096;              // 解码长边上限（对齐参考仓库 4096）
    private static final String BASE_DIR = "好用水印相机";   // 相册根目录
    private static final String TAG_TEXT = "好用水印相机";
    private static final String PREFS = "settings";
    private static final long WEATHER_TTL = 30 * 60 * 1000L; // 天气缓存 30 分钟

    // ---- 相机 ----
    private Camera camera;
    private int facing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private int jpegRotation = 90;
    private boolean previewing = false;
    private boolean capturing = false;
    private SurfaceView surfaceView;
    private Date captureDate;

    // ---- UI ----
    private TextView wmTime, wmDate, wmAddr, wmGeo, wmWeather, wmTag;

    // ---- 定位 / 天气 ----
    private LocationManager locationManager;
    private Location lastFix;
    private String resolvedAddr;
    private String weatherText;          // "☀️ 晴 25°C"
    private long weatherFetchedAt = 0L;
    private double weatherLat, weatherLon;

    // ---- 设置（SharedPreferences） ----
    private SharedPreferences prefs;
    private float fontScale = 1.0f;      // 0.5 / 1.0 / 1.5
    private float alpha = 1.0f;          // 1.0 / 0.85 / 0.7 / 0.5
    private boolean weatherOn = true;
    private boolean exifOn = true;
    private boolean dualSave = true;

    // ---- 工具 ----
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat fmtTimeBig = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private final SimpleDateFormat fmtDateLine = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private final SimpleDateFormat fmtWeek = new SimpleDateFormat("EEEE", Locale.CHINA);
    private final SimpleDateFormat fmtFile = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);

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
        wmTag = findViewById(R.id.wm_tag);
        wmAddr = findViewById(R.id.wm_addr);
        wmGeo = findViewById(R.id.wm_geo);
        wmWeather = findViewById(R.id.wm_weather);

        surfaceView.getHolder().addCallback(this);
        surfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        findViewById(R.id.btn_shutter).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { takePhoto(); }
        });
        findViewById(R.id.btn_switch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchCamera(); }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });

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
        releaseCamera();
        stopLocation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ============================================================
    // 设置
    // ============================================================
    private void loadSettings() {
        fontScale = prefs.getFloat("fontScale", 1.0f);
        alpha = prefs.getFloat("alpha", 1.0f);
        weatherOn = prefs.getBoolean("weatherOn", true);
        exifOn = prefs.getBoolean("exifOn", true);
        dualSave = prefs.getBoolean("dualSave", true);
    }

    private void applySettingsToPreview() {
        wmTime.setTextSize(30 * (fontScale == 0.5f ? 0.75f : fontScale == 1.5f ? 1.25f : 1.0f));
        wmTime.setAlpha(alpha);
        wmDate.setAlpha(alpha);
        wmAddr.setAlpha(alpha);
        wmGeo.setAlpha(alpha);
        wmWeather.setAlpha(alpha);
        wmTag.setAlpha(alpha);
        wmWeather.setVisibility(weatherOn ? View.VISIBLE : View.GONE);
    }

    /** 程序化设置面板：每行点击循环切换档位 */
    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final TextView[] rows = new TextView[5];
        String[] titles = {"水印字号", "水印透明度", "天气信息", "EXIF GPS 写入", "双版本保存（水印+原图）"};
        for (int i = 0; i < 5; i++) {
            TextView tv = new TextView(this);
            tv.setPadding(0, pad / 2, 0, pad / 2);
            tv.setTextSize(15);
            final int idx = i;
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cycleSetting(idx); refreshRows(rows); }
            });
            rows[i] = tv;
            box.addView(tv);
        }
        refreshRows(rows);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        applySettingsToPreview();
                        refreshWeatherIfEnabled();
                    }
                })
                .show();
    }

    private void refreshRows(TextView[] rows) {
        rows[0].setText("水印字号：" + (fontScale == 0.5f ? "小号" : fontScale == 1.5f ? "大号" : "标准") + "（点击切换）");
        rows[1].setText("水印透明度：" + Math.round(alpha * 100) + "%（点击切换）");
        rows[2].setText("天气信息：" + (weatherOn ? "开" : "关") + "（点击切换）");
        rows[3].setText("EXIF GPS 写入：" + (exifOn ? "开" : "关") + "（点击切换）");
        rows[4].setText("双版本保存：" + (dualSave ? "开（水印版+无水印原图）" : "关（仅水印版）") + "（点击切换）");
    }

    private void cycleSetting(int idx) {
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
                weatherOn = !weatherOn;
                prefs.edit().putBoolean("weatherOn", weatherOn).apply();
                break;
            case 3:
                exifOn = !exifOn;
                prefs.edit().putBoolean("exifOn", exifOn).apply();
                break;
            case 4:
                dualSave = !dualSave;
                prefs.edit().putBoolean("dualSave", dualSave).apply();
                break;
        }
    }

    private void refreshWeatherIfEnabled() {
        if (weatherOn && lastFix != null
                && System.currentTimeMillis() - weatherFetchedAt > WEATHER_TTL) {
            fetchWeather(lastFix.getLatitude(), lastFix.getLongitude());
        }
    }

    // ============================================================
    // 相机（Camera1 API，最大分辨率）
    // ============================================================
    private void openCamera() {
        releaseCamera();
        try {
            camera = Camera.open(facing);
            Camera.Parameters p = camera.getParameters();

            // 硬件最大分辨率（对齐参考仓库 MAXIMIZE_QUALITY）
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

    private void takePhoto() {
        if (camera == null || !previewing || capturing) return;
        capturing = true;
        captureDate = new Date();
        toast("正在拍摄…");
        camera.takePicture(null, null, new Camera.PictureCallback() {
            @Override public void onPictureTaken(final byte[] data, Camera cam) {
                io.execute(new Runnable() {
                    @Override public void run() { processAndSave(data); }
                });
            }
        });
    }

    /** 后台线程：解码 → 旋转/镜像 → 限长边 → 水印合成 → 双版本保存 + EXIF → 恢复预览 */
    private void processAndSave(byte[] data) {
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

            Date when = captureDate != null ? captureDate : new Date();
            Bitmap watermarked = out.copy(out.getConfig(), true);
            drawWatermark(watermarked, when);

            String base = "WATERMARK_" + fmtFile.format(when);
            Uri wmUri = saveToGallery(watermarked, "Watermarked", base + ".jpg");
            Uri origUri = null;
            if (dualSave) {
                origUri = saveToGallery(out, "Original", base + "_original.jpg");
            }

            if (exifOn && lastFix != null) {
                writeExifGps(wmUri);
                if (origUri != null) writeExifGps(origUri);
            }

            final String msg = dualSave
                    ? "已保存水印版 + 无水印原图（相册/" + BASE_DIR + "）"
                    : "已保存到相册「" + BASE_DIR + "」";
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    toast(msg);
                    restartPreview();
                }
            });
        } catch (final Exception e) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    capturing = false;
                    toast("保存失败：" + e.getMessage());
                    restartPreview();
                }
            });
        }
    }

    private void restartPreview() {
        if (camera != null && surfaceView.getHolder().getSurface().isValid()) {
            try {
                camera.setPreviewDisplay(surfaceView.getHolder());
                camera.startPreview();
                previewing = true;
            } catch (Exception e) { openCamera(); }
        } else {
            openCamera();
        }
    }

    // ============================================================
    // 马克风格水印（短边自适应，参考 WatermarkRenderer）
    // ============================================================
    private void drawWatermark(Bitmap bmp, Date when) {
        Canvas c = new Canvas(bmp);
        // 短边比例基准：无论横竖屏，水印占比一致、不截断
        float base = Math.min(bmp.getWidth(), bmp.getHeight()) / 1080f;
        float k = base * fontScale;

        String timeText = fmtTimeBig.format(when);
        String dateText = fmtWeek.format(when) + " / " + fmtDateLine.format(when);
        String addrText = resolvedAddr != null ? resolvedAddr : "📍 定位信息未获取";
        String geoText = null;
        if (lastFix != null) {
            StringBuilder sb = new StringBuilder("🌐 ")
                    .append(String.format(Locale.US, "%.5f, %.5f", lastFix.getLatitude(), lastFix.getLongitude()));
            double alt = lastFix.getAltitude();           // 0.0m 无效海拔过滤
            if (alt > 1.0) sb.append(" · 🏔️ ").append(String.format(Locale.US, "%.0fm", alt));
            geoText = sb.toString();
        } else {
            geoText = "🌐 GPS 未启用";
        }
        String wText = weatherOn ? weatherText : null;

        // 地址清洗：去掉"中国"前缀
        addrText = cleanAddress(addrText);

        Paint timeP = text(64 * k, true);
        Paint dateP = text(30 * k, false);
        Paint tagP = text(26 * k, false);
        Paint fieldP = text(28 * k, false);

        // 字段行超长截断
        float maxW = bmp.getWidth() * 0.92f - 48 * k;
        addrText = ellipsize(fieldP, addrText, maxW);
        geoText = ellipsize(fieldP, geoText, maxW);

        float marginX = 36 * k;
        float lineGap = 14 * k;
        float hTime = 64 * k, hDate = 30 * k, hTag = 34 * k, hField = 28 * k;
        boolean hasTag = tagP.measureText(TAG_TEXT) > 0;

        float totalH = hTime + hDate + (hasTag ? hTag + lineGap : 0)
                + hField + (geoText != null ? hField + lineGap * 0.6f : 0)
                + (wText != null ? hField : 0);

        float x = marginX;
        float yBottom = bmp.getHeight() - marginX;         // 自底向上排版
        float y = yBottom - totalH;
        if (y < marginX) y = marginX;                       // 极端情况防溢出

        int A = Math.round(255 * alpha);
        int textWhite = (A << 24) | 0xFFFFFF;
        int textDim = (Math.round(255 * alpha * 0.82f) << 24) | 0xFFFFFF;
        int tagBg = (A << 24) | 0x7C3AED;
        int tagFg = (A << 24) | 0xFFFFFF;

        // 大字时间 + 星期/日期
        timeP.setColor(textWhite);
        shadow(timeP);
        c.drawText(timeText, x, y + hTime, timeP);
        y += hTime + lineGap * 0.4f;
        dateP.setColor(textDim);
        shadow(dateP);
        c.drawText(dateText, x, y + hDate, dateP);
        y += hDate + lineGap;

        // 紫色 Tag
        if (hasTag) {
            float tagW = tagP.measureText(TAG_TEXT) + 24 * k;
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(tagBg);
            c.drawRoundRect(new RectF(x, y, x + tagW, y + hTag), 8 * k, 8 * k, bg);
            tagP.setColor(tagFg);
            c.drawText(TAG_TEXT, x + 12 * k, y + hTag - 7 * k, tagP);
            y += hTag + lineGap;
        }

        // 📍 地址
        fieldP.setColor(textWhite);
        shadow(fieldP);
        c.drawText(addrText, x, y + hField, fieldP);
        y += hField + lineGap * 0.6f;

        // 🌐 经纬度/海拔
        if (geoText != null) {
            fieldP.setColor(textDim);
            c.drawText(geoText, x, y + hField, fieldP);
            y += hField + lineGap * 0.6f;
        }

        // ☀️ 天气
        if (wText != null) {
            fieldP.setColor(textDim);
            c.drawText(wText, x, y + hField, fieldP);
        }
    }

    private void shadow(Paint p) {
        p.setShadowLayer(6, 0, 2, (Math.round(255 * alpha * 0.6f) << 24));
    }

    private String ellipsize(Paint p, String s, float maxW) {
        if (s == null) return null;
        while (p.measureText(s) > maxW && s.length() > 4) s = s.substring(0, s.length() - 2) + "…";
        return s;
    }

    /** 地址清洗：去"中国"前缀等 */
    private String cleanAddress(String addr) {
        if (addr == null) return addr;
        if (addr.startsWith("📍 ")) addr = "📍 " + addr.substring(3).replaceFirst("^中国", "").replaceFirst("^[,，、\\s]+", "");
        return addr;
    }

    private Paint text(float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(size);
        p.setFakeBoldText(bold);
        p.setTypeface(Typeface.create(bold ? "sans-serif-black" : "sans-serif-light",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    // ============================================================
    // 保存（MediaStore）+ EXIF GPS
    // ============================================================
    private Uri saveToGallery(Bitmap bmp, String subDir, String name) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + BASE_DIR + "/" + subDir);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("无法创建相册文件");
        java.io.OutputStream os = getContentResolver().openOutputStream(uri);
        if (os == null) throw new IllegalStateException("无法打开输出流");
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
        os.flush();
        os.close();

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        return uri;
    }

    /** EXIF GPS 元数据写入（可选，失败静默忽略）。框架版 ExifInterface 无 setLatLong，用 setAttribute + DMS 格式 */
    private void writeExifGps(Uri uri) {
        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(uri);
            ExifInterface exif = new ExifInterface(is);
            double lat = lastFix.getLatitude(), lon = lastFix.getLongitude();
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(lat));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, lat >= 0 ? "N" : "S");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(lon));
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lon >= 0 ? "E" : "W");
            double alt = lastFix.getAltitude();
            if (alt > 1.0) {
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, Math.round(alt * 100) + "/100");
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, alt >= 0 ? "0" : "1");
            }
            exif.saveAttributes();
        } catch (Exception ignored) {
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) { }
        }
    }

    /** 十进制度 → 度/分/秒（Rational 格式） */
    private String dms(double v) {
        v = Math.abs(v);
        int d = (int) v;
        double mFloat = (v - d) * 60;
        int m = (int) mFloat;
        double s = (mFloat - m) * 60;
        return d + "/1," + m + "/1," + Math.round(s * 1000) + "/1000";
    }

    // ============================================================
    // 定位 + 地址解析 + 天气
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
        if (weatherOn
                && (weatherText == null
                    || System.currentTimeMillis() - weatherFetchedAt > WEATHER_TTL
                    || Math.abs(weatherLat - loc.getLatitude()) > 0.02
                    || Math.abs(weatherLon - loc.getLongitude()) > 0.02)) {
            fetchWeather(loc.getLatitude(), loc.getLongitude());
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
            conn.setRequestProperty("User-Agent", "EasyWatermarkCamera/1.0");
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

    private void fetchWeather(final double lat, final double lon) {
        weatherLat = lat;
        weatherLon = lon;
        weatherFetchedAt = System.currentTimeMillis();
        io.execute(new Runnable() {
            @Override public void run() {
                String result = null;
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code&timezone=Asia%2FShanghai");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                    double temp = cur.getDouble("temperature_2m");
                    int code = cur.getInt("weather_code");
                    result = "☀️ " + weatherDesc(code) + " " + String.format(Locale.US, "%.0f°C", temp);
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final String f = result;
                if (f != null) {
                    weatherText = f;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { updateLocUi(); }
                    });
                }
            }
        });
    }

    private String weatherDesc(int code) {
        if (code == 0) return "晴";
        if (code <= 3) return "多云";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code == 85 || code == 86) return "阵雪";
        if (code >= 95) return "雷阵雨";
        return "天气";
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
            wmGeo.setText(geo.toString());
            wmAddr.setText("📍 " + (resolvedAddr != null
                    ? resolvedAddr.replaceFirst("^中国", "").replaceFirst("^[,，、\\s]+", "")
                    : "已定位（地址解析中）"));
        }
        wmWeather.setText(weatherOn ? (weatherText != null ? weatherText : "☀️ 天气获取中…") : "");
        wmWeather.setVisibility(weatherOn ? View.VISIBLE : View.GONE);
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
