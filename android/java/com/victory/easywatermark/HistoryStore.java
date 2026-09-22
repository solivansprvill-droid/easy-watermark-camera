package com.victory.easywatermark;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 拍摄历史：私有目录缩略图 + index.json（最多 50 条） */
public final class HistoryStore {

    public static class Item {
        public String code, thumb, addr;
        public long time;
    }

    private HistoryStore() { }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "history");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File indexFile(Context ctx) {
        return new File(dir(ctx), "index.json");
    }

    public static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            File f = indexFile(ctx);
            if (!f.exists()) return out;
            byte[] buf = new byte[(int) f.length()];
            InputStream is = new FileInputStream(f);
            int read = is.read(buf);
            is.close();
            JSONArray arr = new JSONArray(new String(buf, 0, Math.max(0, read), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.code = o.optString("code");
                it.thumb = o.optString("thumb");
                it.addr = o.optString("addr");
                it.time = o.optLong("time");
                if (new File(it.thumb).exists()) out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void add(Context ctx, Bitmap photo, String code, long time, String addr) {
        try {
            File thumbFile = new File(dir(ctx), code + ".jpg");
            Bitmap thumb = Bitmap.createScaledBitmap(photo, 320,
                    Math.round(photo.getHeight() * 320f / photo.getWidth()), true);
            FileOutputStream fo = new FileOutputStream(thumbFile);
            thumb.compress(Bitmap.CompressFormat.JPEG, 80, fo);
            fo.flush();
            fo.close();

            List<Item> items = list(ctx);
            HistoryStore.Item it = new HistoryStore.Item();
            it.code = code;
            it.thumb = thumbFile.getAbsolutePath();
            it.addr = addr != null ? addr : "";
            it.time = time;
            items.add(0, it);
            while (items.size() > 50) {
                Item last = items.remove(items.size() - 1);
                new File(last.thumb).delete();
            }
            save(ctx, items);
        } catch (Exception ignored) { }
    }

    public static synchronized void delete(Context ctx, String code) {
        try {
            List<Item> items = list(ctx);
            for (int i = 0; i < items.size(); i++) {
                if (code.equals(items.get(i).code)) {
                    new File(items.get(i).thumb).delete();
                    items.remove(i);
                    break;
                }
            }
            save(ctx, items);
        } catch (Exception ignored) { }
    }

    public static synchronized void clear(Context ctx) {
        try {
            List<Item> items = list(ctx);
            for (Item it : items) new File(it.thumb).delete();
            indexFile(ctx).delete();
        } catch (Exception ignored) { }
    }

    private static void save(Context ctx, List<Item> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject();
            o.put("code", it.code);
            o.put("thumb", it.thumb);
            o.put("addr", it.addr);
            o.put("time", it.time);
            arr.put(o);
        }
        FileOutputStream fo = new FileOutputStream(indexFile(ctx));
        fo.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        fo.close();
    }
}
