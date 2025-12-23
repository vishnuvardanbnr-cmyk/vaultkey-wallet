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
    private String currentAddress = "";
    private int currentChainId = 1;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private int headerHeightPx = 0;
    private int footerHeightPx = 0;

    @PluginMethod
    public void open(PluginCall call) {
        String url = call.getString("url", "");
        currentAddress = call.getString("address", "");
        currentChainId = call.getInt("chainId", 1);
        
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
    }

    @PluginMethod
    public void updateAccount(PluginCall call) {
        currentAddress = call.getString("address", currentAddress);
        currentChainId = call.getInt("chainId", currentChainId);
        
        if (webView != null) {
            String hexChainId = "0x" + Integer.toHexString(currentChainId);
            String updateScript = 
                "(function(){" +
                "if(window.ethereum){" +
                "window.ethereum.selectedAddress='" + currentAddress + "';" +
                "window.ethereum.chainId='" + hexChainId + "';" +
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
            String escapedError = error.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
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

        headerHeightPx = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 56, activity.getResources().getDisplayMetrics());
        footerHeightPx = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 72, activity.getResources().getDisplayMetrics());

        container = new FrameLayout(activity);
        container.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        containerParams.setMargins(0, headerHeightPx, 0, footerHeightPx);
        container.setLayoutParams(containerParams);
        container.setVisibility(View.VISIBLE);

        webView = new WebView(activity);
        webView.setBackgroundColor(Color.WHITE);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

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
                try {
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
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView.HitTestResult result = view.getHitTestResult();
                String url = result.getExtra();
                if (url != null) {
                    view.loadUrl(url);
                }
                return false;
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        container.addView(webView);
        
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        rootView.addView(container);

        webView.loadUrl(url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setBlockNetworkLoads(false);
        
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }
        
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            try {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF);
            } catch (Exception e) {
                Log.e(TAG, "Force dark error", e);
            }
        }
        
        String ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 Trust/Android";
        settings.setUserAgentString(ua);
    }

    private String buildInjectionScript() {
        String hexChainId = "0x" + Integer.toHexString(currentChainId);
        
        return "(function(){" +
            "if(window._vkInjected)return;" +
            "window._vkInjected=true;" +
            "window._vkCallbacks={};" +
            "var _id=1;" +
            "var addr='" + currentAddress + "';" +
            "var cid='" + hexChainId + "';" +
            "var nv='" + currentChainId + "';" +
            "" +
            "function P(){" +
            "this.isMetaMask=true;" +
            "this.isTrust=true;" +
            "this.isTrustWallet=true;" +
            "this.isVaultKey=true;" +
            "this.chainId=cid;" +
            "this.networkVersion=nv;" +
            "this.selectedAddress=addr;" +
            "this._events={};" +
            "this._metamask={isUnlocked:function(){return Promise.resolve(true);}};" +
            "}" +
            "" +
            "P.prototype.isConnected=function(){return true;};" +
            "" +
            "P.prototype.on=function(e,c){" +
            "if(!this._events[e])this._events[e]=[];" +
            "this._events[e].push(c);" +
            "return this;" +
            "};" +
            "" +
            "P.prototype.once=function(e,c){var s=this;var w=function(){s.removeListener(e,w);c.apply(this,arguments);};return this.on(e,w);};" +
            "P.prototype.off=function(e,c){return this.removeListener(e,c);};" +
            "P.prototype.removeListener=function(e,c){if(this._events[e])this._events[e]=this._events[e].filter(function(x){return x!==c;});return this;};" +
            "P.prototype.removeAllListeners=function(e){if(e)this._events[e]=[];else this._events={};return this;};" +
            "" +
            "P.prototype.emit=function(e){" +
            "var a=Array.prototype.slice.call(arguments,1);" +
            "if(this._events[e])this._events[e].forEach(function(c){try{c.apply(null,a);}catch(x){}});" +
            "return true;" +
            "};" +
            "" +
            "P.prototype.request=function(args){" +
            "var s=this,m=args.method,p=args.params||[];" +
            "" +
            "if(m==='eth_accounts'||m==='eth_requestAccounts'){s.emit('connect',{chainId:cid});return Promise.resolve([addr]);}" +
            "if(m==='eth_chainId')return Promise.resolve(cid);" +
            "if(m==='net_version')return Promise.resolve(nv);" +
            "if(m==='eth_coinbase')return Promise.resolve(addr);" +
            "if(m==='wallet_requestPermissions')return Promise.resolve([{parentCapability:'eth_accounts'}]);" +
            "if(m==='wallet_getPermissions')return Promise.resolve([{parentCapability:'eth_accounts'}]);" +
            "if(m==='wallet_switchEthereumChain'){var nc=p[0]&&p[0].chainId;if(nc){cid=nc;nv=parseInt(nc,16).toString();s.chainId=cid;s.networkVersion=nv;s.emit('chainChanged',cid);}return Promise.resolve(null);}" +
            "if(m==='wallet_addEthereumChain')return Promise.resolve(null);" +
            "if(m==='wallet_watchAsset')return Promise.resolve(true);" +
            "" +
            "return new Promise(function(res,rej){" +
            "var i=_id++;" +
            "window._vkCallbacks[i]={resolve:res,reject:rej};" +
            "try{VaultKeyNative.postMessage(JSON.stringify({id:i,method:m,params:p}));}catch(e){delete window._vkCallbacks[i];rej(e);}" +
            "setTimeout(function(){if(window._vkCallbacks[i]){delete window._vkCallbacks[i];rej(new Error('Timeout'));}},120000);" +
            "});" +
            "};" +
            "" +
            "P.prototype.enable=function(){return this.request({method:'eth_requestAccounts'});};" +
            "P.prototype.send=function(a,b){if(typeof a==='string')return this.request({method:a,params:b});if(typeof b==='function'){this.request({method:a.method,params:a.params}).then(function(r){b(null,{id:a.id,jsonrpc:'2.0',result:r});}).catch(b);return;}return this.request({method:a.method,params:a.params});};" +
            "P.prototype.sendAsync=function(a,b){this.request({method:a.method,params:a.params}).then(function(r){b(null,{id:a.id,jsonrpc:'2.0',result:r});}).catch(b);};" +
            "" +
            "var p=new P();" +
            "p.providers=[p];" +
            "" +
            "try{delete window.ethereum;}catch(x){}" +
            "Object.defineProperty(window,'ethereum',{value:p,writable:false,configurable:true,enumerable:true});" +
            "window.trustwallet={ethereum:p,provider:p};" +
            "window.web3={currentProvider:p};" +
            "" +
            "function announce(){" +
            "try{" +
            "var d={info:{uuid:'vaultkey-'+Date.now(),name:'VaultKey',icon:'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 40 40%22%3E%3Crect fill=%22%233b82f6%22 width=%2240%22 height=%2240%22 rx=%228%22/%3E%3Cpath fill=%22white%22 d=%22M20 8l8 6v12l-8 6-8-6V14z%22/%3E%3C/svg%3E',rdns:'app.vaultkey.wallet'},provider:p};" +
            "window.dispatchEvent(new CustomEvent('eip6963:announceProvider',{detail:Object.freeze(d)}));" +
            "}catch(e){}" +
            "}" +
            "" +
            "window.addEventListener('eip6963:requestProvider',announce);" +
            "setTimeout(announce,0);" +
            "setTimeout(announce,100);" +
            "setTimeout(announce,500);" +
            "window.dispatchEvent(new Event('ethereum#initialized'));" +
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
