package com.streamimdb.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

public class MainActivity extends Activity {
  private static final String HOME = "https://streamimdb.ru/";
  private FrameLayout root;
  private WebView web;
  private Cursor cursor;
  private HorizontalScrollView toolbar;
  private ProgressBar progress;
  private TextView instruction;
  private View videoView;
  private WebChromeClient.CustomViewCallback videoCallback;
  private boolean menuOpen = false;
  private boolean pointerMode = true;
  private boolean menuHeld = false;
  private Button pointerToggle;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private FrameLayout openingLayer;
  private VideoView openingPlayer;

  /** Local, two-second silent Khyam TV startup logo. */
  private void playOpeningLogo() {
    if (openingLayer != null) return;
    final FrameLayout layer = new FrameLayout(this);
    layer.setBackgroundColor(Color.BLACK);
    final VideoView player = new VideoView(this);
    layer.addView(player, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
    openingLayer = layer;
    openingPlayer = player;

    player.setOnPreparedListener(media -> {
      media.setVolume(0f, 0f); // Silence even if source ever contains an audio track.
      media.setLooping(false);
      player.start();
    });
    player.setOnCompletionListener(media -> dismissOpeningLogo());
    player.setOnErrorListener((media, what, extra) -> {
      dismissOpeningLogo(); // Never block the main app on decoder errors.
      return true;
    });
    player.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.khyam_intro));
    handler.postDelayed(this::dismissOpeningLogo, 4500); // Decoder/device fallback.
  }

  private void dismissOpeningLogo() {
    if (openingLayer == null) return;
    FrameLayout oldLayer = openingLayer;
    VideoView oldPlayer = openingPlayer;
    openingLayer = null;
    openingPlayer = null;
    if (oldPlayer != null) {
      oldPlayer.setOnCompletionListener(null);
      oldPlayer.setOnErrorListener(null);
      oldPlayer.stopPlayback();
    }
    root.removeView(oldLayer);
    if (web != null) web.requestFocus();
  }


  @SuppressLint("SetJavaScriptEnabled")
  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setImmersive();
    pointerMode = getPreferences(MODE_PRIVATE).getBoolean("pointer", true);
    root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    setContentView(root);
    web = new WebView(this);
    web.setFocusable(true);
    web.setFocusableInTouchMode(true);
    web.setBackgroundColor(Color.BLACK);
    root.addView(web, new FrameLayout.LayoutParams(-1, -1));
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setUseWideViewPort(true);
    s.setLoadWithOverviewMode(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setSupportZoom(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setSupportMultipleWindows(false);
    s.setJavaScriptCanOpenWindowsAutomatically(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    root.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
        Uri u = req.getUrl();
        String scheme = u.getScheme();
        if ("https".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme)) return false;
        Toast.makeText(MainActivity.this, "الرابط غير مدعوم", Toast.LENGTH_SHORT).show();
        return true;
      }
      @Override public void onPageFinished(WebView v, String url) { progress.setVisibility(View.GONE); }
      @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
        if (req.isForMainFrame()) { progress.setVisibility(View.GONE); toast("تعذر فتح الصفحة. حاول التحديث."); }
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onProgressChanged(WebView v, int percent) {
        progress.setProgress(percent);
        progress.setVisibility(percent >= 100 ? View.GONE : View.VISIBLE);
      }
      @Override public void onShowCustomView(View view, CustomViewCallback callback) {
        if (videoView != null) { callback.onCustomViewHidden(); return; }
        videoView = view;
        videoCallback = callback;
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        web.setVisibility(View.GONE);
        cursor.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);
        instruction.setVisibility(View.GONE);
      }
      @Override public void onHideCustomView() { exitVideo(); }
      @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
    });
    web.setDownloadListener((url, ua, disposition, mime, bytes) -> toast("التنزيل من الموقع غير مدعوم داخل التطبيق"));
    cursor = new Cursor();
    cursor.setFocusable(false);
    cursor.setClickable(false);
    root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
    buildToolbar();
    instruction = new TextView(this);
    instruction.setText("الأسهم: المؤشر   •   OK: اختيار   •   ضغط مطول OK أو MENU: الأدوات");
    instruction.setTextColor(Color.WHITE);
    instruction.setTextSize(13);
    instruction.setGravity(Gravity.CENTER);
    instruction.setBackgroundColor(0xDD111827);
    root.addView(instruction, new FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM));
    handler.postDelayed(() -> instruction.setVisibility(View.GONE), 9000);
    web.loadUrl(HOME);
    playOpeningLogo();
    web.requestFocus();
    cursor.setVisibility(pointerMode ? View.VISIBLE : View.GONE);
  }

  private void buildToolbar() {
    toolbar = new HorizontalScrollView(this);
    toolbar.setHorizontalScrollBarEnabled(false);
    toolbar.setBackgroundColor(0xF3182236);
    LinearLayout list = new LinearLayout(this);
    list.setGravity(Gravity.CENTER_VERTICAL);
    list.setPadding(dp(7), dp(6), dp(7), dp(6));
    toolbar.addView(list, new HorizontalScrollView.LayoutParams(-2, -1));
    addButton(list, "الرئيسية", () -> go(HOME));
    addButton(list, "الأفلام", () -> go(HOME + "movies"));
    addButton(list, "المسلسلات", () -> go(HOME + "tv-shows"));
    addButton(list, "البحث", () -> go(HOME + "search"));
    addButton(list, "رجوع", () -> { if (web.canGoBack()) web.goBack(); toggleMenu(false); });
    addButton(list, "تحديث", () -> { web.reload(); toggleMenu(false); });
    addButton(list, "+ تكبير", () -> { web.zoomIn(); toggleMenu(false); });
    addButton(list, "- تصغير", () -> { web.zoomOut(); toggleMenu(false); });
    pointerToggle = addButton(list, pointerMode ? "المؤشر: تشغيل" : "المؤشر: إيقاف", this::changePointer);
    addButton(list, "مساعدة", this::help);
    addButton(list, "إغلاق", () -> toggleMenu(false));
    root.addView(toolbar, new FrameLayout.LayoutParams(-1, dp(62), Gravity.TOP));
    toolbar.setVisibility(View.GONE);
  }

  private Button addButton(LinearLayout parent, String title, Runnable action) {
    Button b = new Button(this);
    b.setText(title);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setFocusable(true);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xFF263B55);
    bg.setCornerRadius(dp(10));
    b.setBackground(bg);
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(48));
    layout.setMargins(dp(4), 0, dp(4), 0);
    parent.addView(b, layout);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void go(String url) { web.loadUrl(url); toggleMenu(false); }

  private void toggleMenu(boolean show) {
    if (videoView != null) return;
    menuOpen = show;
    toolbar.setVisibility(show ? View.VISIBLE : View.GONE);
    cursor.setVisibility(show || !pointerMode ? View.GONE : View.VISIBLE);
    instruction.setVisibility(View.GONE);
    if (show) ((LinearLayout) toolbar.getChildAt(0)).getChildAt(0).requestFocus();
    else web.requestFocus();
  }

  private void changePointer() {
    pointerMode = !pointerMode;
    getPreferences(MODE_PRIVATE).edit().putBoolean("pointer", pointerMode).apply();
    pointerToggle.setText(pointerMode ? "المؤشر: تشغيل" : "المؤشر: إيقاف");
    toggleMenu(false);
    toast(pointerMode ? "تم تفعيل المؤشر" : "تم تفعيل التنقل بين الروابط");
  }

  private void help() {
    toggleMenu(false);
    new AlertDialog.Builder(this).setTitle("StreamIMDB TV")
      .setMessage("الأسهم: تحريك المؤشر\nOK: اختيار\nضغط مطول على OK: القائمة\nMENU: القائمة\nINFO: تبديل وضع المؤشر\nBACK: رجوع\n\nقد تتطلب بعض المشغلات متصفحاً خارجياً ولا تعمل في WebView.")
      .setPositiveButton("موافق", (d, which) -> web.requestFocus()).show();
  }

  private static boolean isArrow(int k) {
    return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
      || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
  }
  private static boolean isSelect(int k) {
    return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
      || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
  }
  @Override public boolean dispatchKeyEvent(KeyEvent ev) {
    int k = ev.getKeyCode();
    if (ev.getAction() == KeyEvent.ACTION_DOWN && videoView == null) {
      if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_GUIDE || k == KeyEvent.KEYCODE_SETTINGS) {
        toggleMenu(!menuOpen); return true;
      }
      if (k == KeyEvent.KEYCODE_INFO) { changePointer(); return true; }
    }
    if (pointerMode && !menuOpen && videoView == null) {
      if (isSelect(k)) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
          if ((ev.isLongPress() || ev.getRepeatCount() > 7) && !menuHeld) {
            menuHeld = true; toggleMenu(true);
          }
          return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_UP) {
          if (!menuHeld) tap(cursor.x, cursor.y);
          menuHeld = false;
          return true;
        }
      }
      if (isArrow(k)) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
          float step = dp(ev.getRepeatCount() > 6 ? 45 : 23);
          if (k == KeyEvent.KEYCODE_DPAD_LEFT) cursor.move(-step, 0);
          if (k == KeyEvent.KEYCODE_DPAD_RIGHT) cursor.move(step, 0);
          if (k == KeyEvent.KEYCODE_DPAD_UP) cursor.move(0, -step);
          if (k == KeyEvent.KEYCODE_DPAD_DOWN) cursor.move(0, step);
        }
        return true;
      }
    }
    return super.dispatchKeyEvent(ev);
  }

  private void tap(float x, float y) {
    long now = android.os.SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
    down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    web.dispatchTouchEvent(down); down.recycle();
    MotionEvent up = MotionEvent.obtain(now, now + 85, MotionEvent.ACTION_UP, x, y, 0);
    up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    web.dispatchTouchEvent(up); up.recycle();
  }

  private void exitVideo() {
    if (videoView == null) return;
    root.removeView(videoView);
    videoView = null;
    if (videoCallback != null) {
      videoCallback.onCustomViewHidden();
      videoCallback = null;
    }
    web.setVisibility(View.VISIBLE);
    cursor.setVisibility(pointerMode ? View.VISIBLE : View.GONE);
    web.requestFocus();
    setImmersive();
  }

  @Override public void onBackPressed() {
    if (openingLayer != null) { dismissOpeningLogo(); return; }
    if (videoView != null) { exitVideo(); return; }
    if (menuOpen) { toggleMenu(false); return; }
    if (web.canGoBack()) { web.goBack(); return; }
    super.onBackPressed();
  }
  @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
  @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); setImmersive(); }
  @Override protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    dismissOpeningLogo();
    if (videoView != null) exitVideo();
    root.removeView(web);
    web.stopLoading();
    web.destroy();
    super.onDestroy();
  }
  private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
  private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
  private void setImmersive() {
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
      | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  private final class Cursor extends View {
    float x, y;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Cursor() { super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
      super.onSizeChanged(w, h, oldw, oldh);
      x = w / 2f; y = h / 2f;
    }
    void move(float dx, float dy) {
      x = Math.max(dp(15), Math.min(getWidth() - dp(15), x + dx));
      if (dy > 0 && y + dy >= getHeight() - dp(16)) {
        web.pageDown(false); y = getHeight() - dp(70);
      } else if (dy < 0 && y + dy <= dp(16)) {
        web.pageUp(false); y = dp(70);
      } else {
        y = Math.max(dp(15), Math.min(getHeight() - dp(15), y + dy));
      }
      invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(Color.WHITE);
      paint.setShadowLayer(dp(5), 0, dp(2), Color.BLACK);
      canvas.drawCircle(x, y, dp(11), paint);
      paint.clearShadowLayer();
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(3));
      paint.setColor(0xFF2563EB);
      canvas.drawCircle(x, y, dp(11), paint);
      canvas.drawLine(x - dp(20), y, x - dp(14), y, paint);
      canvas.drawLine(x + dp(14), y, x + dp(20), y, paint);
      canvas.drawLine(x, y - dp(20), x, y - dp(14), paint);
      canvas.drawLine(x, y + dp(14), x, y + dp(20), paint);
    }
  }
}
