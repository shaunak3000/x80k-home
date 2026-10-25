package io.github.shaunak3000.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The TV's apps, and which of them the home row shows, in what order. */
final class Apps {
    /** The first time the app runs, the row shows these, in this order, if installed. Everything else starts in All apps. */
    private static final List<String> FAVORITES = Arrays.asList(
            "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "com.netflix.ninja",
            "com.wbd.stream", "com.disney.disneyplus", "com.apple.atve.sony.appletv",
            "com.hulu.livingroomplus", "com.cbs.ott", "com.peacocktv.peacockandroid",
            "com.spotify.tv.android", "tv.twitch.android.app", "com.essentricstv");

    /** Sony's TV app, opened by the Live TV button rather than a tile. */
    static final String LIVE_TV = "com.sony.dtv.tvx";

    static final class App {
        final String pkg;
        final CharSequence label;
        final Drawable banner;
        final Drawable icon;
        final Intent launch;

        App(String pkg, CharSequence label, Drawable banner, Drawable icon, Intent launch) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.launch = launch;
        }
    }

    final Map<String, App> all = new LinkedHashMap<>();
    final List<String> shown = new ArrayList<>();
    private final Context context;
    private final SharedPreferences prefs;

    Apps(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE);
    }

    void load() {
        PackageManager pm = context.getPackageManager();
        Intent tvApps = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        all.clear();
        for (ResolveInfo info : pm.queryIntentActivities(tvApps, 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(LIVE_TV) || all.containsKey(pkg)) continue;
            Intent launch = new Intent(tvApps).setClassName(pkg, info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            all.put(pkg, new App(pkg, info.loadLabel(pm), info.activityInfo.loadBanner(pm), info.loadIcon(pm), launch));
        }

        String known = prefs.getString("known", null);
        List<String> before = split(prefs.getString("shown", ""));
        shown.clear();
        if (known == null) {
            for (String pkg : FAVORITES) if (all.containsKey(pkg)) shown.add(pkg);
        } else {
            for (String pkg : before) if (all.containsKey(pkg)) shown.add(pkg);
            List<String> seenBefore = split(known);
            for (String pkg : all.keySet()) if (!seenBefore.contains(pkg)) shown.add(pkg);   // installed since last time
        }
        save();
    }

    void hide(String pkg) {
        shown.remove(pkg);
        save();
    }

    void show(String pkg) {
        if (!shown.contains(pkg)) shown.add(pkg);
        save();
    }

    void move(int from, int to) {
        shown.add(to, shown.remove(from));
        save();
    }

    private void save() {
        prefs.edit()
                .putString("shown", TextUtils.join(",", shown))
                .putString("known", TextUtils.join(",", all.keySet()))
                .apply();
    }

    private static List<String> split(String joined) {
        return joined.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(joined.split(",")));
    }
}
