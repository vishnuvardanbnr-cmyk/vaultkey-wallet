package app.vaultkey.wallet;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.HashSet;

@CapacitorPlugin(name = "DAppBrowser")
public class DAppBrowserPlugin extends Plugin {
    private static final String TAG = "DAppBrowserPlugin";
    private WebView webView;
    private FrameLayout container;
    private ProgressBar progressBar;
    private String currentAddress = "";
    private int currentChainId = 1;
    private String rpcUrl = "https://eth.llamarpc.com";
    private Handler mainHandler = new Handler(Looper.getMainLooper());

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
            case 56: return "https://bsc-dataseed1.binance.org";
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
            try {
                destroyWebView();
            } catch (Exception e) {
                Log.e(TAG, "Error closing browser", e);
            }
            
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        });
    }

    private void destroyWebView() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            if (container != null) {
                container.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        
        if (container != null) {
            ViewGroup parent = (ViewGroup) container.getParent();
            if (parent != null) {
                parent.removeView(container);
            }
            container = null;
        }
        progressBar = null;
    }

    @PluginMethod
    public void updateAccount(PluginCall call) {
        currentAddress = call.getString("address", currentAddress);
        currentChainId = call.getInt("chainId", currentChainId);
        rpcUrl = getRpcUrl(currentChainId);
        
        if (webView != null) {
            String hexChainId = "0x" + Integer.toHexString(currentChainId);
            // Call the exposed __vkUpdate function to update closure state
            String updateScript = 
                "(function(){" +
                "if(window.__vkUpdate){" +
                "window.__vkUpdate('" + currentAddress + "','" + hexChainId + "','" + rpcUrl + "');" +
                "}else if(window.ethereum){" +
                "window.ethereum.selectedAddress='" + currentAddress + "';" +
                "window.ethereum.chainId='" + hexChainId + "';" +
                "window.ethereum._rpcUrl='" + rpcUrl + "';" +
                "if(window.ethereum.emit){" +
                "window.ethereum.emit('accountsChanged',['" + currentAddress + "']);" +
                "window.ethereum.emit('chainChanged','" + hexChainId + "');" +
                "}}})();";
            
            mainHandler.post(() -> {
                try {
                    webView.evaluateJavascript(updateScript, null);
                } catch (Exception e) {
                    Log.e(TAG, "Error updating account", e);
                }
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
            String escapedError = error.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
            script = "(function(){" +
                "if(window._vkCallbacks&&window._vkCallbacks[" + id + "]){" +
                "window._vkCallbacks[" + id + "].reject(new Error('" + escapedError + "'));" +
                "delete window._vkCallbacks[" + id + "];" +
                "}})();";
        } else {
            script = "(function(){" +
                "if(window._vkCallbacks&&window._vkCallbacks[" + id + "]){" +
                "window._vkCallbacks[" + id + "].resolve(" + result + ");" +
                "delete window._vkCallbacks[" + id + "];" +
                "}})();";
        }
        
        if (webView != null) {
            mainHandler.post(() -> {
                try {
                    webView.evaluateJavascript(script, null);
                } catch (Exception e) {
                    Log.e(TAG, "Error sending response", e);
                }
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

        destroyWebView();

        float density = activity.getResources().getDisplayMetrics().density;
        int headerHeightPx = (int) (100 * density);
        int footerHeightPx = (int) (80 * density);

        container = new FrameLayout(activity);
        container.setBackgroundColor(Color.parseColor("#f5f5f5"));
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        containerParams.setMargins(0, headerHeightPx, 0, footerHeightPx);
        container.setLayoutParams(containerParams);

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (int) (3 * density)
        ));
        progressBar.setIndeterminate(true);
        container.addView(progressBar);

        webView = new WebView(activity);
        webView.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams webViewParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        webViewParams.setMargins(0, (int)(3 * density), 0, 0);
        webView.setLayoutParams(webViewParams);

        configureWebSettings(webView);
        webView.addJavascriptInterface(new WalletBridge(), "VaultKeyNative");

        String injectionScript = buildInjectionScript();
        
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                HashSet<String> origins = new HashSet<>();
                origins.add("*");
                WebViewCompat.addDocumentStartJavaScript(webView, injectionScript, origins);
            } catch (Exception e) {
                Log.e(TAG, "Document-start injection failed", e);
            }
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                try {
                    view.evaluateJavascript(injectionScript, null);
                } catch (Exception e) {
                    Log.e(TAG, "Injection error", e);
                }
                
                JSObject event = new JSObject();
                event.put("url", url);
                event.put("loading", true);
                notifyListeners("browserEvent", event);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                try {
                    // Only inject if not already injected - the script has its own guard
                    // Do NOT re-dispatch ethereum#initialized as it resets React app focus
                    view.evaluateJavascript(injectionScript, null);
                } catch (Exception e) {
                    Log.e(TAG, "Injection error", e);
                }
                
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
                    Log.e(TAG, "Cannot open: " + reqUrl);
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    if (newProgress >= 100) {
                        progressBar.setVisibility(View.GONE);
                    }
                }
            }
            
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView.HitTestResult result = view.getHitTestResult();
                String url = result.getExtra();
                if (url != null) {
                    view.loadUrl(url);
                }
                return false;
            }
        });

        // Don't force hardware layer type - it breaks IME/input on Android 13+
        // Let WebView use default compositing

        container.addView(webView);
        
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        rootView.addView(container);

        webView.loadUrl(url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        
        // Core JavaScript settings
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        
        // Cache settings for better SPA performance
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        // Viewport settings - critical for React apps
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        
        // Text and input settings - important for search fields
        settings.setTextZoom(100);
        settings.setDefaultTextEncodingName("UTF-8");
        
        // Window and popup settings
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        
        // File and content access
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setBlockNetworkLoads(false);
        
        // Media settings
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        // Geolocation (some DApps need it)
        settings.setGeolocationEnabled(true);
        
        // Cookie settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(webView, true);
            cookieManager.flush();
        }
        
        // Disable force dark to prevent CSS issues
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            try {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF);
            } catch (Exception e) {
                Log.e(TAG, "Force dark error", e);
            }
        }
        
        // Trust Wallet compatible user agent
        String ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 Trust/Android";
        settings.setUserAgentString(ua);
        
        // Enable web debugging in debug builds
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    private String buildInjectionScript() {
        String hexChainId = "0x" + Integer.toHexString(currentChainId);
        
        // Comprehensive Trust Wallet-style provider injection
        // Key improvements:
        // 1. Non-intrusive injection that doesn't break React apps
        // 2. Proper EventEmitter implementation
        // 3. Direct RPC calls with dynamic URL switching
        // 4. Full EIP-1193 + EIP-6963 compliance
        return "(function(){" +
            "'use strict';" +
            "if(window._vkInjected)return;" +
            "window._vkInjected=true;" +
            
            // State variables
            "var _id=1;" +
            "var _addr='" + currentAddress + "';" +
            "var _chainId='" + hexChainId + "';" +
            "var _netVersion='" + currentChainId + "';" +
            "var _connected=true;" +
            "var _callbacks={};" +
            "var _listeners={};" +
            
            // RPC URLs map
            "var _rpcs={" +
            "1:'https://eth.llamarpc.com'," +
            "56:'https://bsc-dataseed1.binance.org'," +
            "137:'https://polygon-rpc.com'," +
            "43114:'https://api.avax.network/ext/bc/C/rpc'," +
            "42161:'https://arb1.arbitrum.io/rpc'," +
            "10:'https://mainnet.optimism.io'," +
            "8453:'https://mainnet.base.org'" +
            "};" +
            "var _rpcUrl=_rpcs[" + currentChainId + "]||'https://eth.llamarpc.com';" +
            
            // Store callbacks globally for native bridge
            "window._vkCallbacks=_callbacks;" +
            
            // RPC call helper with retry
            "function rpc(method,params){" +
            "return fetch(_rpcUrl,{" +
            "method:'POST'," +
            "headers:{'Content-Type':'application/json','Accept':'application/json'}," +
            "body:JSON.stringify({jsonrpc:'2.0',id:Date.now(),method:method,params:params||[]})" +
            "}).then(function(r){" +
            "if(!r.ok)throw new Error('Network error');" +
            "return r.json();" +
            "}).then(function(d){" +
            "if(d.error)throw new Error(d.error.message||'RPC error');" +
            "return d.result;" +
            "});" +
            "}" +
            
            // Event emitter methods
            "function on(event,fn){" +
            "if(!_listeners[event])_listeners[event]=[];" +
            "_listeners[event].push(fn);" +
            "return provider;" +
            "}" +
            
            "function off(event,fn){" +
            "if(_listeners[event]){" +
            "_listeners[event]=_listeners[event].filter(function(f){return f!==fn;});" +
            "}" +
            "return provider;" +
            "}" +
            
            "function emit(event){" +
            "var args=Array.prototype.slice.call(arguments,1);" +
            "if(_listeners[event]){" +
            "_listeners[event].slice().forEach(function(fn){" +
            "try{fn.apply(null,args);}catch(e){console.error('[VK]',e);}" +
            "});" +
            "}" +
            "return true;" +
            "}" +
            
            // Bridge to native for signing operations
            "function bridge(method,params){" +
            "return new Promise(function(resolve,reject){" +
            "var id=_id++;" +
            "_callbacks[id]={resolve:resolve,reject:reject};" +
            "try{" +
            "VaultKeyNative.postMessage(JSON.stringify({id:id,method:method,params:params}));" +
            "}catch(e){" +
            "delete _callbacks[id];" +
            "reject(new Error('Native bridge unavailable'));" +
            "}" +
            "setTimeout(function(){" +
            "if(_callbacks[id]){" +
            "delete _callbacks[id];" +
            "reject(new Error('Request timeout'));" +
            "}" +
            "},120000);" +
            "});" +
            "}" +
            
            // RPC methods that can be handled directly
            "var directRpc=['eth_blockNumber','eth_getBlockByNumber','eth_getBlockByHash'," +
            "'eth_call','eth_getBalance','eth_getCode','eth_getStorageAt'," +
            "'eth_getTransactionCount','eth_getTransactionByHash','eth_getTransactionReceipt'," +
            "'eth_getLogs','eth_estimateGas','eth_gasPrice','eth_feeHistory'," +
            "'eth_maxPriorityFeePerGas','eth_getBlockTransactionCountByHash'," +
            "'eth_getBlockTransactionCountByNumber','eth_getUncleCountByBlockHash'," +
            "'eth_getUncleCountByBlockNumber','eth_protocolVersion','eth_syncing'," +
            "'net_listening','net_peerCount','web3_clientVersion','web3_sha3'," +
            "'eth_createAccessList','eth_getProof'];" +
            
            // Main request handler
            "function request(args){" +
            "var method=args.method;" +
            "var params=args.params||[];" +
            
            // Account methods
            "if(method==='eth_accounts'){" +
            "return Promise.resolve(_addr?[_addr]:[]);" +
            "}" +
            
            "if(method==='eth_requestAccounts'){" +
            "if(_addr){" +
            "emit('connect',{chainId:_chainId});" +
            "return Promise.resolve([_addr]);" +
            "}" +
            "return bridge(method,params);" +
            "}" +
            
            // Chain methods
            "if(method==='eth_chainId'){" +
            "return Promise.resolve(_chainId);" +
            "}" +
            
            "if(method==='net_version'){" +
            "return Promise.resolve(_netVersion);" +
            "}" +
            
            "if(method==='eth_coinbase'){" +
            "return Promise.resolve(_addr);" +
            "}" +
            
            // Permissions
            "if(method==='wallet_requestPermissions'){" +
            "return Promise.resolve([{parentCapability:'eth_accounts'}]);" +
            "}" +
            
            "if(method==='wallet_getPermissions'){" +
            "return Promise.resolve([{parentCapability:'eth_accounts'}]);" +
            "}" +
            
            // Chain switching
            "if(method==='wallet_switchEthereumChain'){" +
            "var newChainHex=params[0]&&params[0].chainId;" +
            "if(newChainHex){" +
            "var newChainInt=parseInt(newChainHex,16);" +
            "if(_rpcs[newChainInt]){" +
            "_chainId=newChainHex;" +
            "_netVersion=String(newChainInt);" +
            "_rpcUrl=_rpcs[newChainInt];" +
            "provider.chainId=_chainId;" +
            "provider.networkVersion=_netVersion;" +
            "emit('chainChanged',_chainId);" +
            "return Promise.resolve(null);" +
            "}else{" +
            "return Promise.reject({code:4902,message:'Chain not supported'});" +
            "}" +
            "}" +
            "return Promise.resolve(null);" +
            "}" +
            
            "if(method==='wallet_addEthereumChain'){" +
            "return Promise.resolve(null);" +
            "}" +
            
            "if(method==='wallet_watchAsset'){" +
            "return Promise.resolve(true);" +
            "}" +
            
            // Disconnect
            "if(method==='wallet_revokePermissions'||method==='wallet_disconnect'){" +
            "emit('accountsChanged',[]);" +
            "emit('disconnect',{code:4900,message:'Disconnected'});" +
            "return bridge(method,params).catch(function(){return null;});" +
            "}" +
            
            // Direct RPC calls
            "if(directRpc.indexOf(method)>=0){" +
            "return rpc(method,params);" +
            "}" +
            
            // Everything else goes to native bridge (signing, etc.)
            "return bridge(method,params);" +
            "}" +
            
            // Legacy methods
            "function enable(){" +
            "return request({method:'eth_requestAccounts'});" +
            "}" +
            
            "function send(payload,callback){" +
            "if(typeof payload==='string'){" +
            "return request({method:payload,params:callback});" +
            "}" +
            "if(typeof callback==='function'){" +
            "request({method:payload.method,params:payload.params})" +
            ".then(function(r){callback(null,{id:payload.id,jsonrpc:'2.0',result:r});})" +
            ".catch(function(e){callback(e);});" +
            "return;" +
            "}" +
            "return request({method:payload.method,params:payload.params});" +
            "}" +
            
            "function sendAsync(payload,callback){" +
            "request({method:payload.method,params:payload.params})" +
            ".then(function(r){callback(null,{id:payload.id,jsonrpc:'2.0',result:r});})" +
            ".catch(function(e){callback(e);});" +
            "}" +
            
            // Create provider object
            "var provider={" +
            "isMetaMask:true," +
            "isTrust:true," +
            "isTrustWallet:true," +
            "isVaultKey:true," +
            "chainId:_chainId," +
            "networkVersion:_netVersion," +
            "selectedAddress:_addr," +
            "_rpcUrl:_rpcUrl," +
            "isConnected:function(){return _connected;}," +
            "request:request," +
            "enable:enable," +
            "send:send," +
            "sendAsync:sendAsync," +
            "on:on," +
            "once:function(e,fn){var w=function(){off(e,w);fn.apply(null,arguments);};return on(e,w);}," +
            "off:off," +
            "removeListener:off," +
            "removeAllListeners:function(e){if(e)_listeners[e]=[];else _listeners={};return provider;}," +
            "emit:emit," +
            "providers:[],"+
            "_metamask:{isUnlocked:function(){return Promise.resolve(true);}}" +
            "};" +
            
            "provider.providers.push(provider);" +
            
            // Expose update function to allow native code to update closure state
            // Only emit events when values actually change to avoid breaking React apps
            "window.__vkUpdate=function(newAddr,newChainId,newRpcUrl){" +
            "var addrChanged=_addr!==newAddr;" +
            "var chainChanged=_chainId!==newChainId;" +
            "if(!addrChanged&&!chainChanged)return;" +
            "_addr=newAddr;" +
            "_chainId=newChainId;" +
            "_netVersion=String(parseInt(newChainId,16));" +
            "_rpcUrl=newRpcUrl;" +
            "provider.selectedAddress=_addr;" +
            "provider.chainId=_chainId;" +
            "provider.networkVersion=_netVersion;" +
            "provider._rpcUrl=_rpcUrl;" +
            "if(addrChanged)emit('accountsChanged',[_addr]);" +
            "if(chainChanged)emit('chainChanged',_chainId);" +
            "console.log('[VaultKey] State updated:',_chainId,_addr?_addr.slice(0,10)+'...':'none');" +
            "};" +
            
            // Install provider without breaking existing window.ethereum
            "try{" +
            "Object.defineProperty(window,'ethereum',{" +
            "value:provider," +
            "writable:false," +
            "configurable:true," +
            "enumerable:true" +
            "});" +
            "}catch(e){" +
            "window.ethereum=provider;" +
            "}" +
            
            // Trust Wallet compatibility
            "window.trustwallet={ethereum:provider,provider:provider};" +
            "window.web3={currentProvider:provider};" +
            
            // EIP-6963 announcements
            "var walletInfo={" +
            "uuid:'vaultkey-'+Date.now()," +
            "name:'VaultKey'," +
            "icon:'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 40 40%22%3E%3Crect fill=%22%233b82f6%22 width=%2240%22 height=%2240%22 rx=%228%22/%3E%3Cpath fill=%22white%22 d=%22M20 8l8 6v12l-8 6-8-6V14z%22/%3E%3C/svg%3E'," +
            "rdns:'app.vaultkey.wallet'" +
            "};" +
            
            "function announce(){" +
            "try{" +
            "var detail={info:walletInfo,provider:provider};" +
            "window.dispatchEvent(new CustomEvent('eip6963:announceProvider',{detail:Object.freeze(detail)}));" +
            "}catch(e){}" +
            "}" +
            
            "window.addEventListener('eip6963:requestProvider',announce);" +
            
            // Announce immediately and on delays
            "announce();" +
            "setTimeout(announce,50);" +
            "setTimeout(announce,150);" +
            "setTimeout(announce,500);" +
            "setTimeout(announce,1000);" +
            
            // Dispatch ethereum initialized event
            "try{" +
            "window.dispatchEvent(new Event('ethereum#initialized'));" +
            "}catch(e){}" +
            
            "console.log('[VaultKey] Provider injected, chain:',_chainId,'addr:',_addr?_addr.slice(0,10)+'...':'none');" +
            "})();";
    }

    private class WalletBridge {
        @JavascriptInterface
        public void postMessage(String message) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(message);
                int id = json.getInt("id");
                String method = json.getString("method");
                String params = json.optString("params", "[]");
                
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
        try {
            destroyWebView();
        } catch (Exception e) {
            Log.e(TAG, "Error in handleOnDestroy", e);
        }
        super.handleOnDestroy();
    }
}
