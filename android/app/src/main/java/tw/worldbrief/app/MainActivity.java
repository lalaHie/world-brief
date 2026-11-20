package tw.worldbrief.app;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.MimeTypeMap;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.graphics.Color;
import android.os.Build;
import android.view.WindowInsets;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String APP_URL = "https://lalahie.github.io/world-brief/app/";
    private static final String APP_PATH = "/world-brief/app/";
    public static final String EXTRA_STORY_URL = "tw.worldbrief.app.STORY_URL";
    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(245, 240, 231));
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets.consumeSystemWindowInsets();
        });
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"lalahie.github.io".equals(uri.getHost())) return super.shouldInterceptRequest(view, request);
                String path = uri.getPath();
                if (path == null || !path.startsWith(APP_PATH) || path.contains("..")) return super.shouldInterceptRequest(view, request);
                String relative = path.substring(APP_PATH.length());
                String assetPath = "app/" + (relative.isEmpty() ? "index.html" : relative);
                try {
                    InputStream stream = getAssets().open(assetPath);
                    String extension = MimeTypeMap.getFileExtensionFromUrl(assetPath).toLowerCase(Locale.ROOT);
                    String mime = "svg".equals(extension) ? "image/svg+xml" : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if ("webmanifest".equals(extension)) mime = "application/manifest+json";
                    if (mime == null) mime = "application/octet-stream";
                    return new WebResourceResponse(mime, "UTF-8", stream);
                } catch (IOException error) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(root);
        loadApp(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_STORY_URL));
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadApp(intent.getStringExtra(EXTRA_STORY_URL));
    }

    private void loadApp(String storyUrl) {
        String url = APP_URL + "?native=1";
        if (storyUrl != null && !storyUrl.isEmpty()) {
            url += "&story=" + Uri.encode(storyUrl);
        }
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
