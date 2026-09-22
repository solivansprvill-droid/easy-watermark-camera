package com.victory.easywatermark;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 相册保存（MediaStore）+ EXIF 写入（GPS / 拍摄时间 / 防伪指纹） */
public final class PhotoStore {

    private PhotoStore() { }

    public static Uri save(Context ctx, Bitmap bmp, String subDir, String name) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/水印相机/" + subDir);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("无法创建相册文件");
        OutputStream os = ctx.getContentResolver().openOutputStream(uri);
        if (os == null) throw new IllegalStateException("无法打开输出流");
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
        os.flush();
        os.close();

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        ctx.getContentResolver().update(uri, done, null, null);
        return uri;
    }

    /** EXIF 写入：GPS + DateTimeOriginal + 防伪指纹（UserComment），失败静默忽略 */
    public static void writeExifAll(Context ctx, Uri uri, Watermark.Params p, Date when, String fingerprint) {
        InputStream is = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            ExifInterface exif = new ExifInterface(is);
            if (p.lat != null && p.lon != null) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, Watermark.dms(p.lat));
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, p.lat >= 0 ? "N" : "S");
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, Watermark.dms(p.lon));
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, p.lon >= 0 ? "E" : "W");
                if (p.alt != null && p.alt > 1.0) {
                    double altQ = Math.round(p.alt * 100) / 100.0;
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, Math.round(altQ * 100) + "/100");
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, altQ >= 0 ? "0" : "1");
                }
            }
            // 防伪指纹的可复算字段：拍摄时间（秒级）
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                    new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(when));
            if (fingerprint != null) {
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "WMC1:" + fingerprint);
            }
            exif.saveAttributes();
        } catch (Exception ignored) {
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) { }
        }
    }

    /** 防伪指纹：SHA-256( code | 拍摄时间 | 纬度 | 经度 | 海拔 )，各字段与 EXIF 序列化严格互逆 */
    public static String fingerprint(String code, Date when, Watermark.Params p) {
        String dateStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(when);
        String latS = "-", lonS = "-", altS = "-";
        try {
            if (p.lat != null && p.lon != null) {
                latS = String.format(Locale.US, "%.5f", Watermark.parseDms(Watermark.dms(p.lat)));
                lonS = String.format(Locale.US, "%.5f", Watermark.parseDms(Watermark.dms(p.lon)));
                if (p.alt != null && p.alt > 1.0) {
                    double altQ = Math.round(p.alt * 100) / 100.0;
                    altS = String.valueOf(Math.round(altQ));
                }
            }
        } catch (Exception ignored) { }
        String base = code + "|" + dateStr + "|" + latS + "|" + lonS + "|" + altS;
        return sha256Hex(base.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
