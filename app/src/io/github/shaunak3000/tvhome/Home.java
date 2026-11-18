package io.github.shaunak3000.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The home screen: a changing photo, the clock, Live TV and the inputs, and a row of the TV's apps. */
public final class Home extends Activity implements Photos.Listener {
    private static final long PHOTO_EVERY_MS = 60_000;
    private static final float ROW_TILE_DP = 120, GRID_TILE_DP = 100;
    private static final int DARK = 0xFF0B0E14, PANEL = 0xE00E121A, ACCENT = 0xFFF2C14E;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable nextPhoto = this::nextPhoto;
    private final Runnable hideToast = this::fadeToast;
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            apps.load();
            buildRow();
            if (getCurrentFocus() == null) row.getChildAt(0).requestFocus();
        }
    };

    private Apps apps;
    private Photos photos;
    private ImageView photoBack, photoFront;
    private View scrimTop, all;
    private TextView credit, toast;
    private LinearLayout chips, row;
    private HorizontalScrollView rowScroll;
    private GridLayout grid;
    private PopupWindow menu;
    private Tile moving;
    private int swallowKeyUp = KeyEvent.KEYCODE_UNKNOWN;
    private int lastTileId = View.NO_ID;
    private long lastPhotoAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.home);
        photoBack = findViewById(R.id.photo_back);
        photoFront = findViewById(R.id.photo_front);
        scrimTop = findViewById(R.id.scrim_top);
        credit = findViewById(R.id.credit);
        toast = findViewById(R.id.toast);
        chips = findViewById(R.id.chips);
        row = findViewById(R.id.row);
        rowScroll = findViewById(R.id.row_scroll);
        all = findViewById(R.id.all);
        grid = findViewById(R.id.grid);
        toast.setBackground(rounded(PANEL, 99));

        apps = new Apps(this);
        photos = new Photos(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packagesChanged, filter);

        apps.load();
        buildRow();
        row.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildChips();
        long since = System.currentTimeMillis() - lastPhotoAt;
        if (since > 20_000) nextPhoto();   // a fresh photo each time you come back home
        else handler.postDelayed(nextPhoto, PHOTO_EVERY_MS - since);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(nextPhoto);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        photos.stop();
        super.onDestroy();
    }

    /** Home pressed while already home: close whatever is open and go back to the first app. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (menu != null) menu.dismiss();
        if (moving != null) stopMoving();
        if (all.getVisibility() == View.VISIBLE) closeAll();
        rowScroll.smoothScrollTo(0, 0);
        row.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (all.getVisibility() == View.VISIBLE) closeAll();   // on the home screen itself, Back has nowhere to go
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && key == swallowKeyUp) {
            swallowKeyUp = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (moving == null) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                shift(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            } else if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_BACK) {
                stopMoving();
                swallowKeyUp = key;   // so letting go of OK doesn't also open the app
            }
        }
        return true;   // while a tile is moving, nothing else reacts
    }

    @Override
    public void onPhoto(Bitmap photo, String text, float brightness) {
        if (isDestroyed()) return;
        photoFront.setImageBitmap(photo);
        photoFront.animate().alpha(1f).setDuration(1500).withEndAction(() -> {
            photoBack.setImageBitmap(photo);
            photoFront.setAlpha(0f);
            photoFront.setImageDrawable(null);
        }).start();
        credit.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            credit.setText(text);
            credit.animate().alpha(1f).setDuration(600).start();
        }).start();
        scrimTop.animate().alpha(Math.min(1f, 0.45f + brightness)).setDuration(1500).start();   // darker behind the clock on bright photos
    }

    private void nextPhoto() {
        lastPhotoAt = System.currentTimeMillis();
        photos.next(this);
        handler.removeCallbacks(nextPhoto);
        handler.postDelayed(nextPhoto, PHOTO_EVERY_MS);
    }

    private void buildRow() {
        row.removeAllViews();
        for (String pkg : apps.shown) {
            Apps.App app = apps.all.get(pkg);
            Tile tile = new Tile(this, app.label, app.banner, app.icon, ROW_TILE_DP);
            tile.setOnClickListener(v -> open(app.launch, app.label));
            tile.setOnLongClickListener(v -> showMenu(tile, app));
            addToRow(tile);
        }
        Tile allApps = new Tile(this, "All apps", null, getDrawable(R.drawable.ic_apps), ROW_TILE_DP);
        allApps.setOnClickListener(v -> openAll());
        addToRow(allApps);
    }

    private void addToRow(Tile tile) {
        tile.setId(View.generateViewId());
        tile.setOnFocusChangeListener((v, focused) -> {   // coming back down from the buttons returns to this tile
            if (!focused) return;
            lastTileId = v.getId();
            for (int i = 0; i < chips.getChildCount(); i++) chips.getChildAt(i).setNextFocusDownId(lastTileId);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(4));
        row.addView(tile, params);
    }

    /** Live TV, the HDMI inputs that have something plugged in, and Settings. */
    private void buildChips() {
        chips.removeAllViews();
        Intent liveTv = getPackageManager().getLeanbackLaunchIntentForPackage(Apps.LIVE_TV);
        if (liveTv != null) addChip(R.drawable.ic_live_tv, "Live TV", liveTv);
        TvInputManager tv = getSystemService(TvInputManager.class);
        if (tv != null) {
            List<TvInputInfo> inputs = tv.getTvInputList();
            Map<String, CharSequence> devices = new HashMap<>();   // names that HDMI-CEC devices report, by port
            for (TvInputInfo input : inputs) {
                if (input.getParentId() != null) devices.put(input.getParentId(), input.loadLabel(this));
            }
            for (TvInputInfo input : inputs) {
                if (input.getType() != TvInputInfo.TYPE_HDMI || input.getParentId() != null || input.isHidden(this)
                        || tv.getInputState(input.getId()) == TvInputManager.INPUT_STATE_DISCONNECTED) continue;
                CharSequence name = input.loadCustomLabel(this);
                if (name == null) name = devices.get(input.getId());
                if (name == null) name = input.loadLabel(this);
                addChip(R.drawable.ic_input, name,
                        new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.getId())));
            }
        }
        addChip(R.drawable.ic_settings, "Settings", new Intent(Settings.ACTION_SETTINGS));
    }

    private void addChip(int icon, CharSequence label, Intent intent) {
        TextView chip = new TextView(this);
        Drawable glyph = getDrawable(icon).mutate();
        glyph.setBounds(0, 0, dp(15), dp(15));
        chip.setCompoundDrawablesRelative(glyph, null, null, null);
        chip.setCompoundDrawablePadding(dp(6));
        chip.setCompoundDrawableTintList(focusColors());
        chip.setTextColor(focusColors());
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), 0, dp(13), 0);
        GradientDrawable resting = rounded(0x8C0C1018, 15);
        resting.setStroke(dp(1), 0x29FFFFFF);
        chip.setBackground(focusBackground(rounded(Color.WHITE, 15), resting));
        chip.setFocusable(true);
        chip.setNextFocusDownId(lastTileId);
        chip.setOnClickListener(v -> open(intent, label));
        chip.setOnFocusChangeListener((v, focused) ->
                v.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f).setDuration(160).start());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        params.setMarginStart(dp(8));
        chips.addView(chip, params);
    }

    private void open(Intent intent, CharSequence label) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Couldn't open " + label);
        }
    }

    /** Holding OK on a tile offers Move and Hide. */
    private boolean showMenu(Tile tile, Apps.App app) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(5), dp(5), dp(5), dp(5));
        box.setBackground(rounded(PANEL, 10));
        menu = new PopupWindow(box, dp(130), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.setOnDismissListener(() -> menu = null);
        box.addView(menuItem("Move", () -> startMoving(tile)));
        box.addView(menuItem("Hide", () -> hide(app.pkg)));
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        tile.getLocationOnScreen(at);
        menu.showAtLocation(row, Gravity.NO_GRAVITY, at[0] + (tile.getWidth() - dp(130)) / 2, at[1] - box.getMeasuredHeight() - dp(8));
        return true;
    }

    private TextView menuItem(String label, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        item.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        item.setTextColor(focusColors());
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(9), 0, dp(9), 0);
        item.setBackground(focusBackground(rounded(Color.WHITE, 7), null));
        item.setFocusable(true);
        item.setOnClickListener(v -> {
            menu.dismiss();
            action.run();
        });
        item.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        return item;
    }

    private void startMoving(Tile tile) {
        moving = tile;
        tile.setLifted(true);
        toast("← → to move, then OK");
    }

    private void stopMoving() {
        moving.setLifted(false);
        moving = null;
    }

    private void shift(int step) {
        int from = row.indexOfChild(moving), to = from + step;
        if (to < 0 || to >= apps.shown.size()) return;   // All apps stays last
        View neighbour = row.getChildAt(to);
        row.removeView(neighbour);
        row.addView(neighbour, from);
        apps.move(from, to);
        Tile tile = moving;
        tile.post(() -> tile.requestRectangleOnScreen(new Rect(0, 0, tile.getWidth(), tile.getHeight())));
    }

    private void hide(String pkg) {
        int index = apps.shown.indexOf(pkg);
        CharSequence label = apps.all.get(pkg).label;
        apps.hide(pkg);
        buildRow();
        row.getChildAt(Math.min(index, row.getChildCount() - 1)).requestFocus();
        toast(label + " hidden · it's in All apps");
    }

    /** Every app: the row's in order, then the hidden ones alphabetically. OK shows or hides one. */
    private void openAll() {
        List<Apps.App> hidden = new ArrayList<>();
        for (Apps.App app : apps.all.values()) if (!apps.shown.contains(app.pkg)) hidden.add(app);
        hidden.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        List<Apps.App> everything = new ArrayList<>();
        for (String pkg : apps.shown) everything.add(apps.all.get(pkg));
        everything.addAll(hidden);

        grid.removeAllViews();
        for (Apps.App app : everything) grid.addView(gridCell(app));
        all.setVisibility(View.VISIBLE);
        ((ViewGroup) grid.getChildAt(0)).getChildAt(0).requestFocus();
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        chips.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    private View gridCell(Apps.App app) {
        Tile tile = new Tile(this, app.label, app.banner, app.icon, GRID_TILE_DP);
        TextView name = new TextView(this);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        name.setTextColor(0xDBFFFFFF);
        name.setLines(2);   // the name, then "Hidden" on a line of its own so a long name can't cut it off
        CharSequence title = TextUtils.ellipsize(app.label, name.getPaint(), dp(GRID_TILE_DP), TextUtils.TruncateAt.END);
        name.setPadding(dp(5), dp(2), dp(5), 0);
        Runnable refresh = () -> {
            boolean shown = apps.shown.contains(app.pkg);
            tile.setAlpha(shown ? 1f : 0.34f);
            SpannableString text = new SpannableString(shown ? title : title + "\nHidden");
            if (!shown) text.setSpan(new ForegroundColorSpan(ACCENT), title.length() + 1, text.length(), 0);
            name.setText(text);
        };
        refresh.run();
        tile.setOnClickListener(v -> {
            boolean shown = apps.shown.contains(app.pkg);
            if (shown) apps.hide(app.pkg);
            else apps.show(app.pkg);
            refresh.run();
            toast(app.label + (shown ? " hidden" : " added to the end of your row"));
        });

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.addView(tile);
        cell.addView(name, new LinearLayout.LayoutParams(dp(GRID_TILE_DP + 10), ViewGroup.LayoutParams.WRAP_CONTENT));   // the tile's width, so long names can't widen a column
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.setMargins(0, 0, dp(14), dp(13));
        cell.setLayoutParams(params);
        return cell;
    }

    private void closeAll() {
        row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        chips.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        all.setVisibility(View.GONE);
        grid.removeAllViews();
        buildRow();
        row.getChildAt(row.getChildCount() - 1).requestFocus();   // back on the All apps tile
    }

    private void toast(CharSequence text) {
        toast.setText(text);
        toast.animate().alpha(1f).setDuration(200).start();
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 2400);
    }

    private void fadeToast() {
        toast.animate().alpha(0f).setDuration(200).start();
    }

    private static ColorStateList focusColors() {
        return new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {DARK, Color.WHITE});
    }

    private static StateListDrawable focusBackground(Drawable focused, Drawable resting) {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, focused);
        if (resting != null) background.addState(new int[0], resting);
        return background;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
