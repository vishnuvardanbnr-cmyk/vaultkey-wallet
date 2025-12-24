package app.vaultkey.wallet;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.HashSet;

public class DAppBrowserActivity extends AppCompatActivity {
    private static final String TAG = "DAppBrowserActivity";
    
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_CHAIN_ID = "chainId";
    
    public static final String ACTION_WEB3_REQUEST = "app.vaultkey.wallet.WEB3_REQUEST";
    public static final String ACTION_WEB3_RESPONSE = "app.vaultkey.wallet.WEB3_RESPONSE";
    public static final String ACTION_BROWSER_EVENT = "app.vaultkey.wallet.BROWSER_EVENT";
    public static final String ACTION_CLOSE_BROWSER = "app.vaultkey.wallet.CLOSE_BROWSER";
    public static final String ACTION_UPDATE_ACCOUNT = "app.vaultkey.wallet.UPDATE_ACCOUNT";
    
    private WebView webView;
    private ProgressBar progressBar;
    private EditText urlInput;
    private Button backButton;
    private Button forwardButton;
    private Button refreshButton;
    private Button closeButton;
    
    private String currentAddress = "";
    private int currentChainId = 1;
    private String rpcUrl = "https://eth.llamarpc.com";
    
    private BroadcastReceiver responseReceiver;
    private BroadcastReceiver closeReceiver;
    private BroadcastReceiver updateReceiver;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        String url = getIntent().getStringExtra(EXTRA_URL);
        currentAddress = getIntent().getStringExtra(EXTRA_ADDRESS);
        currentChainId = getIntent().getIntExtra(EXTRA_CHAIN_ID, 1);
        rpcUrl = getRpcUrl(currentChainId);
        
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }
        
        Log.d(TAG, "Opening browser - URL: " + url + ", Address: " + currentAddress + ", ChainId: " + currentChainId);
        
        createUI();
        setupBroadcastReceivers();
        loadUrl(url);
        
        sendBrowserEvent(url, true);
    }
    
    private void createUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        
        float density = getResources().getDisplayMetrics().density;
        
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(Color.parseColor("#1a1a2e"));
        header.setPadding((int)(8 * density), (int)(12 * density), (int)(8 * density), (int)(12 * density));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        header.setLayoutParams(headerParams);
        
        closeButton = createTextButton("\u2190", density);
        closeButton.setOnClickListener(v -> finish());
        header.addView(closeButton);
        
        urlInput = new EditText(this);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.parseColor("#888888"));
        urlInput.setHint("Enter URL...");
        urlInput.setBackgroundColor(Color.parseColor("#2d2d44"));
        urlInput.setPadding((int)(12 * density), (int)(8 * density), (int)(12 * density), (int)(8 * density));
        urlInput.setSingleLine(true);
        urlInput.setTextSize(14);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateToUrl();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        urlParams.setMargins((int)(8 * density), 0, (int)(8 * density), 0);
        urlInput.setLayoutParams(urlParams);
        header.addView(urlInput);
        
        backButton = createTextButton("\u25C0", density);
        backButton.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            }
        });
        header.addView(backButton);
        
        forwardButton = createTextButton("\u25B6", density);
        forwardButton.setOnClickListener(v -> {
            if (webView != null && webView.canGoForward()) {
                webView.goForward();
            }
        });
        header.addView(forwardButton);
        
        refreshButton = createTextButton("\u21BB", density);
        refreshButton.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
            }
        });
        header.addView(refreshButton);
        
        root.addView(header);
        
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (int)(3 * density)
        ));
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar);
        
        createWebView();
        LinearLayout.LayoutParams webViewParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        webView.setLayoutParams(webViewParams);
        root.addView(webView);
        
        setContentView(root);
    }
    
    private Button createTextButton(String text, float density) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16);
        btn.setBackgroundColor(Color.TRANSPARENT);
        btn.setPadding((int)(8 * density), (int)(4 * density), (int)(8 * density), (int)(4 * density));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            (int)(40 * density),
            (int)(40 * density)
        );
        btn.setLayoutParams(params);
        
        return btn;
    }
    
    private void navigateToUrl() {
        String url = urlInput.getText().toString().trim();
        if (!url.isEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            loadUrl(url);
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        
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
        settings.setTextZoom(100);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setGeolocationEnabled(true);
        
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
        
        String ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 VaultKey/1.0";
        settings.setUserAgentString(ua);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        
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
                progressBar.setVisibility(View.VISIBLE);
                urlInput.setText(url);
                
                try {
                    view.evaluateJavascript(injectionScript, null);
                } catch (Exception e) {
                    Log.e(TAG, "Injection error", e);
                }
                
                sendBrowserEvent(url, true);
                updateNavigationButtons();
            }
            
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                urlInput.setText(url);
                
                try {
                    view.evaluateJavascript(injectionScript, null);
                } catch (Exception e) {
                    Log.e(TAG, "Injection error", e);
                }
                
                sendBrowserEvent(url, false);
                updateNavigationButtons();
            }
            
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(reqUrl));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Cannot open: " + reqUrl);
                }
                return true;
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }
    
    private void updateNavigationButtons() {
        if (backButton != null) {
            backButton.setAlpha(webView.canGoBack() ? 1.0f : 0.3f);
        }
        if (forwardButton != null) {
            forwardButton.setAlpha(webView.canGoForward() ? 1.0f : 0.3f);
        }
    }
    
    private void loadUrl(String url) {
        if (webView != null) {
            urlInput.setText(url);
            webView.loadUrl(url);
        }
    }
    
    private void setupBroadcastReceivers() {
        responseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int id = intent.getIntExtra("id", 0);
                String result = intent.getStringExtra("result");
                String error = intent.getStringExtra("error");
                handleWeb3Response(id, result, error);
            }
        };
        
        closeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finish();
            }
        };
        
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                currentAddress = intent.getStringExtra("address");
                currentChainId = intent.getIntExtra("chainId", currentChainId);
                rpcUrl = getRpcUrl(currentChainId);
                updateWebViewAccount();
            }
        };
        
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(responseReceiver, new IntentFilter(ACTION_WEB3_RESPONSE));
        lbm.registerReceiver(closeReceiver, new IntentFilter(ACTION_CLOSE_BROWSER));
        lbm.registerReceiver(updateReceiver, new IntentFilter(ACTION_UPDATE_ACCOUNT));
    }
    
    private void handleWeb3Response(int id, String result, String error) {
        String script;
        if (error != null && !error.isEmpty()) {
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
            runOnUiThread(() -> {
                try {
                    webView.evaluateJavascript(script, null);
                } catch (Exception e) {
                    Log.e(TAG, "Error sending response", e);
                }
            });
        }
    }
    
    private void updateWebViewAccount() {
        if (webView != null) {
            String hexChainId = "0x" + Integer.toHexString(currentChainId);
            String script = 
                "(function(){" +
                "if(window.__vkUpdate){" +
                "window.__vkUpdate('" + currentAddress + "','" + hexChainId + "','" + rpcUrl + "');" +
                "}else if(window.ethereum){" +
                "window.ethereum.selectedAddress='" + currentAddress + "';" +
                "window.ethereum.chainId='" + hexChainId + "';" +
                "if(window.ethereum.emit){" +
                "window.ethereum.emit('accountsChanged',['" + currentAddress + "']);" +
                "window.ethereum.emit('chainChanged','" + hexChainId + "');" +
                "}}})();";
            
            runOnUiThread(() -> {
                try {
                    webView.evaluateJavascript(script, null);
                } catch (Exception e) {
                    Log.e(TAG, "Error updating account", e);
                }
            });
        }
    }
    
    private void sendBrowserEvent(String url, boolean loading) {
        Intent intent = new Intent(ACTION_BROWSER_EVENT);
        intent.putExtra("url", url);
        intent.putExtra("loading", loading);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    private void sendWeb3Request(int id, String method, String params) {
        Intent intent = new Intent(ACTION_WEB3_REQUEST);
        intent.putExtra("id", id);
        intent.putExtra("method", method);
        intent.putExtra("params", params);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
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
    
    private String buildInjectionScript() {
        String hexChainId = "0x" + Integer.toHexString(currentChainId);
        
        return "(function(){" +
            "'use strict';" +
            "if(window._vkInjected)return;" +
            "window._vkInjected=true;" +
            
            "var _id=1;" +
            "var _addr='" + currentAddress + "';" +
            "var _chainId='" + hexChainId + "';" +
            "var _netVersion='" + currentChainId + "';" +
            "var _callbacks={};" +
            "var _listeners={};" +
            
            "var _rpcs={1:'https://eth.llamarpc.com',56:'https://bsc-dataseed1.binance.org',137:'https://polygon-rpc.com',43114:'https://api.avax.network/ext/bc/C/rpc',42161:'https://arb1.arbitrum.io/rpc',10:'https://mainnet.optimism.io',8453:'https://mainnet.base.org'};" +
            "var _rpcUrl=_rpcs[" + currentChainId + "]||'https://eth.llamarpc.com';" +
            
            "window._vkCallbacks=_callbacks;" +
            
            "window.__vkUpdate=function(addr,chain,rpc){" +
            "_addr=addr;_chainId=chain;_rpcUrl=rpc||_rpcUrl;" +
            "_netVersion=String(parseInt(chain,16));" +
            "provider.selectedAddress=_addr;" +
            "provider.chainId=_chainId;" +
            "provider.networkVersion=_netVersion;" +
            "emit('accountsChanged',[_addr]);" +
            "emit('chainChanged',_chainId);" +
            "};" +
            
            "function rpc(method,params){" +
            "return fetch(_rpcUrl,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({jsonrpc:'2.0',id:Date.now(),method:method,params:params||[]})}).then(function(r){return r.json();}).then(function(d){if(d.error)throw new Error(d.error.message);return d.result;});" +
            "}" +
            
            "function on(e,fn){if(!_listeners[e])_listeners[e]=[];_listeners[e].push(fn);return provider;}" +
            "function off(e,fn){if(_listeners[e])_listeners[e]=_listeners[e].filter(function(f){return f!==fn;});return provider;}" +
            "function emit(e){var args=[].slice.call(arguments,1);if(_listeners[e])_listeners[e].slice().forEach(function(fn){try{fn.apply(null,args);}catch(x){}});return true;}" +
            
            "function bridge(method,params){" +
            "return new Promise(function(resolve,reject){" +
            "var id=_id++;_callbacks[id]={resolve:resolve,reject:reject};" +
            "try{VaultKeyNative.postMessage(JSON.stringify({id:id,method:method,params:params}));}catch(e){delete _callbacks[id];reject(e);}" +
            "setTimeout(function(){if(_callbacks[id]){delete _callbacks[id];reject(new Error('Timeout'));}},120000);" +
            "});" +
            "}" +
            
            "var directRpc=['eth_blockNumber','eth_getBlockByNumber','eth_getBlockByHash','eth_call','eth_getBalance','eth_getCode','eth_getStorageAt','eth_getTransactionCount','eth_getTransactionByHash','eth_getTransactionReceipt','eth_getLogs','eth_estimateGas','eth_gasPrice','eth_feeHistory','eth_maxPriorityFeePerGas','net_listening','web3_clientVersion'];" +
            
            "function request(args){" +
            "var method=args.method;var params=args.params||[];" +
            "if(method==='eth_accounts')return Promise.resolve(_addr?[_addr]:[]);" +
            "if(method==='eth_requestAccounts'){emit('connect',{chainId:_chainId});return Promise.resolve([_addr]);}" +
            "if(method==='eth_chainId')return Promise.resolve(_chainId);" +
            "if(method==='net_version')return Promise.resolve(_netVersion);" +
            "if(method==='eth_coinbase')return Promise.resolve(_addr);" +
            "if(method==='wallet_requestPermissions')return Promise.resolve([{parentCapability:'eth_accounts'}]);" +
            "if(method==='wallet_getPermissions')return Promise.resolve([{parentCapability:'eth_accounts'}]);" +
            "if(method==='wallet_switchEthereumChain'){var c=params[0]&&params[0].chainId;if(c){var n=parseInt(c,16);if(_rpcs[n]){_chainId=c;_netVersion=String(n);_rpcUrl=_rpcs[n];provider.chainId=_chainId;emit('chainChanged',_chainId);return Promise.resolve(null);}return Promise.reject({code:4902,message:'Chain not supported'});}return Promise.resolve(null);}" +
            "if(method==='wallet_addEthereumChain')return Promise.resolve(null);" +
            "if(method==='wallet_watchAsset')return Promise.resolve(true);" +
            "if(directRpc.indexOf(method)!==-1)return rpc(method,params);" +
            "if(method==='eth_sendTransaction'||method==='eth_signTransaction'||method==='personal_sign'||method==='eth_sign'||method==='eth_signTypedData'||method==='eth_signTypedData_v3'||method==='eth_signTypedData_v4')return bridge(method,params);" +
            "return rpc(method,params);" +
            "}" +
            
            "var provider={" +
            "isMetaMask:true,isTrust:true,isVaultKey:true," +
            "selectedAddress:_addr,chainId:_chainId,networkVersion:_netVersion," +
            "isConnected:function(){return true;}," +
            "request:request," +
            "send:function(m,p){if(typeof m==='string')return request({method:m,params:p});return request(m);}," +
            "sendAsync:function(req,cb){request({method:req.method,params:req.params}).then(function(r){cb(null,{id:req.id,jsonrpc:'2.0',result:r});}).catch(function(e){cb(e);});}," +
            "on:on,off:off,removeListener:off,emit:emit," +
            "enable:function(){return request({method:'eth_requestAccounts'});}" +
            "};" +
            
            "window.ethereum=provider;" +
            "window.web3={currentProvider:provider};" +
            
            "var info={uuid:'vaultkey-1',name:'VaultKey',icon:'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMzIiIGhlaWdodD0iMzIiIHZpZXdCb3g9IjAgMCAzMiAzMiI+PGNpcmNsZSBjeD0iMTYiIGN5PSIxNiIgcj0iMTYiIGZpbGw9IiMxYTFhMmUiLz48dGV4dCB4PSIxNiIgeT0iMjEiIGZvbnQtc2l6ZT0iMTQiIGZpbGw9IiNmZmYiIHRleHQtYW5jaG9yPSJtaWRkbGUiPuKXiDwvdGV4dD48L3N2Zz4=',rdns:'app.vaultkey.wallet'};" +
            "var detail={info:info,provider:provider};" +
            "window.dispatchEvent(new CustomEvent('eip6963:announceProvider',{detail:detail}));" +
            "window.addEventListener('eip6963:requestProvider',function(){window.dispatchEvent(new CustomEvent('eip6963:announceProvider',{detail:detail}));});" +
            
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
                
                Log.d(TAG, "Web3 request: " + method);
                sendWeb3Request(id, method, params);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing message", e);
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        if (responseReceiver != null) lbm.unregisterReceiver(responseReceiver);
        if (closeReceiver != null) lbm.unregisterReceiver(closeReceiver);
        if (updateReceiver != null) lbm.unregisterReceiver(updateReceiver);
        
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        
        sendBrowserEvent("", false);
    }
    
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
