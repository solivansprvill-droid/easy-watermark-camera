package com.victory.easywatermark;

import android.content.Context;
import android.location.Geocoder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** 反地理编码共享工具（v1.7）：Geocoder 优先，Nominatim 兜底 */
public final class GeoAddr {

    /** 由经纬度解析中文地址，失败返回 null（须在后台线程调用） */
    public static String resolve(Context ctx, double lat, double lon) {
        String addr = null;
        try {
            if (Geocoder.isPresent()) {
                List<android.location.Address> list = new Geocoder(ctx, Locale.CHINA)
                        .getFromLocation(lat, lon, 1);
                if (list != null && !list.isEmpty()) {
                    android.location.Address a = list.get(0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(a.getAddressLine(i));
                    }
                    addr = Watermark.cleanAddress(sb.toString());
                }
            }
        } catch (Exception ignored) { }
        if (addr == null || addr.isEmpty()) addr = nominatimReverse(lat, lon);
        return (addr != null && !addr.isEmpty()) ? addr : null;
    }

    private static String nominatimReverse(double lat, double lon) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=zh-CN&lat=" + lat + "&lon=" + lon);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "WatermarkCamera/1.7");
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

    private GeoAddr() { }
}
