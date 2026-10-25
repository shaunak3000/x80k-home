package io.github.shaunak3000.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Background photos: Wikimedia Commons' featured landscape photos, picked at random and kept on disk.
 * This is the app's only network code, and get() refuses any address that isn't Wikimedia's.
 */
final class Photos {
    interface Listener {
        void onPhoto(Bitmap photo, String credit, float clockAreaBrightness);
    }

    private static final String API = "https://commons.wikimedia.org/w/api.php?format=json&formatversion=2&action=query&";
    private static final String USER_AGENT = "x80k-home/0.1 (github.com/shaunak3000/x80k-home)";
    private static final String[] CATEGORIES = {
        "landscapes", "mountains", "coasts", "bodies of water", "forests", "islands", "volcanoes"};
    private static final int KEEP = 20;
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    private final File dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Set<String> seen = new HashSet<>();
    private List<String> titles;
    private String current;

    Photos(Context context) {
        dir = new File(context.getFilesDir(), "photos");
        dir.mkdirs();
    }

    /** Shows a photo not seen yet, downloading one if needed (any saved photo when offline), then fetches the next. */
    void next(Listener listener) {
        worker.execute(() -> {
            File photo = pick(true);
            if (photo == null) photo = download();
            if (photo == null) photo = pick(false);
            if (photo != null) show(photo, listener);
            if (pick(true) == null) download();
        });
    }

    void stop() {
        worker.shutdownNow();
    }

    private File pick(boolean unseenOnly) {
        List<File> pool = new ArrayList<>();
        for (File photo : onDisk()) {
            String name = photo.getName();
            if (!name.equals(current) && !(unseenOnly && seen.contains(name))) pool.add(photo);
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    private void show(File photo, Listener listener) {
        Bitmap bitmap = BitmapFactory.decodeFile(photo.getPath());
        if (bitmap == null) {   // a damaged file: drop it
            delete(photo);
            return;
        }
        current = photo.getName();
        seen.add(current);
        String credit = read(new File(photo.getPath() + ".txt"));
        float brightness = brightness(bitmap);
        main.post(() -> listener.onPhoto(bitmap, credit, brightness));
    }

    /** Average brightness, from 0 to 1, of the top-left corner where the clock sits. */
    private static float brightness(Bitmap bitmap) {
        float sum = 0;
        int count = 0;
        for (int y = 0; y < bitmap.getHeight() / 3; y += Math.max(1, bitmap.getHeight() / 30)) {
            for (int x = 0; x < bitmap.getWidth() / 2; x += Math.max(1, bitmap.getWidth() / 40)) {
                int c = bitmap.getPixel(x, y);
                sum += 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c);
                count++;
            }
        }
        return count == 0 ? 0 : sum / count / 255f;
    }

    /** Downloads one random photo shaped close to the screen, or returns null if that fails (offline, for example). */
    private File download() {
        try {
            if (titles == null) titles = titles();
            for (int attempt = 0; attempt < 4 && !titles.isEmpty(); attempt++) {
                String title = titles.get(random.nextInt(titles.size()));
                JSONObject info = json(API + "prop=imageinfo&iiprop=size%7Curl%7Cextmetadata&iiurlwidth=1920"
                        + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CObjectName&titles=" + encode(title))
                        .getJSONObject("query").getJSONArray("pages").getJSONObject(0)
                        .getJSONArray("imageinfo").getJSONObject(0);
                double ratio = info.getDouble("width") / info.getDouble("height");
                if (ratio < 1.4 || ratio > 2) continue;   // anything narrower or wider would be cropped heavily

                JSONObject meta = info.getJSONObject("extmetadata");
                List<String> credit = new ArrayList<>();
                for (String field : new String[] {"ObjectName", "Artist", "LicenseShortName"}) {
                    JSONObject value = meta.optJSONObject(field);
                    String text = value == null ? "" : Html.fromHtml(value.optString("value"), Html.FROM_HTML_MODE_LEGACY).toString().trim();
                    if (!text.isEmpty()) credit.add(text);
                }
                credit.add("Wikimedia Commons");

                File photo = new File(dir, Integer.toHexString(title.hashCode()) + ".jpg");
                write(photo, get(info.getString("thumburl")));
                write(new File(photo.getPath() + ".txt"), TextUtils.join(" · ", credit).getBytes(StandardCharsets.UTF_8));
                trim();
                return photo;
            }
        } catch (IOException | JSONException e) {
            Log.w("x80k-home", "Photo download failed; the saved photos keep showing", e);
        }
        return null;
    }

    /** Every file in the featured categories, refreshed weekly. */
    private List<String> titles() throws IOException, JSONException {
        File list = new File(dir, "titles.txt");
        if (list.exists() && System.currentTimeMillis() - list.lastModified() < WEEK_MS) return lines(list);
        try {
            List<String> found = new ArrayList<>();
            for (String category : CATEGORIES) {
                JSONArray members = json(API + "list=categorymembers&cmtype=file&cmlimit=500&cmtitle="
                        + encode("Category:Featured pictures of " + category))
                        .getJSONObject("query").getJSONArray("categorymembers");
                for (int i = 0; i < members.length(); i++) found.add(members.getJSONObject(i).getString("title"));
            }
            write(list, TextUtils.join("\n", found).getBytes(StandardCharsets.UTF_8));
            return found;
        } catch (IOException | JSONException e) {
            if (list.exists()) return lines(list);   // an old list beats none
            throw e;
        }
    }

    /** The app's only network request. Anything but Wikimedia's API and its image servers is refused. */
    private static byte[] get(String url) throws IOException {
        if (!url.startsWith(API) && !url.startsWith("https://thumb.wikimedia.org/") && !url.startsWith("https://upload.wikimedia.org/")) {
            throw new IOException("Not a Wikimedia address: " + url);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try (InputStream in = connection.getInputStream()) {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + connection.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject json(String url) throws IOException, JSONException {
        return new JSONObject(new String(get(url), StandardCharsets.UTF_8));
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private File[] onDisk() {
        File[] photos = dir.listFiles((folder, name) -> name.endsWith(".jpg"));
        return photos == null ? new File[0] : photos;
    }

    /** Keeps the newest few photos and deletes the rest. */
    private void trim() {
        File[] photos = onDisk();
        Arrays.sort(photos, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < photos.length; i++) {
            if (!photos[i].getName().equals(current)) delete(photos[i]);
        }
    }

    private static void delete(File photo) {
        photo.delete();
        new File(photo.getPath() + ".txt").delete();
    }

    private static void write(File file, byte[] bytes) throws IOException {
        File partial = new File(file.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            out.write(bytes);
        }
        if (!partial.renameTo(file)) throw new IOException("Could not save " + file);
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static List<String> lines(File file) {
        return new ArrayList<>(Arrays.asList(read(file).split("\n")));
    }
}
