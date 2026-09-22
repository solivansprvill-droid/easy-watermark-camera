package com.victory.easywatermark;

import android.graphics.Bitmap;
import java.util.Date;

/** 拍摄会话的待处理照片（大 Bitmap 不走 Intent，用静态持有） */
public final class PendingPhoto {
    public static Bitmap original;      // 无水印原图（可被编辑器替换）
    public static Bitmap watermarked;   // 合成水印后的成片
    public static Date date;            // 拍摄时刻（水印时间定格）
    public static String code;          // 防伪编号
    public static String fingerprint;   // 防伪指纹（写入 EXIF）
    public static Watermark.Params params; // 水印参数快照

    public static void set(Bitmap orig, Bitmap wm, Date d, String c, String fp, Watermark.Params p) {
        original = orig;
        watermarked = wm;
        date = d;
        code = c;
        fingerprint = fp;
        params = p;
    }

    public static void clear() {
        original = null;
        watermarked = null;
        date = null;
        code = null;
        fingerprint = null;
        params = null;
    }

    private PendingPhoto() { }
}
