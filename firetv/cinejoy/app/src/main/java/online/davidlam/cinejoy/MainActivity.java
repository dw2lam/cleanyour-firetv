package online.davidlam.cinejoy;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Kiosk browser for cinejoy.pk on a Fire TV: one full-screen WebView, a D-pad driven mouse cursor, and a
 * main frame that can never leave the site (ad popups and redirects are dropped).
 */
public class MainActivity extends Activity {
    private static final String HOME = "https://cinejoy.pk/";
    /** The main frame may only show this host and its subdomains. */
    private static final String SITE = "cinejoy.pk";

    /**
     * Width in CSS px the page lays out at. A density-320 TV gives a default WebView 960 CSS px across
     * (1920 / 2); 1280 means 1.5 device px per CSS px. Applied by rewriting the page's viewport meta tag.
     */
    private static final int CSS_WIDTH = 1280;

    // Cursor feel. Distances are px on a 1080p screen (scaled to the actual height).
    private static final float STEP_PX = 12f;          // one short press
    private static final float MAX_STEP_PX = 45f;      // fully accelerated
    private static final float STEPS_PER_SEC = 30f;    // while held, the cursor glides at step * 30 px/s
    private static final long HOLD_DELAY_MS = 220;     // a press shorter than this moves exactly one step
    private static final long RAMP_MS = 1100;          // time from STEP_PX to MAX_STEP_PX
    private static final float EDGE_TAP_PX = 160f;     // scroll for one press against an edge
    private static final float EDGE_SPEED = 1.6f;      // held against an edge: scroll speed = cursor speed * this
    private static final float PAGE = 0.8f;            // Rewind / FastForward: fraction of the screen per press
    private static final long HIDE_MS = 3000;          // cursor hides after this long without a key press
    /** Also send mouse-hover moves, so hover styles and SvelteKit's preload-on-hover follow the cursor. */
    private static final boolean SEND_HOVER = true;
    private static final long NAV_REPEAT_MS = 140;     // Select mode: a held arrow moves focus this often
    private static final long MENU_LONG_MS = 650;      // Menu held this long reloads instead of switching modes
    private static final int SEEK_S = 10;              // player: Left / Right
    private static final int SKIP_S = 30;              // player: Rewind / Fast-forward
    private static final long PLAYING_POLL_MS = 20_000;  // how often to check for playback, to keep the screen on

    private static final String VIEWPORT_JS =
            "(function(c){var d=document;d.__cjVp=c;"
            + "function fix(){var h=d.head;if(!h)return;var m=h.querySelectorAll('meta[name=\"viewport\"]');"
            + "if(!m.length){var n=d.createElement('meta');n.name='viewport';h.appendChild(n);m=[n];}"
            + "for(var i=0;i<m.length;i++)if(m[i].content!==d.__cjVp)m[i].content=d.__cjVp;}"
            + "fix();"
            // Re-apply if the parser adds the page's own tag later, or a script rewrites it.
            + "if(!d.__cjObs&&d.documentElement){d.__cjObs=new MutationObserver(fix);"
            + "d.__cjObs.observe(d.head||d.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});}"
            + "})('%s');";

    private static final String ERROR_HTML = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><style>"
            + "html,body{margin:0;height:100%;background:#050505;color:#f2f2f2;font-family:sans-serif}"
            + "body{display:flex;align-items:center;justify-content:center;text-align:center}"
            + "i{display:block;width:14px;height:14px;margin:0 auto 30px;border-radius:50%;background:#95FF50;box-shadow:0 0 28px 4px rgba(149,255,80,.55)}"
            + "h1{margin:0 0 14px;font-size:34px;font-weight:700;letter-spacing:-.01em}"
            + "p{margin:0;font-size:20px;color:#9b9b9b}b{color:#95FF50}"
            + "</style></head><body><div><i></i><h1>Can’t reach cinejoy.pk</h1>"
            + "<p>Check the TV’s internet connection, then press <b>OK</b> to try again.</p></div></body></html>";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideCursor = this::hideCursor;
    private final Choreographer.FrameCallback frame = this::onFrame;

