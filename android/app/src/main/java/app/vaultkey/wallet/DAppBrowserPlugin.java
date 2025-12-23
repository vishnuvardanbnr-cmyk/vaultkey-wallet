package app.vaultkey.wallet;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayInputStream;
import java.util.HashSet;

@CapacitorPlugin(name = "DAppBrowser")
public class DAppBrowserPlugin extends Plugin {
    private static final String TAG = "DAppBrowserPlugin";
    private WebView webView;
    private FrameLayout container;
    private String injectionScript = "";
    private String currentAddress = "";
    private int currentChainId = 1;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @PluginMethod
    public void open(PluginCall call) {
        String url = call.getString("url", "");
        currentAddress = call.getString("address", "");
        currentChainId = call.getInt("chainId", 1);
        
        if (url.isEmpty()) {
            call.reject("URL is required");
            return;
        }

        injectionScript = generateInjectionScript(currentAddress, currentChainId);

        mainHandler.post(() -> {
            try {
                createWebView(url);
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
            } catch (Exception e) {
                Log.e(TAG, "Error opening browser", e);
                call.reject("Failed to open browser: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void close(PluginCall call) {
        mainHandler.post(() -> {
            if (container != null && webView != null) {
                container.removeView(webView);
                webView.destroy();
                webView = null;
                
                ViewGroup parent = (ViewGroup) container.getParent();
                if (parent != null) {
                    parent.removeView(container);
                }
                container = null;
            }
            
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void updateAccount(PluginCall call) {
        currentAddress = call.getString("address", currentAddress);
        currentChainId = call.getInt("chainId", currentChainId);
        
        if (webView != null) {
            String updateScript = String.format(
                "if (window.ethereum && window.ethereum.isVaultKey) { " +
                "  window.ethereum.selectedAddress = '%s'; " +
                "  window.ethereum.chainId = '0x%x'; " +
                "  window.ethereum.networkVersion = '%d'; " +
                "  window.ethereum.emit('accountsChanged', ['%s']); " +
                "  window.ethereum.emit('chainChanged', '0x%x'); " +
                "}",
                currentAddress, currentChainId, currentChainId, currentAddress, currentChainId
            );
            
            mainHandler.post(() -> {
                webView.evaluateJavascript(updateScript, null);
            });
        }
        
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void sendResponse(PluginCall call) {
        int id = call.getInt("id", 0);
        String result = call.getString("result", "null");
        String error = call.getString("error", "");
        
        String script;
        if (!error.isEmpty()) {
            script = String.format(
                "window.dispatchEvent(new CustomEvent('vaultkey_response', { detail: { id: %d, error: { message: '%s' } } }));",
                id, error.replace("'", "\\'")
            );
        } else {
            script = String.format(
                "window.dispatchEvent(new CustomEvent('vaultkey_response', { detail: { id: %d, result: %s } }));",
                id, result
            );
        }
        
        if (webView != null) {
            mainHandler.post(() -> {
                webView.evaluateJavascript(script, null);
            });
        }
        
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView(String url) {
        Activity activity = getActivity();
        if (activity == null) return;

        int headerHeightPx = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 56, activity.getResources().getDisplayMetrics());
        int footerHeightPx = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 72, activity.getResources().getDisplayMetrics());

        container = new FrameLayout(activity);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        containerParams.setMargins(0, headerHeightPx, 0, footerHeightPx);
        container.setLayoutParams(containerParams);

        webView = new WebView(activity);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " VaultKeyWallet");

        webView.addJavascriptInterface(new WebAppInterface(), "VaultKeyBridge");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            HashSet<String> origins = new HashSet<>();
            origins.add("*");
            WebViewCompat.addDocumentStartJavaScript(webView, injectionScript, origins);
            Log.d(TAG, "Using document-start script injection");
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(injectionScript, null);
                
                JSObject event = new JSObject();
                event.put("url", url);
                event.put("loading", true);
                notifyListeners("browserEvent", event);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(injectionScript, null);
                
                JSObject event = new JSObject();
                event.put("url", url);
                event.put("loading", false);
                notifyListeners("browserEvent", event);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(reqUrl));
                    activity.startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Cannot open URL: " + reqUrl);
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        container.addView(webView);
        
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        rootView.addView(container);

        webView.loadUrl(url);
    }

    private String generateInjectionScript(String address, int chainId) {
        return String.format(
            "(function() {" +
            "  if (window.ethereum && window.ethereum.isVaultKey) return;" +
            "  " +
            "  const currentAddress = '%s';" +
            "  let currentChainId = %d;" +
            "  let requestId = 0;" +
            "  const pendingRequests = {};" +
            "  " +
            "  class VaultKeyProvider {" +
            "    constructor() {" +
            "      this.isVaultKey = true;" +
            "      this.isMetaMask = true;" +
            "      this.isTrust = true;" +
            "      this.isTrustWallet = true;" +
            "      this.isCoinbaseWallet = false;" +
            "      this.isConnected = () => true;" +
            "      this.chainId = '0x' + currentChainId.toString(16);" +
            "      this.networkVersion = currentChainId.toString();" +
            "      this.selectedAddress = currentAddress;" +
            "      this._events = {};" +
            "      this._metamask = { isUnlocked: () => Promise.resolve(true) };" +
            "    }" +
            "    " +
            "    on(event, callback) {" +
            "      if (!this._events[event]) this._events[event] = [];" +
            "      this._events[event].push(callback);" +
            "      return this;" +
            "    }" +
            "    " +
            "    removeListener(event, callback) {" +
            "      if (this._events[event]) {" +
            "        this._events[event] = this._events[event].filter(cb => cb !== callback);" +
            "      }" +
            "      return this;" +
            "    }" +
            "    " +
            "    removeAllListeners(event) {" +
            "      if (event) { this._events[event] = []; } else { this._events = {}; }" +
            "      return this;" +
            "    }" +
            "    " +
            "    emit(event, ...args) {" +
            "      if (this._events[event]) {" +
            "        this._events[event].forEach(cb => { try { cb(...args); } catch(e) {} });" +
            "      }" +
            "    }" +
            "    " +
            "    async request({ method, params }) {" +
            "      const id = ++requestId;" +
            "      console.log('[VaultKey] Request:', method, params);" +
            "      " +
            "      if (method === 'eth_requestAccounts' || method === 'eth_accounts') {" +
            "        console.log('[VaultKey] Returning accounts:', [currentAddress]);" +
            "        this.emit('connect', { chainId: this.chainId });" +
            "        return [currentAddress];" +
            "      }" +
            "      if (method === 'eth_chainId') {" +
            "        return this.chainId;" +
            "      }" +
            "      if (method === 'net_version') {" +
            "        return this.networkVersion;" +
            "      }" +
            "      if (method === 'wallet_switchEthereumChain') {" +
            "        const reqChainId = params && params[0] && params[0].chainId;" +
            "        if (reqChainId) {" +
            "          currentChainId = parseInt(reqChainId, 16);" +
            "          this.chainId = reqChainId;" +
            "          this.networkVersion = currentChainId.toString();" +
            "          this.emit('chainChanged', reqChainId);" +
            "        }" +
            "        return null;" +
            "      }" +
            "      if (method === 'wallet_addEthereumChain') {" +
            "        return null;" +
            "      }" +
            "      if (method === 'eth_coinbase') {" +
            "        return currentAddress;" +
            "      }" +
            "      " +
            "      return new Promise((resolve, reject) => {" +
            "        pendingRequests[id] = { resolve, reject, method };" +
            "        " +
            "        window.VaultKeyBridge.postMessage(JSON.stringify({" +
            "          id: id," +
            "          method: method," +
            "          params: params || []" +
            "        }));" +
            "        " +
            "        setTimeout(() => {" +
            "          if (pendingRequests[id]) {" +
            "            delete pendingRequests[id];" +
            "            reject(new Error('Request timed out'));" +
            "          }" +
            "        }, 120000);" +
            "      });" +
            "    }" +
            "    " +
            "    async enable() {" +
            "      return this.request({ method: 'eth_requestAccounts' });" +
            "    }" +
            "    " +
            "    send(methodOrPayload, paramsOrCallback) {" +
            "      if (typeof methodOrPayload === 'string') {" +
            "        return this.request({ method: methodOrPayload, params: paramsOrCallback });" +
            "      }" +
            "      if (typeof paramsOrCallback === 'function') {" +
            "        this.request({ method: methodOrPayload.method, params: methodOrPayload.params })" +
            "          .then(result => paramsOrCallback(null, { result }))" +
            "          .catch(error => paramsOrCallback(error));" +
            "        return;" +
            "      }" +
            "      return this.request({ method: methodOrPayload.method, params: methodOrPayload.params });" +
            "    }" +
            "    " +
            "    sendAsync(payload, callback) {" +
            "      this.request({ method: payload.method, params: payload.params })" +
            "        .then(result => callback(null, { id: payload.id, jsonrpc: '2.0', result }))" +
            "        .catch(error => callback(error));" +
            "    }" +
            "  }" +
            "  " +
            "  const provider = new VaultKeyProvider();" +
            "  " +
            "  // Delete any existing ethereum object first" +
            "  try { delete window.ethereum; } catch(e) {}" +
            "  try { delete window.trustwallet; } catch(e) {}" +
            "  try { delete window.web3; } catch(e) {}" +
            "  " +
            "  provider.providers = [provider];" +
            "  " +
            "  Object.defineProperty(window, 'ethereum', {" +
            "    value: provider," +
            "    writable: false," +
            "    configurable: false," +
            "    enumerable: true" +
            "  });" +
            "  " +
            "  window.ethereum.providers = [provider];" +
            "  " +
            "  Object.defineProperty(window, 'trustwallet', {" +
            "    value: { ethereum: provider, solana: null, provider: provider }," +
            "    writable: false," +
            "    configurable: false," +
            "    enumerable: true" +
            "  });" +
            "  " +
            "  Object.defineProperty(window, 'web3', {" +
            "    value: { currentProvider: provider, eth: { accounts: [currentAddress] } }," +
            "    writable: false," +
            "    configurable: false," +
            "    enumerable: true" +
            "  });" +
            "  " +
            "  if (typeof window.coinbaseWalletExtension === 'undefined') {" +
            "    window.coinbaseWalletExtension = provider;" +
            "  }" +
            "  " +
            "  window.addEventListener('vaultkey_response', function(e) {" +
            "    const { id, result, error } = e.detail;" +
            "    console.log('[VaultKey] Response for id:', id, 'result:', result, 'error:', error);" +
            "    if (pendingRequests[id]) {" +
            "      if (error) {" +
            "        pendingRequests[id].reject(new Error(error.message || error));" +
            "      } else {" +
            "        pendingRequests[id].resolve(result);" +
            "      }" +
            "      delete pendingRequests[id];" +
            "    }" +
            "  });" +
            "  " +
            "  const announceEIP6963 = function() {" +
            "    try {" +
            "      const info = {" +
            "        uuid: 'vaultkey-wallet-' + Date.now()," +
            "        name: 'VaultKey'," +
            "        icon: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACgAAAAoCAYAAACM/rhtAAAABGdBTUEAALGPC/xhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAAAhGVYSWZNTQAqAAAACAAFARIAAwAAAAEAAQAAARoABQAAAAEAAABKARsABQAAAAEAAABSASgAAwAAAAEAAgAAh2kABAAAAAEAAABaAAAAAAAAAEgAAAABAAAASAAAAAEAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAKKADAAQAAAABAAAAKAAAAADzLqNFAAAACXBIWXMAAAsTAAALEwEAmpwYAAABWWlUWHRYTUw6Y29tLmFkb2JlLnhtcAAAAAAAPHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyIgeDp4bXB0az0iWE1QIENvcmUgNi4wLjAiPgogICA8cmRmOlJERiB4bWxuczpyZGY9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkvMDIvMjItcmRmLXN5bnRheC1ucyMiPgogICAgICA8cmRmOkRlc2NyaXB0aW9uIHJkZjphYm91dD0iIgogICAgICAgICAgICB4bWxuczp0aWZmPSJodHRwOi8vbnMuYWRvYmUuY29tL3RpZmYvMS4wLyI+CiAgICAgICAgIDx0aWZmOk9yaWVudGF0aW9uPjE8L3RpZmY6T3JpZW50YXRpb24+CiAgICAgIDwvcmRmOkRlc2NyaXB0aW9uPgogICA8L3JkZjpSREY+CjwveDp4bXBtZXRhPgoZXuEHAAABqElEQVRYCe2YsU7DMBCGnUJXxMrIY/AILKy8CYI34A1gYmFhZWRFLMAjgFSJGTqAWn/Ff8p1OTs+O1WQ/kh1fL47/3ecOG4cx5EA/3cCg3/TH+b/rIQABCAAAQhAAAIQgAAEIAABCEBgTwT6+xIe5WVn/7zMH2bmOy0/nOfP8k/nBbW8f1x/mj/K0p1OuUx/kj/LX51nl/le/lZ/lr8qr2rl/bP6k/y1eM/D7s/yz/J1R7uW94/qT/I3B/Vcfz6qP8nXXexY3j+pP8lfHNJz/Pmofi1fd7Bjef+k/iR/cUjP8eej+pP8xSE9x5+P6k/y9Qc7lveP6k/yF4f0HH8+ql/L1x3sWN4/qT/J35xXc/35qH4tX3ewY3n/pH4tf3VIz/Hno/q1fN3BjuX9k/q1/NUhPcefj+rX8nUHO5b3T+rX8leH9Bx/PqpfyzcdaD/eP6lfy98c1HP8+ah+LV93sGN5/6R+LX91SM/x56P6tXzdwY7l/ZP6tfzVIT3Hn4/q1/J1BzuW90/q1/JXh/Qcfz6qX8vXHexY3j+pX8v/A/ADmpE='," +
            "        rdns: 'app.vaultkey.wallet'" +
            "      };" +
            "      const detail = Object.freeze({ info: Object.freeze(info), provider: provider });" +
            "      window.dispatchEvent(new CustomEvent('eip6963:announceProvider', { detail }));" +
            "    } catch(e) { console.log('[VaultKey] EIP-6963 error:', e); }" +
            "  };" +
            "  " +
            "  window.addEventListener('eip6963:requestProvider', function() {" +
            "    announceEIP6963();" +
            "  });" +
            "  " +
            "  setTimeout(function() {" +
            "    window.dispatchEvent(new Event('ethereum#initialized'));" +
            "    announceEIP6963();" +
            "    if (window.ethereum._events && window.ethereum._events['connect']) {" +
            "      window.ethereum.emit('connect', { chainId: window.ethereum.chainId });" +
            "    }" +
            "  }, 1);" +
            "  " +
            "  // Re-announce periodically in case DApp loads late" +
            "  setTimeout(announceEIP6963, 100);" +
            "  setTimeout(announceEIP6963, 500);" +
            "  setTimeout(announceEIP6963, 1000);" +
            "  console.log('[VaultKey] Web3 provider injected with EIP-6963 - isMetaMask:', provider.isMetaMask, 'isTrust:', provider.isTrust);" +
            "})();",
            address, chainId
        );
    }

    private class WebAppInterface {
        @JavascriptInterface
        public void postMessage(String message) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(message);
                int id = json.getInt("id");
                String method = json.getString("method");
                org.json.JSONArray params = json.optJSONArray("params");
                
                JSObject event = new JSObject();
                event.put("id", id);
                event.put("method", method);
                event.put("params", params != null ? params.toString() : "[]");
                notifyListeners("web3Request", event);
                
            } catch (Exception e) {
                Log.e(TAG, "Error parsing message", e);
            }
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.handleOnDestroy();
    }
}
