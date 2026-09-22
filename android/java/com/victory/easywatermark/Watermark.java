package com.victory.easywatermark;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 马克风格水印渲染器 v2：三套模板 + 字段开关 + 备注/Logo + 方位角 + 短边自适应 */
public final class Watermark {

    public static final int TEMPLATE_MARK = 0;   // 马克式（信息块）
    public static final int TEMPLATE_MINI = 1;   // 极简式（两行小字）
    public static final int TEMPLATE_BAR  = 2;   // 通栏式（全宽条）

    /** 水印位置（v1.4） */
    public static final int POS_BL = 0;          // 左下（默认）
    public static final int POS_BR = 1;          // 右下
    public static final int POS_TL = 2;          // 左上
    public static final int POS_TR = 3;          // 右上

    /** 水印文字颜色（v1.4）：白/黑/黄/青 */
    public static final int[] TEXT_COLORS = {0xFFFFFF, 0x000000, 0xFFD60A, 0x4DD0E1};
    public static final String[] TEXT_COLOR_NAMES = {"白色", "黑色", "黄色", "青色"};

    private static final SimpleDateFormat FMT_TIME = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private static final SimpleDateFormat FMT_DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private static final SimpleDateFormat FMT_WEEK = new SimpleDateFormat("EEEE", Locale.CHINA);
    private static final SimpleDateFormat FMT_FILE = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
    public static final String VERIFY_TEXT = "🛡 水印相机已验证照片真实性";

    /** 拍摄瞬间的水印参数快照 */
    public static class Params {
        public float fontScale = 1.0f;
        public float alpha = 1.0f;
        public int template = TEMPLATE_MARK;
        public boolean showAddr = true;
        public boolean showGeo = true;
        public boolean showVerify = true;
        public String addr;             // 逆地理地址（可 null）
        public String note;             // 自定义备注（可 null）
        public Double lat, lon, alt;    // 可 null
        public Double azimuth;          // 罗盘方位角（可 null）
        public Bitmap logo;             // 自定义 Logo（可 null）
        public int position = POS_BL;   // 水印位置（v1.4）
        public int textColor = 0xFFFFFF;// 水印文字颜色 RGB（v1.4）
    }

    private Watermark() { }

    /** 在副本上绘制水印 */
    public static Bitmap render(Bitmap src, Date when, String code, Params p) {
        Bitmap out = src.copy(src.getConfig(), true);
        draw(out, when, code, p);
        return out;
    }

    public static void draw(Bitmap bmp, Date when, String code, Params p) {
        if (p.template == TEMPLATE_MINI) drawMini(bmp, when, code, p);
        else if (p.template == TEMPLATE_BAR) drawBar(bmp, when, code, p);
        else drawMark(bmp, when, code, p);
    }

    // ---------- 公共文本 ----------

    private static String timeText(Date when) { return FMT_TIME.format(when); }

    private static String dateText(Date when) {
        return FMT_WEEK.format(when) + " / " + FMT_DATE.format(when);
    }

    private static String addrText(Params p) {
        return p.addr != null ? "📍 " + p.addr : "📍 定位信息未获取";
    }