    private FrameLayout root;
    private WebView web;
    private FrameLayout fullscreen;      // hosts the HTML5 fullscreen video view, above the WebView
    private CursorView cursor;           // topmost
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private InputMethodManager imm;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    private float unit = 1f;             // screen size relative to 1080p
    private float vScrollPx, hScrollPx;  // px per wheel "tick" (how Chromium converts AXIS_*SCROLL)

    private boolean cursorShown;
    private boolean left, right, up, down;  // D-pad directions currently held
    private long holdStart;
    private long lastFrameNanos;
    private boolean framePosted;
    private boolean edgeHit;
    private int swallowKey = -1;         // key whose press only woke the cursor; ignored until released
    private boolean backDown;
    private boolean imeUp;
    private boolean errorShowing;
    private boolean clearHistoryOnLoad;
    private boolean resumed;

    // Select mode: arrows move a focus ring between the page's links and buttons (assets/nav.js) instead of the
    // cursor. Menu switches modes; the choice is kept. Fullscreen video always uses the cursor.
    private String navJs = "";
    private boolean selectMode;
    private SharedPreferences prefs;
    private TextView modePill;
    private final Runnable hideModePill = () -> modePill.animate().alpha(0f).setDuration(250).start();
    private TextView osd;                // player state (play/pause, time) at the bottom
    private final Runnable hideOsd = () -> osd.animate().alpha(0f).setDuration(300).start();
    private boolean videoPlaying;        // keeps the screen on; HTML fullscreen video does too
    private final Runnable pollPlaying = this::pollPlaying;
    private long lastNavAt;
    private long menuDownAt;
    private boolean menuLongDone;
    private String lastUrl = HOME;

