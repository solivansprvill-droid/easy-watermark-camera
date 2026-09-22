package com.victory.easywatermark;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 拍摄历史：缩略图墙 + 防伪码管理 + 批量补印 */
public class HistoryActivity extends Activity {

    private static final int REQ_IMPORT = 10;
    private static final int MAX_SIDE = 3200;

    private GridView grid;
    private TextView emptyHint;
    private List<HistoryStore.Item> items = new ArrayList<HistoryStore.Item>();
    private SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    private final class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout box = convertView instanceof LinearLayout ? (LinearLayout) convertView : null;
            if (box == null) {
                box = new LinearLayout(HistoryActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (6 * getResources().getDisplayMetrics().density);
                box.setPadding(pad, pad, pad, pad);
                ImageView iv = new ImageView(HistoryActivity.this);
                iv.setId(android.R.id.icon);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                box.addView(iv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (110 * getResources().getDisplayMetrics().density)));
                TextView tv = new TextView(HistoryActivity.this);
                tv.setId(android.R.id.text1);
                tv.setTextColor(0xFFCCCCCC);
                tv.setTextSize(10);
                box.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            HistoryStore.Item it = items.get(position);
            ImageView iv = box.findViewById(android.R.id.icon);
            TextView tv = box.findViewById(android.R.id.text1);
            Bitmap bmp = BitmapFactory.decodeFile(it.thumb);
            iv.setImageBitmap(bmp);
            tv.setText(it.code);
            return box;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        grid = findViewById(R.id.history_grid);
        emptyHint = findViewById(R.id.empty_hint);
        grid.setAdapter(new GridAdapter());
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent, View view, final int position, long id) {
                showItemDialog(items.get(position));
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btn_import).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImages(); }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(HistoryActivity.this)
                        .setTitle("清空历史")
                        .setMessage("仅删除历史缩略图与记录，不影响相册照片。确定？")
                        .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                HistoryStore.clear(HistoryActivity.this);
                                reload();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items = HistoryStore.list(this);
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        emptyHint.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showItemDialog(final HistoryStore.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("防伪: " + it.code)
                .setMessage("拍摄时间：" + fmt.format(new Date(it.time))
                        + (it.addr != null && !it.addr.isEmpty() ? "\n📍 " + Watermark.cleanAddress(it.addr) : ""))
                .setItems(new String[]{"复制防伪码", "删除记录"}, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 0) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("code", it.code));
                            toast("防伪码已复制");
                        } else {
                            HistoryStore.delete(HistoryActivity.this, it.code);
                            reload();
                        }
                    }
                })
                .show();
    }

    private void pickImages() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("image/*");
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(it, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        toast("开始补印 " + uris.size() + " 张…");
        final Watermark.Params baseParams = buildParams();
        new Thread(new Runnable() {
            @Override public void run() {
                int ok = 0;
                SimpleDateFormat fileFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
                for (Uri uri : uris) {
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeStream(is, null, o);
                        is.close();
                        int sample = 1;
                        while (Math.max(o.outWidth, o.outHeight) / sample > MAX_SIDE) sample *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = sample;
                        is = getContentResolver().openInputStream(uri);
                        Bitmap src = BitmapFactory.decodeStream(is, null, o);
                        is.close();
                        if (src == null) continue;

                        // 读取原照片 EXIF：拍摄时间与 GPS
                        Watermark.Params p = new Watermark.Params();
                        copySettings(baseParams, p);
                        Date when = new Date();
                        try {
                            is = getContentResolver().openInputStream(uri);
                            ExifInterface exif = new ExifInterface(is);
                            String dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                            if (dt != null && dt.length() >= 19) {
                                SimpleDateFormat exifFmt = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
                                Date d = exifFmt.parse(dt);
                                if (d != null) when = d;
                            }
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
                            try { is.close(); } catch (Exception ignored) { }
                        }
                        p.addr = "（相册照片，地址未知）";

                        String code = newCode();
                        String fp = PhotoStore.fingerprint(code, when, p);
                        Bitmap wm = Watermark.render(src, when, code, p);
                        PhotoStore.save(HistoryActivity.this, wm, "Watermarked",
                                "REPRINT_" + fileFmt.format(when) + "_" + code + ".jpg");
                        HistoryStore.add(HistoryActivity.this, wm, code, when.getTime(), p.addr);
                        ok++;
                    } catch (Exception ignored) { }
                }
                final int done = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast("补印完成 " + done + "/" + uris.size() + " 张");
                        reload();
                    }
                });
            }
        }).start();
    }

    private void copySettings(Watermark.Params from, Watermark.Params to) {
        to.fontScale = from.fontScale;
        to.alpha = from.alpha;
        to.template = from.template;
        to.showAddr = from.showAddr;
        to.showGeo = from.showGeo;
        to.showVerify = from.showVerify;
        to.note = from.note;
        to.logo = from.logo;
        to.azimuth = null;
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

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
