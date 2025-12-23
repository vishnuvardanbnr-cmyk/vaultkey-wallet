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
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

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

        container = new FrameLayout(activity);
        container.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

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
            "  const currentChainId = %d;" +
            "  let requestId = 0;" +
            "  const pendingRequests = {};" +
            "  " +
            "  class VaultKeyProvider {" +
            "    constructor() {" +
            "      this.isVaultKey = true;" +
            "      this.isMetaMask = true;" +
            "      this.isTrust = true;" +
            "      this.isConnected = () => true;" +
            "      this.chainId = '0x' + currentChainId.toString(16);" +
            "      this.networkVersion = currentChainId.toString();" +
            "      this.selectedAddress = currentAddress;" +
            "      this._events = {};" +
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
            "    emit(event, ...args) {" +
            "      if (this._events[event]) {" +
            "        this._events[event].forEach(cb => cb(...args));" +
            "      }" +
            "    }" +
            "    " +
            "    async request({ method, params }) {" +
            "      const id = ++requestId;" +
            "      " +
            "      if (method === 'eth_requestAccounts' || method === 'eth_accounts') {" +
            "        return [currentAddress];" +
            "      }" +
            "      if (method === 'eth_chainId') {" +
            "        return '0x' + currentChainId.toString(16);" +
            "      }" +
            "      if (method === 'net_version') {" +
            "        return currentChainId.toString();" +
            "      }" +
            "      if (method === 'wallet_switchEthereumChain') {" +
            "        return null;" +
            "      }" +
            "      " +
            "      return new Promise((resolve, reject) => {" +
            "        pendingRequests[id] = { resolve, reject };" +
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
            "        }, 60000);" +
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
            "  Object.defineProperty(window, 'ethereum', {" +
            "    value: provider," +
            "    writable: false," +
            "    configurable: true" +
            "  });" +
            "  " +
            "  window.addEventListener('vaultkey_response', function(e) {" +
            "    const { id, result, error } = e.detail;" +
            "    if (pendingRequests[id]) {" +
            "      if (error) {" +
            "        pendingRequests[id].reject(new Error(error.message));" +
            "      } else {" +
            "        pendingRequests[id].resolve(result);" +
            "      }" +
            "      delete pendingRequests[id];" +
            "    }" +
            "  });" +
            "  " +
            "  window.dispatchEvent(new Event('ethereum#initialized'));" +
            "  console.log('[VaultKey] Web3 provider injected for mobile');" +
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