    @Override
    @SuppressWarnings("deprecation")  // system-window insets: the only keyboard signal on Android 9
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        unit = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        ViewConfiguration vc = ViewConfiguration.get(this);
        vScrollPx = vc.getScaledVerticalScrollFactor() > 0 ? vc.getScaledVerticalScrollFactor() : 64 * dm.density;
        hScrollPx = vc.getScaledHorizontalScrollFactor() > 0 ? vc.getScaledHorizontalScrollFactor() : 64 * dm.density;
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050505);
        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(0xFF000000);
        fullscreen.setVisibility(View.GONE);
        cursor = new CursorView(this);
        cursor.setAlpha(0f);
        cursor.setVisibility(View.INVISIBLE);
        root.addView(fullscreen, match());
        root.addView(cursor, match());
        modePill = makeModePill(dm.density);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pillLp.topMargin = (int) (28 * dm.density);
        root.addView(modePill, pillLp);
        osd = makeModePill(dm.density);
        osd.setTextSize(20);
        FrameLayout.LayoutParams osdLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        osdLp.bottomMargin = (int) (120 * dm.density);   // above the site's own control bar
        root.addView(osd, osdLp);
        prefs = getSharedPreferences("kiosk", MODE_PRIVATE);
        selectMode = prefs.getBoolean("select_mode", true);
        navJs = readAsset("nav.js");
        // With adjustResize + the immersive layout flags nothing is resized; the keyboard just shows up
        // here as a bottom inset. Consume it so no view pads itself.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            imeUp = insets.getSystemWindowInsetBottom() > v.getHeight() * 0.15f;
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        // The stock decor layout pads itself for system-window insets (fitsSystemWindows), which would
        // shrink the page when the keyboard opens and hide the inset from the listener above. Turn it off.
        for (ViewParent p = root.getParent(); p instanceof View && p != getWindow().getDecorView(); p = p.getParent()) {
            ((View) p).setFitsSystemWindows(false);
        }
        hideSystemUi();

        createWebView();
        if (saved == null || web.restoreState(saved) == null) web.loadUrl(HOME);
        if (!selectMode) root.post(this::showCursor);  // show where the cursor starts, then let it fade
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @SuppressWarnings("deprecation")
    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(0xFF050505);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        // Low memory (1.5 GB TVs): let Android kill other things before this renderer while the app is in front.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);              // window.open / target=_blank load in place, then get filtered
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUseWideViewPort(true);                      // honour the viewport tag we write (width=1280)
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // Present as Chrome for Android rather than "a WebView": some embedded players refuse WebViews.
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")"));

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        root.addView(web, 0, match());
        web.requestFocus();
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation policy, viewport and errors

    private static boolean onSite(Uri u) {
        String scheme = u.getScheme(), host = u.getHost();
        if (host == null || !("https".equals(scheme) || "http".equals(scheme))) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(SITE) || host.endsWith("." + SITE);
    }

    private static boolean onSite(String url) {
        return url != null && onSite(Uri.parse(url));
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!request.isForMainFrame()) {
                // Player iframes may go anywhere on the web, but never hand a URL to another app.
                return !(scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                        || scheme.equals("data") || scheme.equals("blob"));
            }
            // Top frame: the site only (data:/blob: too). Everything else - popups, ad redirects,
            // off-site links - is cancelled silently. about:blank is deliberately not allowed: with
            // multiple windows off, window.open('about:blank') would blank the page.
            return !(onSite(u) || scheme.equals("data") || scheme.equals("blob"));
        }

        // Three chances to rewrite the viewport tag, from commit to load end; each is idempotent.
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (onSite(url) && !errorShowing) applyViewport();
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (onSite(url) && !errorShowing) applyViewport();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!onSite(url) || errorShowing) return;
            applyViewport();
            if (clearHistoryOnLoad) {  // recovered from the offline page: forget it
                clearHistoryOnLoad = false;
                view.clearHistory();
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (onSite(url)) lastUrl = url;  // also follows SvelteKit's pushState navigations
            // A new page: the old focus would otherwise stay on whatever was pressed to get here.
            view.evaluateJavascript("window.__cjNav && __cjNav.reset()", null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            // Only "can't reach the server" failures; an aborted or cancelled navigation is not one.
            int code = error.getErrorCode();
            String desc = String.valueOf(error.getDescription());
            if (code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT || code == ERROR_TIMEOUT || code == ERROR_IO
                    || code == ERROR_FAILED_SSL_HANDSHAKE || desc.contains("INTERNET_DISCONNECTED")
                    || desc.contains("ERR_NETWORK") || desc.contains("ADDRESS_UNREACHABLE")) {
                showError();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The renderer was killed (low memory on a 1.5 GB TV) or crashed. The WebView is dead: replace it
            // instead of letting the app crash.
            if (view != web) return true;
            exitFullscreen();
            root.removeView(web);
            web.destroy();
            createWebView();
            if (!resumed) web.onPause();  // died in the background: the new one stays quiet too
            web.loadUrl(lastUrl);
            return true;
        }
    }

    /** Makes the page lay out CSS_WIDTH px wide, scaled to fill the view exactly. */
    private void applyViewport() {
        float dipWidth = (web.getWidth() > 0 ? web.getWidth() : getResources().getDisplayMetrics().widthPixels)
                / getResources().getDisplayMetrics().density;
        float scale = dipWidth / CSS_WIDTH;
        String content = String.format(Locale.US,
                "width=%d, initial-scale=%.4f, minimum-scale=%.4f, maximum-scale=%.4f, user-scalable=no",
                CSS_WIDTH, scale, scale, scale);
        web.evaluateJavascript(String.format(Locale.US, VIEWPORT_JS, content), null);
    }

    private void showError() {
        errorShowing = true;
        clearHistoryOnLoad = true;
        web.stopLoading();
        web.loadDataWithBaseURL(null, ERROR_HTML, "text/html", "utf-8", null);
    }

    private void loadHome() {
        errorShowing = false;
        web.loadUrl(HOME);
    }

    // ---------------------------------------------------------------------------------------------
    // HTML5 fullscreen video

    private final class Chrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            fullscreen.addView(view, match());
            fullscreen.setVisibility(View.VISIBLE);
            updateKeepScreenOn();
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            // Black instead of the grey play-icon placeholder before a video's first frame.
            Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF000000);
            return b;
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        customView = null;
        updateKeepScreenOn();
        WebChromeClient.CustomViewCallback callback = customViewCallback;
        customViewCallback = null;
        if (callback != null) callback.onCustomViewHidden();  // tells the page it left fullscreen
        web.requestFocus();
    }

    // ---------------------------------------------------------------------------------------------
    // Remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean pressed = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = pressed && e.getRepeatCount() == 0;

        if (code == swallowKey) {
            if (!pressed) swallowKey = -1;
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                // Act on release, and only for a press we saw: if the keyboard ate the press to close
                // itself, its release must not also navigate back.
                if (first) backDown = true;
                else if (!pressed && backDown) { backDown = false; onBack(); }
                return true;
            case KeyEvent.KEYCODE_MENU:
                // Press: switch between Select and Cursor. Hold: reload (the offline page: retry).
                if (first) {
                    menuDownAt = SystemClock.uptimeMillis();
                    menuLongDone = false;
                } else if (pressed && !menuLongDone && SystemClock.uptimeMillis() - menuDownAt >= MENU_LONG_MS) {
                    menuLongDone = true;
                    if (errorShowing) loadHome(); else web.reload();
                } else if (!pressed && !menuLongDone) {
                    if (errorShowing) loadHome(); else setSelectMode(!selectMode);
                }
                return true;
            // Media keys drive the page's video directly in both modes; without one, Space and page scrolling.
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                // No Space-key fallback: Space presses whatever button has the page's focus, which re-opened
                // the title that was just played.
                if (first) callNav("media('toggle')", this::showResult);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (pressed) skip(-1, first);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (pressed) skip(1, first);
                return true;
        }

        boolean dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!dpad && !ok) return super.dispatchKeyEvent(e);
        // A keyboard's Enter in a focused text field submits it (the remote's OK is DPAD_CENTER).
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && imm.isAcceptingText()) return super.dispatchKeyEvent(e);

        // Typing: the keyboard (or the focused field) gets the arrows and OK, not the cursor. The Fire TV
        // keyboard normally consumes them before they get here; this covers the ones it lets through.
        if (textMode()) {
            stopHold();
            return super.dispatchKeyEvent(e);
        }

        // The first arrow press after the cursor hid only brings it back. OK always taps where the cursor is, so it
        // pauses a playing video (whose controls hid the cursor) in one press.
        if (selectMode && customView == null && !errorShowing) {
            if (dpad) {
                long now = SystemClock.uptimeMillis();
                if (pressed && (first || now - lastNavAt >= NAV_REPEAT_MS)) {
                    lastNavAt = now;
                    nav(code == KeyEvent.KEYCODE_DPAD_UP ? "up" : code == KeyEvent.KEYCODE_DPAD_DOWN ? "down"
                            : code == KeyEvent.KEYCODE_DPAD_LEFT ? "left" : "right");
                }
            } else if (first) {
                selectTap();
            }
            return true;
        }

        if (first && !cursorShown && !ok) {
            showCursor();
            swallowKey = code;
            return true;
        }
        if (pressed) showCursor();  // keeps it visible while keys are used

        if (ok) {
            if (first) {
                if (errorShowing) loadHome(); else tap();
            }
            return true;
        }
        if (first) startHold(code);
        else if (!pressed) endHold(code);
        return true;  // auto-repeats are ignored: the frame loop moves the cursor while a key is held
    }

    // ---------------------------------------------------------------------------------------------
    // Select mode

    /**
     * Calls __cjNav (assets/nav.js) and hands its result object to then (null when it returned none). nav.js is
     * injected only when the document doesn't have it yet, so a key press costs a one-line call, not a re-parse.
     */
    private void callNav(String call, Consumer<JSONObject> then) {
        web.evaluateJavascript("window.__cjNav ? __cjNav." + call + " : 'NOJS'", v -> {
            if ("\"NOJS\"".equals(v)) {
                web.evaluateJavascript(navJs + ";__cjNav." + call, v2 -> then.accept(parse(v2)));
            } else {
                then.accept(parse(v));
            }
        });
    }

    private static JSONObject parse(String json) {
        if (json == null || !json.startsWith("{")) return null;
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
    }

    private void nav(String dir) {
        float k = cssPerPx();
        callNav(String.format(Locale.US, "move('%s',%.1f,%.1f)", dir, cursor.cursorX() * k, cursor.cursorY() * k), r -> {
            if (r == null) return;
            String act = r.optString("act");
            if (act.equals("reveal")) {
                // The site draws its player controls on mouse movement: hover mid-screen, then Up/Down focuses them.
                cursor.moveTo(root.getWidth() / 2f, root.getHeight() / 2f);
                hover(MotionEvent.ACTION_HOVER_MOVE);
                showOsd(r.optString("osd"));
            } else if (act.equals("osd")) {
                showOsd(r.optString("osd"));
            } else if (act.equals("none") && (dir.equals("up") || dir.equals("down"))) {
                pageScroll(dir.equals("up") ? -1 : 1, true);   // nothing further that way: scroll to find more
            }
        });
    }

    /** OK in Select mode: a real tap at the focused element's centre (it also reaches player iframes), or play/pause. */
    private void selectTap() {
        float k = cssPerPx();
        callNav(String.format(Locale.US, "ok(%.1f,%.1f)", cursor.cursorX() * k, cursor.cursorY() * k), r -> {
            if (r == null || showResult(r) || !r.optString("act").equals("tap")) return;
            float scale = web.getWidth() / (float) r.optDouble("w", CSS_WIDTH);   // device px per CSS px
            cursor.moveTo((float) r.optDouble("x") * scale, (float) r.optDouble("y") * scale);
            tap();
        });
    }

    /** Rewind / Fast-forward: skip in the video, or page up / down where there is none. */
    private void skip(int dir, boolean first) {
        long now = SystemClock.uptimeMillis();
        if (!first && now - lastNavAt < NAV_REPEAT_MS) return;
        lastNavAt = now;
        callNav("media('seek'," + dir * SKIP_S + ")", r -> {
            if (!showResult(r)) pageScroll(dir, first);
        });
    }

    /** Shows a player result's text; false when the result was not a player one. */
    private boolean showResult(JSONObject r) {
        if (r == null || !r.optString("act").equals("osd")) return false;
        showOsd(r.optString("osd"));
        return true;
    }

    private void showOsd(String text) {
        if (text == null || text.isEmpty()) return;
        osd.setText(text);
        osd.animate().cancel();
        osd.setAlpha(1f);
        ui.removeCallbacks(hideOsd);
        ui.postDelayed(hideOsd, 2200);
        if (!videoPlaying) pollPlayingSoon();
    }

    // ---- screen on while a video plays ----------------------------------------------------------

    // The site plays video inside the page (not HTML fullscreen), so nothing would stop the screensaver mid-film.
    private void pollPlaying() {
        if (!resumed) return;
        web.evaluateJavascript("window.__cjNav ? __cjNav.playing() : false", v -> {
            videoPlaying = "true".equals(v);
            updateKeepScreenOn();
        });
        ui.postDelayed(pollPlaying, PLAYING_POLL_MS);
    }

    private void pollPlayingSoon() {
        ui.removeCallbacks(pollPlaying);
        ui.postDelayed(pollPlaying, 1500);
    }

    private void updateKeepScreenOn() {
        if (videoPlaying || customView != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** CSS px of the page per device px, for handing the cursor's spot to nav.js. */
    private float cssPerPx() {
        return web.getWidth() > 0 ? (float) CSS_WIDTH / web.getWidth() : 1f;
    }

    private void setSelectMode(boolean on) {
        selectMode = on;
        prefs.edit().putBoolean("select_mode", on).apply();
        if (on) {
            hideCursor();
            callNav("show()", r -> { });
        } else {
            callNav("hide()", r -> { });
            showCursor();
        }
        modePill.setText(on ? "Select  \u00B7  Menu for cursor" : "Cursor  \u00B7  Menu to select");
        modePill.animate().cancel();
        modePill.setAlpha(1f);
        ui.removeCallbacks(hideModePill);
        ui.postDelayed(hideModePill, 1600);
    }

    private TextView makeModePill(float density) {
        TextView t = new TextView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0141414);
        bg.setCornerRadius(999);
        bg.setStroke((int) Math.max(1, density), 0x3395FF50);
        t.setBackground(bg);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding((int) (20 * density), (int) (9 * density), (int) (20 * density), (int) (9 * density));
        t.setAlpha(0f);
        return t;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private boolean textMode() {
        return imeUp && customView == null && imm.isAcceptingText();
    }

    private void onBack() {
        if (customView != null) {
            exitFullscreen();
        } else if (textMode()) {
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        } else if (errorShowing) {
            finish();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cursor

    private void showCursor() {
        ui.removeCallbacks(hideCursor);
        ui.postDelayed(hideCursor, HIDE_MS);
        if (cursorShown) return;
        cursorShown = true;
        cursor.animate().cancel();
        cursor.setVisibility(View.VISIBLE);
        cursor.animate().alpha(1f).setDuration(120).start();
        hover(MotionEvent.ACTION_HOVER_MOVE);
    }

    private void hideCursor() {
        ui.removeCallbacks(hideCursor);
        if (!cursorShown) return;
        cursorShown = false;
        stopHold();
        hover(MotionEvent.ACTION_HOVER_EXIT);  // drop hover effects (e.g. player controls) with the cursor
        cursor.animate().cancel();
        cursor.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            if (!cursorShown) cursor.setVisibility(View.INVISIBLE);
        }).start();
    }

    private void startHold(int code) {
        setHeld(code, true);
        holdStart = SystemClock.uptimeMillis();
        edgeHit = false;
        lastFrameNanos = 0;
        int dx = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0;
        step(dx * STEP_PX * unit, dy * STEP_PX * unit, true);
        if (!framePosted) {
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(frame);
        }
    }

    private void endHold(int code) {
        setHeld(code, false);
    }

    private void stopHold() {
        left = right = up = down = false;
    }

    private void setHeld(int code, boolean held) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: left = held; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right = held; break;
            case KeyEvent.KEYCODE_DPAD_UP: up = held; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down = held; break;
        }
    }

    /** While a direction is held: glide, accelerating from STEP_PX to MAX_STEP_PX per 1/30 s. */
    private void onFrame(long frameTimeNanos) {
        int dx = (right ? 1 : 0) - (left ? 1 : 0);
        int dy = (down ? 1 : 0) - (up ? 1 : 0);
        if (!(left || right || up || down)) {
            framePosted = false;
            return;
        }
        float dt = lastFrameNanos == 0 ? 0 : Math.min((frameTimeNanos - lastFrameNanos) / 1e9f, 0.05f);
        lastFrameNanos = frameTimeNanos;
        long held = SystemClock.uptimeMillis() - holdStart;
        if (held > HOLD_DELAY_MS && dt > 0) {
            float ramp = Math.min(1f, (held - HOLD_DELAY_MS) / (float) RAMP_MS);
            float speed = (STEP_PX + (MAX_STEP_PX - STEP_PX) * ramp) * STEPS_PER_SEC * unit;
            step(dx * speed * dt, dy * speed * dt, false);
        }
        Choreographer.getInstance().postFrameCallback(frame);
    }

    /**
     * Moves the cursor, clamped to the screen. Whatever the edge stops becomes a scroll of the thing under
     * the cursor: for a single press only when the cursor already sat on that edge.
     */
    private void step(float dx, float dy, boolean single) {
        float w = root.getWidth() - 1, h = root.getHeight() - 1;
        if (w <= 0 || h <= 0) return;
        float x = cursor.cursorX(), y = cursor.cursorY();
        float nx = Math.max(0, Math.min(w, x + dx)), ny = Math.max(0, Math.min(h, y + dy));
        float sx, sy;
        if (single) {
            sx = dx != 0 && nx == x ? Math.signum(dx) * EDGE_TAP_PX * unit : 0;
            sy = dy != 0 && ny == y ? Math.signum(dy) * EDGE_TAP_PX * unit : 0;
        } else {
            sx = (x + dx - nx) * EDGE_SPEED;
            sy = (y + dy - ny) * EDGE_SPEED;
            if ((sx != 0 || sy != 0) && !edgeHit) {
                // Reached the edge mid-glide: restart the acceleration, so scrolling starts gently.
                edgeHit = true;
                holdStart = SystemClock.uptimeMillis() - HOLD_DELAY_MS;
            }
        }
        if (nx != x || ny != y) {
            cursor.moveTo(nx, ny);
            hover(MotionEvent.ACTION_HOVER_MOVE);
        }
        if (sx != 0 || sy != 0) scroll(sx, sy);
    }

    private void pageScroll(int dir, boolean first) {
        showCursor();
        float h = root.getHeight() > 0 ? root.getHeight() : 1080 * unit;
        scroll(0, dir * h * (first ? PAGE : PAGE / 3));  // held: keep paging, a bit gentler
    }

    // ---------------------------------------------------------------------------------------------
    // Synthetic input, dispatched into the layout at the cursor so it reaches whatever is drawn there,
    // cross-origin player iframes and the fullscreen video view included. The cursor view itself is not
    // clickable, so the events fall through it.

    /** OK: a finger tap (down + up, same downTime) at the cursor. */
    private void tap() {
        long t = SystemClock.uptimeMillis();
        dispatch(pointer(MotionEvent.ACTION_DOWN, t, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 0, 0), true);
        dispatch(pointer(MotionEvent.ACTION_UP, t, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 0, 0), true);
        cursor.pulse();
    }

    /**
     * Mouse-wheel scroll at the cursor, so the scroller under it moves (inner lists, carousels, iframes),
     * not just the page. dx/dy are px; positive = reveal content to the right / below.
     */
    private void scroll(float dx, float dy) {
        long t = SystemClock.uptimeMillis();
        dispatch(pointer(MotionEvent.ACTION_SCROLL, t, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE,
                dx / hScrollPx, -dy / vScrollPx), false);  // AXIS_VSCROLL > 0 means scroll up
    }

    private void hover(int action) {
        if (!SEND_HOVER || root.getWidth() == 0) return;
        long t = SystemClock.uptimeMillis();
        dispatch(pointer(action, t, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, 0, 0), false);
    }

    private MotionEvent pointer(int action, long downTime, int source, int toolType, float hScroll, float vScroll) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = toolType;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = cursor.cursorX();
        c.y = cursor.cursorY();
        c.pressure = 1f;
        c.size = 1f;
        if (hScroll != 0) c.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        if (vScroll != 0) c.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[] {p}, new MotionEvent.PointerCoords[] {c},
                0, 0, 1f, 1f, 0, 0, source, 0);
    }

    private void dispatch(MotionEvent e, boolean touch) {
        if (touch) root.dispatchTouchEvent(e); else root.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi(); else stopHold();  // a key release can get lost with the focus
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.resumeTimers();
        web.onResume();
        ui.removeCallbacks(pollPlaying);
        ui.post(pollPlaying);
        hideSystemUi();
        // Back online while the offline page is up: retry by itself. Fires once at once if a network exists.
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                ui.post(() -> { if (errorShowing) loadHome(); });
            }
        };
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            networkCallback = null;  // too many callbacks or a firmware quirk: retry stays on OK
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopHold();
        ui.removeCallbacks(hideCursor);
        ui.removeCallbacks(pollPlaying);
        if (networkCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkCallback = null;
        }
        web.onPause();
        web.pauseTimers();  // process-wide: no JS timers, layout or ads running in the background
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
