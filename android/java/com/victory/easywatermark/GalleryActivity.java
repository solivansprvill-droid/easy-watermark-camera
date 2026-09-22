package com.victory.easywatermark;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相册（v1.5）：两种范围
 *  - scope=app：本 App 保存的无水印原图（Pictures/水印相机/Original），点选可编辑并重新合成水印
 *  - scope=all：设备全部照片，点选可打水印并进入编辑
 */
public class GalleryActivity extends Activity {

    public static final String EXTRA_SCOPE = "scope";
    public static final String SCOPE_APP = "app";
    public static final String SCOPE_ALL = "all";

    private static final int MAX_LOAD = 300;
    private static final int MAX_SIDE = 3200;

    private final List<Uri> items = new ArrayList<Uri>();
    /** 缩略图缓存：按条目数上限 64（320px ≈ 26MB），防止大相册 OOM（v1.7 修复） */
    private final android.util.LruCache<Uri, Bitmap> thumbs = new android.util.LruCache<Uri, Bitmap>(64) {
        @Override protected int sizeOf(Uri key, Bitmap b) { return 1; }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    private boolean appScope;
    private ImageAdapter adapter;
    private TextView emptyHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        appScope = SCOPE_APP.equals(getIntent().getStringExtra(EXTRA_SCOPE));

        ((TextView) findViewById(R.id.gallery_title)).setText(appScope ? "相册 · 原图" : "全部照片");
        emptyHint = (TextView) findViewById(R.id.gallery_empty);
        emptyHint.setText(appScope ? "还没有本 App 拍摄的原图\n拍一张或从「全部照片」导入" : "设备里没有照片");
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        adapter = new ImageAdapter();
        GridView grid = (GridView) findViewById(R.id.gallery_grid);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                onPick(items.get(position));
            }
        });

        loadGrid();
    }

    // ---------- 网格加载 ----------

    private void loadGrid() {
        items.clear();
        thumbs.evictAll();
        adapter.notifyDataSetChanged();
        emptyHint.setVisibility(View.GONE);
        pool.execute(new Runnable() {
            @Override public void run() {
                List<Uri> found = new ArrayList<Uri>();
                try {
                    String sel = appScope
                            ? MediaStore.Images.ImageColumns.RELATIVE_PATH + " LIKE ?"
                            : null;
                    String[] args = appScope
                            ? new String[]{"Pictures/水印相机/Original%"}
                            : null;
                    android.database.Cursor c = getContentResolver().query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            new String[]{MediaStore.Images.Media._ID},
                            sel, args,
                            MediaStore.Images.Media.DATE_MODIFIED + " DESC");
                    if (c != null) {
                        while (c.moveToNext() && found.size() < MAX_LOAD) {
                            long id = c.getLong(0);
                            found.add(ContentUris.withAppendedId(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
                        }
                        c.close();
                    }
                } catch (Exception ignored) { }
                items.addAll(found);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        adapter.notifyDataSetChanged();
                        emptyHint.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private Bitmap thumb(final Uri u) {
        Bitmap b = thumbs.get(u);
        if (b != null) return b;
        try {
            b = getContentResolver().loadThumbnail(u, new android.util.Size(320, 320), null);
            thumbs.put(u, b);
        } catch (Exception ignored) { }
        return b;
    }

    private class ImageAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            ImageView iv = (ImageView) convertView;
            if (iv == null) {
                iv = new ImageView(GalleryActivity.this);
                int d = (int) (2 * getResources().getDisplayMetrics().density);
                iv.setPadding(d, d, d, d);
                iv.setBackgroundColor(0xFF171021);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 320));
                iv.setTag(null);
            }
            final Uri u = items.get(position);
            final ImageView fiv = iv;
            Bitmap t = thumbs.get(u);
            if (t != null) {
                fiv.setImageBitmap(t);
            } else {
                fiv.setImageDrawable(null);
                final ImageView target = fiv;
                pool.execute(new Runnable() {
                    @Override public void run() {
                        final Bitmap b = thumb(u);
                        if (b == null) return;
                        runOnUiThread(new Runnable() {
                            @Override public void run() { target.setImageBitmap(b); }
                        });
                    }
                });
            }
            return iv;
        }
    }

    // ---------- 点选操作 ----------

    private void onPick(final Uri u) {
        String[] opts = appScope
                ? new String[]{"✏️ 编辑（裁剪/滤镜/加字后重打水印）", "📤 分享", "🗑 删除"}
                : new String[]{"🎨 打水印并编辑", "📤 分享原照片"};
        new AlertDialog.Builder(this)
                .setTitle(appScope ? "相册原图" : "设备照片")
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 0) editFlow(u);
                        else if (appScope && w == 1) share(u);
                        else if (!appScope && w == 1) share(u);
                        else if (appScope && w == 2) confirmDelete(u);
                    }
                })
                .show();
    }

    private void share(Uri u) {
        try {
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("image/jpeg");
            it.putExtra(Intent.EXTRA_STREAM, u);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享照片"));
        } catch (Exception e) {
            toast("分享失败");
        }
    }

    private void confirmDelete(final Uri u) {
        new AlertDialog.Builder(this)
                .setTitle("删除照片")
                .setMessage("将从相册中删除这张原图（不可恢复），确定？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            getContentResolver().delete(u, null, null);
                            toast("已删除");
                            loadGrid();
                        } catch (Exception e) {
                            toast("删除失败");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 编辑链路：载入 → EXIF → 水印 → ResultActivity（可进编辑器） ----------

    private void editFlow(final Uri u) {
        toast("正在载入照片…");
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    Bitmap src = decode(u);
                    if (src == null) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { toast("照片载入失败"); }
                        });
                        return;
                    }
                    Watermark.Params p = buildParams();
                    Date when = new Date();
                    final String[] descHold = new String[1];
                    InputStream is = null;
                    try {
                        is = getContentResolver().openInputStream(u);
                        ExifInterface exif = new ExifInterface(is);
                        String dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                        if (dt != null && dt.length() >= 19) {
                            SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                            Date d = f.parse(dt);
                            if (d != null) when = d;
                        }
                        // v1.7：读取拍摄时写入的地址（ImageDescription）
                        String desc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
                        if (desc != null && !desc.trim().isEmpty()) descHold[0] = desc.trim();
                        Float lat = parseGps(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE),
                                exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF));
                        Float lon = parseGps(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE),
                                exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF));
                        if (lat != null && lon != null) {
                            p.lat = lat.doubleValue();
                            p.lon = lon.doubleValue();
                            String altStr = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE);
                            if (altStr != null && altStr.contains("/")) {
                                String[] ab = altStr.split("/");
                                double av = Double.parseDouble(ab[0]) / Double.parseDouble(ab[1]);
                                if (av > 1.0) p.alt = av;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try { if (is != null) is.close(); } catch (Exception ignored) { }
                    }

                    // v1.7 地址恢复链：手动选址 > EXIF 存储地址 > 由 EXIF GPS 反查 > 兜底文案
                    String customAddr = getSharedPreferences("settings", Context.MODE_PRIVATE)
                            .getString("customAddr", "");
                    String fallback = appScope ? "（相册原图，地址未知）" : "（设备照片，地址未知）";
                    if (!customAddr.isEmpty()) {
                        p.addr = customAddr;
                    } else if (descHold[0] != null) {
                        p.addr = descHold[0];
                    } else if (p.lat != null && p.lon != null) {
                        String a = GeoAddr.resolve(GalleryActivity.this, p.lat, p.lon);
                        p.addr = a != null ? a : fallback;
                    } else {
                        p.addr = fallback;
                    }

                    String code = newCode();
                    String fp = PhotoStore.fingerprint(code, when, p);
                    Bitmap wm = Watermark.render(src, when, code, p);
                    PendingPhoto.set(src, wm, when, code, fp, p);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            startActivity(new Intent(GalleryActivity.this, ResultActivity.class));
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { toast("载入失败，请重试"); }
                    });
                }
            }
        });
    }

    private Bitmap decode(Uri u) throws Exception {
        InputStream is = getContentResolver().openInputStream(u);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, o);
        is.close();
        int side = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (side / (sample * 2) >= MAX_SIDE) sample *= 2;
        is = getContentResolver().openInputStream(u);
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeStream(is, null, o2);
        is.close();
        if (b != null && Math.max(b.getWidth(), b.getHeight()) > MAX_SIDE) {
            float sc = MAX_SIDE / (float) Math.max(b.getWidth(), b.getHeight());
            b = Bitmap.createScaledBitmap(b,
                    Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
        }
        return b;
    }

    private Watermark.Params buildParams() {
        SharedPreferences prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
        Watermark.Params p = new Watermark.Params();
        p.fontScale = prefs.getFloat("fontScale", 1.0f);
        p.alpha = prefs.getFloat("alpha", 1.0f);
        p.template = prefs.getInt("template", Watermark.TEMPLATE_MARK);
        p.showAddr = prefs.getBoolean("showAddr", true);
        p.showGeo = prefs.getBoolean("showGeo", true);
        p.showVerify = prefs.getBoolean("showVerify", true);
        p.note = prefs.getString("note", "");
        p.position = prefs.getInt("wmPos", Watermark.POS_BL);
        int ci = prefs.getInt("wmColorIdx", 0);
        p.textColor = Watermark.TEXT_COLORS[ci >= 0 && ci < Watermark.TEXT_COLORS.length ? ci : 0];
        String logoUri = prefs.getString("logoUri", null);
        if (logoUri != null) {
            try {
                InputStream is = getContentResolver().openInputStream(Uri.parse(logoUri));
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, o);
                is.close();
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
                is = getContentResolver().openInputStream(Uri.parse(logoUri));
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                p.logo = BitmapFactory.decodeStream(is, null, o2);
                is.close();
            } catch (Exception ignored) { }
        }
        return p;
    }

    private Float parseGps(String rational, String ref) {
        if (rational == null || !rational.contains(",")) return null;
        try {
            String[] parts = rational.split(",");
            double v = 0;
            for (int i = 0; i < 3; i++) {
                String[] nd = parts[i].split("/");
                double num = Double.parseDouble(nd[0]);
                double den = nd.length > 1 ? Double.parseDouble(nd[1]) : 1;
                v += num / den / Math.pow(60, i);
            }
            return (ref != null && (ref.equals("S") || ref.equals("W"))) ? (float) -v : (float) v;
        } catch (Exception e) {
            return null;
        }
    }

    private String newCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(13);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 13; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdown();
    }
}
