package tw.worldbrief.app;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ViewGroup;

public final class MainActivity extends Activity {
    private static final String APP_URL = "https://lalahie.github.io/world-brief/app/";
    public static final String EXTRA_STORY_URL = "tw.worldbrief.app.STORY_URL";
    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        loadApp(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_STORY_URL));
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadApp(intent.getStringExtra(EXTRA_STORY_URL));
    }

    private void loadApp(String storyUrl) {
        String url = APP_URL;
        if (storyUrl != null && !storyUrl.isEmpty()) {
            url += "?story=" + Uri.encode(storyUrl);
        }
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