    private static String geoText(Params p) {
        if (p.lat == null) return "🌐 GPS 未启用";
        StringBuilder sb = new StringBuilder("🌐 ")
                .append(String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon));
        if (p.alt != null && p.alt > 1.0) sb.append(" · 🏔️ ").append(String.format(Locale.US, "%.0fm", p.alt));
        if (p.azimuth != null) sb.append(" · 🧭 ").append(String.format(Locale.US, "%.0f°", p.azimuth)).append(dirName(p.azimuth));
        return sb.toString();
    }

    private static String antiText(String code) {
        return "防伪: " + (code != null ? code : "XXXXXXXXXXXXX");
    }

    public static String dirName(Double deg) {
        String[] dirs = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        int i = (int) Math.floor(((deg % 360) + 360) % 360 / 45.0 + 0.5) % 8;
        return dirs[i];
    }

    // ---------- 模板 0：马克式（左下信息块） ----------

    private static void drawMark(Bitmap bmp, Date when, String code, Params p) {
        Canvas c = new Canvas(bmp);
        float base = Math.min(bmp.getWidth(), bmp.getHeight()) / 1080f;
        float k = base * p.fontScale;

        Paint timeP = text(64 * k, true);
        Paint dateP = text(30 * k, false);
        Paint fieldP = text(28 * k, false);
        Paint verifyP = text(26 * k, false);
        Paint antiP = text(20 * k, false);
        Paint noteP = text(26 * k, false);

        float maxW = bmp.getWidth() * 0.92f - 72 * k;
        String t1 = timeText(when), t2 = dateText(when);
        String tAddr = p.showAddr ? ellipsize(fieldP, addrText(p), maxW) : null;
        String tGeo = p.showGeo ? ellipsize(fieldP, geoText(p), maxW) : null;
        String tNote = (p.note != null && !p.note.isEmpty()) ? ellipsize(fieldP, "📌 " + p.note, maxW) : null;
        String tAnti = antiText(code);
        String tVerify = tVerify(p);

        float blockW = blockWidth(timeP, dateP, fieldP, verifyP, antiP, noteP,
                t1, t2, tAddr, tGeo, tNote, tVerify, tAnti);

        int A = Math.round(255 * p.alpha);
        int rgb = p.textColor & 0xFFFFFF;
        int textWhite = (A << 24) | rgb;
        int textDim = (Math.round(255 * p.alpha * 0.85f) << 24) | rgb;

        boolean right = (p.position == POS_BR || p.position == POS_TR);
        boolean top = (p.position == POS_TL || p.position == POS_TR);

        float marginX = 36 * k, gap = 14 * k;
        float hTime = 64 * k, hDate = 30 * k, hField = 28 * k, hVerify = 26 * k, hAnti = 20 * k, hNote = 28 * k;
        float logoH = 0, logoW = 0;
        if (p.logo != null) {
            logoH = 52 * k;
            logoW = Math.min(p.logo.getWidth() * logoH / p.logo.getHeight(), blockW);
        }

        float totalH = logoH + (logoH > 0 ? gap * 0.6f : 0)
                + hTime + gap * 0.4f + hDate + gap
                + (tAddr != null ? hField + gap * 0.6f : 0)
                + (tGeo != null ? hField + gap * 0.6f : 0)
                + (tNote != null ? hNote + gap * 0.6f : 0)
                + (tVerify != null ? hVerify + gap * 0.5f : 0)
                + hAnti;

        float x = right ? bmp.getWidth() - marginX - blockW : marginX;
        if (x < marginX) x = marginX;
        float y = top ? marginX : bmp.getHeight() - marginX - totalH;
        if (!top && y < marginX) y = marginX;

        if (p.logo != null) {
            float lx = right ? x + blockW - logoW : x;
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            lp.setAlpha(A);
            c.drawBitmap(p.logo, null, new RectF(lx, y, lx + logoW, y + logoH), lp);
            y += logoH + gap * 0.6f;
        }

        timeP.setColor(textWhite); shadow(timeP, p.alpha);
        line(c, timeP, t1, x, blockW, y + hTime, right);
        y += hTime + gap * 0.4f;
        dateP.setColor(textDim); shadow(dateP, p.alpha);
        line(c, dateP, t2, x, blockW, y + hDate, right);
        y += hDate + gap;
        if (tAddr != null) { fieldP.setColor(textWhite); shadow(fieldP, p.alpha); line(c, fieldP, tAddr, x, blockW, y + hField, right); y += hField + gap * 0.6f; }
        if (tGeo != null) { fieldP.setColor(textDim); line(c, fieldP, tGeo, x, blockW, y + hField, right); y += hField + gap * 0.6f; }
        if (tNote != null) { noteP.setColor(textWhite); shadow(noteP, p.alpha); line(c, noteP, tNote, x, blockW, y + hNote, right); y += hNote + gap * 0.6f; }
        if (tVerify != null) { verifyP.setColor(textWhite); shadow(verifyP, p.alpha); line(c, verifyP, tVerify, x, blockW, y + hVerify, right); y += hVerify + gap * 0.5f; }
        antiP.setColor(textDim);
        c.drawText(tAnti, x + blockW - antiP.measureText(tAnti), y + hAnti, antiP);
    }

    /** 单行绘制：right 时按信息块右缘对齐（v1.4 位置支持） */
    private static void line(Canvas c, Paint pnt, String t, float x, float blockW, float y, boolean right) {
        c.drawText(t, right ? x + blockW - pnt.measureText(t) : x, y, pnt);
    }

    private static String tVerify(Params p) { return p.showVerify ? VERIFY_TEXT : null; }

    private static float blockWidth(Paint timeP, Paint dateP, Paint fieldP, Paint verifyP, Paint antiP, Paint noteP,
                                    String t1, String t2, String addr, String geo, String note, String verify, String anti) {
        float w = Math.max(timeP.measureText(t1), dateP.measureText(t2));
        if (addr != null) w = Math.max(w, fieldP.measureText(addr));
        if (geo != null) w = Math.max(w, fieldP.measureText(geo));
        if (note != null) w = Math.max(w, noteP.measureText(note));
        if (verify != null) w = Math.max(w, verifyP.measureText(verify));
        w = Math.max(w, antiP.measureText(anti));
        return w;
    }

    // ---------- 模板 1：极简式（两行小字） ----------

    private static void drawMini(Bitmap bmp, Date when, String code, Params p) {
        Canvas c = new Canvas(bmp);
        float base = Math.min(bmp.getWidth(), bmp.getHeight()) / 1080f;
        float k = base * p.fontScale;

        int A = Math.round(255 * p.alpha);
        int rgb = p.textColor & 0xFFFFFF;
        int textWhite = (A << 24) | rgb;
        int textDim = (Math.round(255 * p.alpha * 0.85f) << 24) | rgb;

        Paint lineP = text(24 * k, false);
        float maxW = bmp.getWidth() * 0.92f - 48 * k;

        String l1 = FMT_DATE.format(when) + " " + timeText(when) + " " + FMT_WEEK.format(when)
                + ((p.note != null && !p.note.isEmpty()) ? " · " + p.note : "");
        StringBuilder l2 = new StringBuilder();
        if (p.showAddr) l2.append(addrText(p));
        if (p.showGeo && p.lat != null) {
            if (l2.length() > 0) l2.append(" · ");
            l2.append(String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon));
        }
        if (p.showVerify) {
            if (l2.length() > 0) l2.append(" · ");
            l2.append("🛡 已验证");
        }
        if (l2.length() > 0) l2.append(" · ");
        l2.append(antiText(code));

        l1 = ellipsize(lineP, l1, maxW);
        String l2s = ellipsize(lineP, l2.toString(), maxW);

        float marginX = 24 * k, gap = 8 * k;
        float hLine = 24 * k;
        float totalH = hLine + gap + hLine;
        boolean right = (p.position == POS_BR || p.position == POS_TR);
        boolean top = (p.position == POS_TL || p.position == POS_TR);
        float bw = Math.max(lineP.measureText(l1), lineP.measureText(l2s));
        float x = right ? bmp.getWidth() - marginX - bw : marginX;
        if (x < marginX) x = marginX;
        float y = top ? marginX : bmp.getHeight() - marginX - totalH;
        if (!top && y < marginX) y = marginX;

        lineP.setColor(textWhite); shadow(lineP, p.alpha);
        line(c, lineP, l1, x, bw, y + hLine, right);
        lineP.setColor(textDim);
        line(c, lineP, l2s, x, bw, y + hLine + gap + hLine, right);
    }

    // ---------- 模板 2：通栏式（底部全宽条） ----------

    private static void drawBar(Bitmap bmp, Date when, String code, Params p) {
        Canvas c = new Canvas(bmp);
        float base = Math.min(bmp.getWidth(), bmp.getHeight()) / 1080f;
        float k = base * p.fontScale;

        Paint timeP = text(52 * k, true);
        Paint dateP = text(24 * k, false);
        Paint fieldP = text(24 * k, false);
        Paint antiP = text(18 * k, false);

        float padX = 28 * k, gap = 10 * k;
        float hTime = 52 * k, hField = 24 * k, hAnti = 18 * k;
        float maxW = bmp.getWidth() - padX * 2;

        String tAddr = p.showAddr ? ellipsize(fieldP, addrText(p), maxW) : null;
        String tGeo = p.showGeo ? ellipsize(fieldP, geoText(p), maxW) : null;
        String tNote = (p.note != null && !p.note.isEmpty()) ? ellipsize(fieldP, "📌 " + p.note, maxW) : null;
        String tVerify = p.showVerify ? VERIFY_TEXT : null;

        float rows = hTime + gap
                + (tAddr != null ? hField + gap : 0)
                + (tGeo != null ? hField + gap : 0)
                + (tNote != null ? hField + gap : 0)
                + (tVerify != null ? hField + gap : 0)
                + hAnti + gap;
        float barH = rows + 24 * k;
        boolean top = (p.position == POS_TL || p.position == POS_TR);
        float barTop = top ? 0 : bmp.getHeight() - barH;

        int A = Math.round(255 * p.alpha);
        Paint bg = new Paint();
        bg.setColor((Math.round(255 * p.alpha * 0.62f) << 24));
        c.drawRect(0, barTop, bmp.getWidth(), barTop + barH, bg);

        int rgb = p.textColor & 0xFFFFFF;
        int textWhite = (A << 24) | rgb;
        int textDim = (Math.round(255 * p.alpha * 0.82f) << 24) | rgb;

        float y = barTop + 14 * k;
        timeP.setColor(textWhite);
        c.drawText(timeText(when), padX, y + hTime, timeP);
        String dt = dateText(when);
        dateP.setColor(textDim);
        c.drawText(dt, bmp.getWidth() - padX - dateP.measureText(dt), y + hTime * 0.9f, dateP);
        y += hTime + gap;

        if (tAddr != null) { fieldP.setColor(textWhite); c.drawText(tAddr, padX, y + hField, fieldP); y += hField + gap; }
        if (tGeo != null) { fieldP.setColor(textDim); c.drawText(tGeo, padX, y + hField, fieldP); y += hField + gap; }
        if (tNote != null) { fieldP.setColor(textWhite); c.drawText(tNote, padX, y + hField, fieldP); y += hField + gap; }
        if (tVerify != null) { fieldP.setColor(textDim); c.drawText(tVerify, padX, y + hField, fieldP); y += hField + gap; }
        antiP.setColor(textDim);
        c.drawText(antiText(code), bmp.getWidth() - padX - antiP.measureText(antiText(code)), y + hAnti, antiP);
    }

    // ---------- 工具 ----------

    private static void shadow(Paint p, float alpha) {
        p.setShadowLayer(6, 0, 2, (Math.round(255 * alpha * 0.6f) << 24));
    }

    private static String ellipsize(Paint p, String s, float maxW) {
        if (s == null) return null;
        while (p.measureText(s) > maxW && s.length() > 4) s = s.substring(0, s.length() - 2) + "…";
        return s;
    }

    private static Paint text(float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(size);
        p.setFakeBoldText(bold);
        p.setTypeface(Typeface.create(bold ? "sans-serif-black" : "sans-serif-light",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    /** 十进制度 → 度/分/秒（Rational 格式） */
    public static String dms(double v) {
        v = Math.abs(v);
        int d = (int) v;
        double mFloat = (v - d) * 60;
        int m = (int) mFloat;
        double s = (mFloat - m) * 60;
        return d + "/1," + m + "/1," + Math.round(s * 1000) + "/1000";
    }

    /** DMS Rational → 十进制度（与 dms 写入精确互逆） */
    public static double parseDms(String rational) {
        String[] parts = rational.split(",");
        double d = Double.parseDouble(parts[0].split("/")[0]) / Double.parseDouble(parts[0].contains("/") ? parts[0].split("/")[1] : "1");
        double m = Double.parseDouble(parts[1].split("/")[0]) / Double.parseDouble(parts[1].split("/")[1]);
        double s = Double.parseDouble(parts[2].split("/")[0]) / Double.parseDouble(parts[2].split("/")[1]);
        return d + m / 60.0 + s / 3600.0;
    }

    /** 地址清洗：去"中国"前缀等 */
    public static String cleanAddress(String addr) {
        if (addr == null) return null;
        return addr.replaceFirst("^中国", "").replaceFirst("^[,，、\\s]+", "");
    }
}
