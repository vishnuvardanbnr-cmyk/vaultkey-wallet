package app.vaultkey.wallet;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(name = "DAppBrowser")
public class DAppBrowserPlugin extends Plugin {
    private static final String TAG = "DAppBrowserPlugin";
    private WebView webView;
    private FrameLayout container;
    private String trustProviderScript = "";
    private String currentAddress = "";
    private int currentChainId = 1;
    private String rpcUrl = "https://eth.llamarpc.com";
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Map<Integer, Long> pendingCallbacks = new HashMap<>();

    @Override
    public void load() {
        super.load();
        loadTrustProvider();
    }

    private void loadTrustProvider() {
        try {
            AssetManager assets = getContext().getAssets();
            InputStream is = assets.open("trust-provider.js");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            trustProviderScript = sb.toString();
            Log.d(TAG, "Trust provider loaded: " + trustProviderScript.length() + " bytes");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load trust-provider.js", e);
        }
    }

    @PluginMethod
    public void open(PluginCall call) {
        String url = call.getString("url", "");
        currentAddress = call.getString("address", "");
        currentChainId = call.getInt("chainId", 1);
        rpcUrl = getRpcUrl(currentChainId);
        
        if (url.isEmpty()) {
            call.reject("URL is required");
            return;
        }

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

    private String getRpcUrl(int chainId) {
        switch (chainId) {
            case 1: return "https://eth.llamarpc.com";
            case 56: return "https://bsc-dataseed.binance.org";
            case 137: return "https://polygon-rpc.com";
            case 43114: return "https://api.avax.network/ext/bc/C/rpc";
            case 42161: return "https://arb1.arbitrum.io/rpc";
            case 10: return "https://mainnet.optimism.io";
            case 8453: return "https://mainnet.base.org";
            default: return "https://eth.llamarpc.com";
        }
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
            pendingCallbacks.clear();
            
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void updateAccount(PluginCall call) {
        currentAddress = call.getString("address", currentAddress);
        currentChainId = call.getInt("chainId", currentChainId);
        rpcUrl = getRpcUrl(currentChainId);
        
        if (webView != null) {
            String hexChainId = "0x" + Integer.toHexString(currentChainId);
            String updateScript = String.format(
                "(function() {" +
                "  if (window.ethereum) {" +
                "    window.ethereum.address = '%s';" +
                "    window.ethereum.chainId = '%s';" +
                "    if (window.ethereum.emit) {" +
                "      window.ethereum.emit('accountsChanged', ['%s']);" +
                "      window.ethereum.emit('chainChanged', '%s');" +
                "    }" +
                "  }" +
                "})();",
                currentAddress, hexChainId, currentAddress, hexChainId
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
                "if (window.trustCallbacks && window.trustCallbacks[%d]) {" +
                "  window.trustCallbacks[%d].reject(new Error('%s'));" +
                "  delete window.trustCallbacks[%d];" +
                "}" +
                "window.dispatchEvent(new CustomEvent('vaultkey_response', { detail: { id: %d, error: { message: '%s' } } }));",
                id, id, error.replace("'", "\\'"), id, id, error.replace("'", "\\'")
            );
        } else {
            script = String.format(
                "if (window.trustCallbacks && window.trustCallbacks[%d]) {" +
                "  window.trustCallbacks[%d].resolve(%s);" +
                "  delete window.trustCallbacks[%d];" +
                "}" +
                "window.dispatchEvent(new CustomEvent('vaultkey_response', { detail: { id: %d, result: %s } }));",
                id, id, result, id, id, result
            );
        }
        
        if (webView != null) {
            mainHandler.post(() -> {
                webView.evaluateJavascript(script, null);
            });
        }
        pendingCallbacks.remove(id);
        
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
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", "") + " Trust/Android");

        webView.addJavascriptInterface(new TrustBridge(), "trust");
        webView.addJavascriptInterface(new VaultKeyBridge(), "VaultKeyBridge");

        String fullInjectionScript = generateFullInjectionScript();
        
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            HashSet<String> origins = new HashSet<>();
            origins.add("*");
            WebViewCompat.addDocumentStartJavaScript(webView, fullInjectionScript, origins);
            Log.d(TAG, "Using document-start script injection");
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(fullInjectionScript, null);
                
                JSObject event = new JSObject();
                event.put("url", url);
                event.put("loading", true);
                notifyListeners("browserEvent", event);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(fullInjectionScript, null);
                view.evaluateJavascript("setTimeout(function(){" + generateEIP6963Script() + "},100);", null);
                
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

    private String generateFullInjectionScript() {
        String hexChainId = "0x" + Integer.toHexString(currentChainId);
        
        return String.format(
            "(function() {" +
            "  if (window._vaultKeyInjected) return;" +
            "  window._vaultKeyInjected = true;" +
            "  window.trustCallbacks = {};" +
            "  var _callbackId = 1;" +
            "  " +
            "  var config = {" +
            "    ethereum: {" +
            "      address: '%s'," +
            "      chainId: %d," +
            "      rpcUrl: '%s'" +
            "    }" +
            "  };" +
            "  " +
            "  function TrustProvider() {" +
            "    this.isMetaMask = true;" +
            "    this.isTrust = true;" +
            "    this.isTrustWallet = true;" +
            "    this.isVaultKey = true;" +
            "    this.address = config.ethereum.address;" +
            "    this.chainId = '%s';" +
            "    this.networkVersion = '%d';" +
            "    this.selectedAddress = config.ethereum.address;" +
            "    this._events = {};" +
            "    this._metamask = {" +
            "      isUnlocked: function() { return Promise.resolve(true); }," +
            "      requestBatch: function() { return Promise.resolve([]); }" +
            "    };" +
            "    this.providers = [this];" +
            "  }" +
            "  " +
            "  TrustProvider.prototype.isConnected = function() { return true; };" +
            "  " +
            "  TrustProvider.prototype.on = function(event, callback) {" +
            "    if (!this._events[event]) this._events[event] = [];" +
            "    this._events[event].push(callback);" +
            "    return this;" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.once = function(event, callback) {" +
            "    var self = this;" +
            "    var wrapped = function() {" +
            "      self.removeListener(event, wrapped);" +
            "      callback.apply(this, arguments);" +
            "    };" +
            "    return this.on(event, wrapped);" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.off = function(event, callback) {" +
            "    return this.removeListener(event, callback);" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.removeListener = function(event, callback) {" +
            "    if (this._events[event]) {" +
            "      this._events[event] = this._events[event].filter(function(cb) { return cb !== callback; });" +
            "    }" +
            "    return this;" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.removeAllListeners = function(event) {" +
            "    if (event) { this._events[event] = []; } else { this._events = {}; }" +
            "    return this;" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.emit = function(event) {" +
            "    var args = Array.prototype.slice.call(arguments, 1);" +
            "    if (this._events[event]) {" +
            "      this._events[event].forEach(function(cb) {" +
            "        try { cb.apply(null, args); } catch(e) { console.error('[VaultKey] emit error:', e); }" +
            "      });" +
            "    }" +
            "    return true;" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.request = function(args) {" +
            "    var self = this;" +
            "    var method = args.method;" +
            "    var params = args.params || [];" +
            "    console.log('[VaultKey] request:', method, JSON.stringify(params));" +
            "    " +
            "    if (method === 'eth_accounts' || method === 'eth_requestAccounts') {" +
            "      self.emit('connect', { chainId: self.chainId });" +
            "      return Promise.resolve([self.address]);" +
            "    }" +
            "    if (method === 'eth_chainId') return Promise.resolve(self.chainId);" +
            "    if (method === 'net_version') return Promise.resolve(self.networkVersion);" +
            "    if (method === 'eth_coinbase') return Promise.resolve(self.address);" +
            "    if (method === 'wallet_requestPermissions') {" +
            "      return Promise.resolve([{ parentCapability: 'eth_accounts' }]);" +
            "    }" +
            "    if (method === 'wallet_getPermissions') {" +
            "      return Promise.resolve([{ parentCapability: 'eth_accounts' }]);" +
            "    }" +
            "    if (method === 'wallet_switchEthereumChain') {" +
            "      var chainId = params[0] && params[0].chainId;" +
            "      if (chainId) {" +
            "        self.chainId = chainId;" +
            "        self.networkVersion = parseInt(chainId, 16).toString();" +
            "        self.emit('chainChanged', chainId);" +
            "      }" +
            "      return Promise.resolve(null);" +
            "    }" +
            "    if (method === 'wallet_addEthereumChain') return Promise.resolve(null);" +
            "    if (method === 'wallet_watchAsset') return Promise.resolve(true);" +
            "    " +
            "    return new Promise(function(resolve, reject) {" +
            "      var id = _callbackId++;" +
            "      window.trustCallbacks[id] = { resolve: resolve, reject: reject, method: method };" +
            "      " +
            "      try {" +
            "        trust.signMessage(id, method, JSON.stringify(params));" +
            "      } catch(e) {" +
            "        console.error('[VaultKey] bridge error:', e);" +
            "        delete window.trustCallbacks[id];" +
            "        reject(e);" +
            "      }" +
            "      " +
            "      setTimeout(function() {" +
            "        if (window.trustCallbacks[id]) {" +
            "          delete window.trustCallbacks[id];" +
            "          reject(new Error('Request timeout'));" +
            "        }" +
            "      }, 120000);" +
            "    });" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.enable = function() {" +
            "    return this.request({ method: 'eth_requestAccounts' });" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.send = function(methodOrPayload, paramsOrCallback) {" +
            "    if (typeof methodOrPayload === 'string') {" +
            "      return this.request({ method: methodOrPayload, params: paramsOrCallback });" +
            "    }" +
            "    if (typeof paramsOrCallback === 'function') {" +
            "      this.request({ method: methodOrPayload.method, params: methodOrPayload.params })" +
            "        .then(function(r) { paramsOrCallback(null, { id: methodOrPayload.id, jsonrpc: '2.0', result: r }); })" +
            "        .catch(function(e) { paramsOrCallback(e); });" +
            "      return;" +
            "    }" +
            "    return this.request({ method: methodOrPayload.method, params: methodOrPayload.params });" +
            "  };" +
            "  " +
            "  TrustProvider.prototype.sendAsync = function(payload, callback) {" +
            "    this.request({ method: payload.method, params: payload.params })" +
            "      .then(function(r) { callback(null, { id: payload.id, jsonrpc: '2.0', result: r }); })" +
            "      .catch(function(e) { callback(e); });" +
            "  };" +
            "  " +
            "  var provider = new TrustProvider();" +
            "  " +
            "  try { delete window.ethereum; } catch(e) {}" +
            "  try { delete window.web3; } catch(e) {}" +
            "  " +
            "  Object.defineProperty(window, 'ethereum', {" +
            "    value: provider," +
            "    writable: false," +
            "    configurable: true," +
            "    enumerable: true" +
            "  });" +
            "  " +
            "  window.trustwallet = { ethereum: provider, provider: provider };" +
            "  window.web3 = { currentProvider: provider, eth: { accounts: [provider.address] } };" +
            "  " +
            generateEIP6963Script() +
            "  " +
            "  window.dispatchEvent(new Event('ethereum#initialized'));" +
            "  console.log('[VaultKey] Provider injected - address:', provider.address, 'chainId:', provider.chainId);" +
            "})();",
            currentAddress, currentChainId, rpcUrl, hexChainId, currentChainId
        );
    }

    private String generateEIP6963Script() {
        return 
            "  (function() {" +
            "    try {" +
            "      var info = {" +
            "        uuid: 'vaultkey-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9)," +
            "        name: 'VaultKey'," +
            "        icon: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCA0MCA0MCI+PHJlY3QgZmlsbD0iIzNiODJmNiIgd2lkdGg9IjQwIiBoZWlnaHQ9IjQwIiByeD0iOCIvPjxwYXRoIGZpbGw9IndoaXRlIiBkPSJNMjAgOGw4IDZ2MTJsLTggNi04LTZWMTR6Ii8+PC9zdmc+'," +
            "        rdns: 'app.vaultkey.wallet'" +
            "      };" +
            "      var detail = Object.freeze({ info: Object.freeze(info), provider: window.ethereum });" +
            "      window.dispatchEvent(new CustomEvent('eip6963:announceProvider', { detail: detail }));" +
            "      window.addEventListener('eip6963:requestProvider', function() {" +
            "        window.dispatchEvent(new CustomEvent('eip6963:announceProvider', { detail: detail }));" +
            "      });" +
            "    } catch(e) { console.log('[VaultKey] EIP-6963 error:', e); }" +
            "  })();";
    }

    private class TrustBridge {
        @JavascriptInterface
        public void signMessage(int id, String method, String paramsJson) {
            Log.d(TAG, "TrustBridge.signMessage: id=" + id + ", method=" + method);
            pendingCallbacks.put(id, System.currentTimeMillis());
            
            try {
                JSObject event = new JSObject();
                event.put("id", id);
                event.put("method", method);
                event.put("params", paramsJson);
                notifyListeners("web3Request", event);
            } catch (Exception e) {
                Log.e(TAG, "Error in signMessage", e);
            }
        }

        @JavascriptInterface
        public void signTransaction(int id, String to, String value, int nonce, String gasLimit, String gasPrice, String data) {
            Log.d(TAG, "TrustBridge.signTransaction: id=" + id);
            pendingCallbacks.put(id, System.currentTimeMillis());
            
            try {
                org.json.JSONObject tx = new org.json.JSONObject();
                tx.put("to", to);
                tx.put("value", value);
                tx.put("nonce", nonce);
                tx.put("gasLimit", gasLimit);
                tx.put("gasPrice", gasPrice);
                tx.put("data", data);
                
                JSObject event = new JSObject();
                event.put("id", id);
                event.put("method", "eth_signTransaction");
                event.put("params", "[" + tx.toString() + "]");
                notifyListeners("web3Request", event);
            } catch (Exception e) {
                Log.e(TAG, "Error in signTransaction", e);
            }
        }

        @JavascriptInterface
        public void signPersonalMessage(int id, String data) {
            Log.d(TAG, "TrustBridge.signPersonalMessage: id=" + id);
            pendingCallbacks.put(id, System.currentTimeMillis());
            
            try {
                JSObject event = new JSObject();
                event.put("id", id);
                event.put("method", "personal_sign");
                event.put("params", "[\"" + data + "\", \"" + currentAddress + "\"]");
                notifyListeners("web3Request", event);
            } catch (Exception e) {
                Log.e(TAG, "Error in signPersonalMessage", e);
            }
        }

        @JavascriptInterface
        public void signTypedMessage(int id, String data) {
            Log.d(TAG, "TrustBridge.signTypedMessage: id=" + id);
            pendingCallbacks.put(id, System.currentTimeMillis());
            
            try {
                JSObject event = new JSObject();
                event.put("id", id);
                event.put("method", "eth_signTypedData_v4");
                event.put("params", "[\"" + currentAddress + "\", " + data + "]");
                notifyListeners("web3Request", event);
            } catch (Exception e) {
                Log.e(TAG, "Error in signTypedMessage", e);
            }
        }
    }

    private class VaultKeyBridge {
        @JavascriptInterface
        public void postMessage(String message) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(message);
                int id = json.getInt("id");
                String method = json.getString("method");
                String params = json.optString("params", "[]");
                
                pendingCallbacks.put(id, System.currentTimeMillis());
                
                JSObject event = new JSObject();
                event.put("id", id);
                event.put("method", method);
                event.put("params", params);
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
        pendingCallbacks.clear();
        super.handleOnDestroy();
    }
}
