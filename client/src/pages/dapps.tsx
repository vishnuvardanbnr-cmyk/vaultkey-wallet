import { useState, useRef, useEffect, useCallback } from "react";
import { ExternalLink, Globe, Wallet, ChevronRight, RefreshCw, X, ArrowLeft, Lock, Copy, AlertTriangle, Send, FileSignature } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/hooks/use-toast";
import { useWallet } from "@/lib/wallet-context";
import { HardwareStatusCard } from "@/components/hardware-status";
import { ChainIcon } from "@/components/chain-icon";
import { DEFAULT_CHAINS } from "@shared/schema";
import { isNativeDAppBrowserAvailable, nativeDAppBrowser } from "@/lib/native-dapp-browser";
import { dappBridge } from "@/lib/dapp-bridge";
import { ethers } from "ethers";

interface PendingSignRequest {
  method: string;
  params: any[];
  resolve: (result: string | null) => void;
}

const EVM_CHAINS = DEFAULT_CHAINS.filter(c => c.chainId > 0);

interface DAppInfo {
  name: string;
  url: string;
  description: string;
  category: string;
}

const POPULAR_DAPPS: DAppInfo[] = [
  { name: "PancakeSwap", url: "https://pancakeswap.finance/", description: "Trade, earn crypto", category: "DEX" },
  { name: "Uniswap", url: "https://app.uniswap.org/", description: "Swap tokens", category: "DEX" },
  { name: "Aave", url: "https://app.aave.com/", description: "Lending protocol", category: "Lending" },
  { name: "1inch", url: "https://app.1inch.io/", description: "DEX aggregator", category: "DEX" },
];

export default function DApps() {
  const { isConnected, isUnlocked, wallets, chains, walletMode } = useWallet();
  const { toast } = useToast();
  
  const [url, setUrl] = useState("");
  const [currentUrl, setCurrentUrl] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [iframeError, setIframeError] = useState(false);
  const [selectedChainId, setSelectedChainId] = useState<number>(56); // BNB Chain default
  const [showWalletSelector, setShowWalletSelector] = useState(false);
  const [connectedWallet, setConnectedWallet] = useState<string | null>(null);
  const [isNativeBrowserOpen, setIsNativeBrowserOpen] = useState(false);
  const [pendingSignRequest, setPendingSignRequest] = useState<PendingSignRequest | null>(null);
  const [isSigningInProgress, setIsSigningInProgress] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement>(null);

  const isMobile = isNativeDAppBrowserAvailable();
  const selectedChain = EVM_CHAINS.find(c => c.chainId === selectedChainId) || EVM_CHAINS[0];

  // Get wallets for current chain
  const chainWallets = wallets.filter(w => {
    const chain = chains.find(c => c.id === w.chainId);
    return chain && chain.chainId === selectedChainId;
  });

  // All EVM wallets
  const evmWallets = wallets.filter(w => {
    const chain = chains.find(c => c.id === w.chainId);
    return chain && chain.chainId > 0;
  });

  // Cleanup native browser on unmount
  useEffect(() => {
    return () => {
      if (isNativeBrowserOpen) {
        nativeDAppBrowser.close();
      }
    };
  }, [isNativeBrowserOpen]);

  // Update native browser when account changes
  useEffect(() => {
    if (isNativeBrowserOpen && connectedWallet) {
      nativeDAppBrowser.updateAccount(connectedWallet, selectedChainId);
    }
  }, [connectedWallet, selectedChainId, isNativeBrowserOpen]);

  // Handle sign request from DApp - show confirmation dialog
  const handleSignRequest = useCallback((method: string, params: any[]): Promise<string | null> => {
    return new Promise((resolve) => {
      setPendingSignRequest({ method, params, resolve });
    });
  }, []);

  // Format transaction details for display
  const formatTransactionDetails = useCallback((method: string, params: any[]) => {
    if (method === "eth_sendTransaction" || method === "eth_signTransaction") {
      const tx = params[0] || {};
      const value = tx.value ? ethers.formatEther(BigInt(tx.value)) : "0";
      return {
        type: "Transaction",
        to: tx.to || "Contract Creation",
        value: `${value} ${selectedChain.symbol}`,
        data: tx.data ? (tx.data.length > 66 ? tx.data.slice(0, 66) + "..." : tx.data) : "0x",
        hasData: tx.data && tx.data !== "0x",
      };
    } else if (method === "personal_sign" || method === "eth_sign") {
      const message = params[0] || "";
      let displayMessage = message;
      if (message.startsWith("0x")) {
        try {
          displayMessage = Buffer.from(message.slice(2), "hex").toString("utf8");
        } catch {
          displayMessage = message;
        }
      }
      return {
        type: "Message Signature",
        message: displayMessage.length > 200 ? displayMessage.slice(0, 200) + "..." : displayMessage,
      };
    } else if (method.includes("signTypedData")) {
      try {
        const typedData = typeof params[1] === "string" ? JSON.parse(params[1]) : params[1];
        return {
          type: "Typed Data Signature",
          domain: typedData.domain?.name || "Unknown DApp",
          primaryType: typedData.primaryType || "Unknown",
        };
      } catch {
        return { type: "Typed Data Signature" };
      }
    }
    return { type: method };
  }, [selectedChain]);

  // Approve sign request
  const approveSignRequest = useCallback(async () => {
    if (!pendingSignRequest) return;
    
    setIsSigningInProgress(true);
    try {
      dappBridge.setAccount(connectedWallet || "");
      dappBridge.setChainId(selectedChainId);
      dappBridge.setWalletMode(walletMode === "hard_wallet" ? "hardware" : "soft_wallet");
      
      // Create a promise to capture the response
      let signedResult: string | null = null;
      
      dappBridge.setResponseHandler((response) => {
        if (response.result) {
          signedResult = response.result;
        }
      });
      
      // Execute the request through dappBridge
      await dappBridge.handleRequest({
        type: "web3_request",
        id: Date.now(),
        method: pendingSignRequest.method,
        params: pendingSignRequest.params,
      });
      
      pendingSignRequest.resolve(signedResult);
      
      toast({
        title: "Signed Successfully",
        description: pendingSignRequest.method.includes("send") ? "Transaction sent" : "Request signed",
        duration: 3000,
      });
    } catch (error: any) {
      console.error("[DApps] Sign error:", error);
      toast({
        title: "Signing Failed",
        description: error?.message || "Failed to sign request",
        variant: "destructive",
      });
      pendingSignRequest.resolve(null);
    } finally {
      setIsSigningInProgress(false);
      setPendingSignRequest(null);
    }
  }, [pendingSignRequest, connectedWallet, selectedChainId, walletMode, toast]);

  // Reject sign request
  const rejectSignRequest = useCallback(() => {
    if (pendingSignRequest) {
      pendingSignRequest.resolve(null);
      setPendingSignRequest(null);
      toast({
        title: "Request Rejected",
        description: "You declined the signing request",
        duration: 2000,
      });
    }
  }, [pendingSignRequest, toast]);

  const openNativeBrowser = async (targetUrl: string) => {
    // Use any EVM wallet address since they're chain-agnostic
    const address = connectedWallet || chainWallets[0]?.address || evmWallets[0]?.address || "";
    
    console.log("[DApps] openNativeBrowser called", { 
      targetUrl, 
      address, 
      hasConnectedWallet: !!connectedWallet,
      chainWalletsCount: chainWallets.length,
      evmWalletsCount: evmWallets.length,
      isMobile 
    });
    
    if (!address) {
      toast({
        title: "No Wallet Found",
        description: "Please create a wallet first to use DApps",
        variant: "destructive",
      });
      return;
    }
    
    console.log("[DApps] Opening native browser:", targetUrl, "with address:", address, "chainId:", selectedChainId);

    setIsLoading(true);
    
    nativeDAppBrowser.setOnLoadingChange((loading) => {
      setIsLoading(loading);
    });
    
    nativeDAppBrowser.setOnUrlChange((newUrl) => {
      setUrl(newUrl);
      setCurrentUrl(newUrl);
    });

    nativeDAppBrowser.setOnChainChange((newChainId) => {
      setSelectedChainId(newChainId);
      toast({
        title: "Network Changed",
        description: `DApp requested switch to ${EVM_CHAINS.find(c => c.chainId === newChainId)?.name || 'Unknown Network'}`,
        duration: 2000,
      });
    });

    nativeDAppBrowser.setOnDisconnect(() => {
      setConnectedWallet(null);
    });

    // Set up sign request handler to show confirmation dialog
    nativeDAppBrowser.setOnSignRequest(handleSignRequest);

    try {
      toast({
        title: "Opening DApp...",
        description: "Launching browser",
        duration: 2000,
      });
      
      const success = await nativeDAppBrowser.open(targetUrl, address, selectedChainId);
      console.log("[DApps] Native browser open result:", success);
      
      if (success) {
        setIsNativeBrowserOpen(true);
        setConnectedWallet(address);
        setCurrentUrl(targetUrl);
      } else {
        setIsLoading(false);
        toast({
          title: "Browser Error",
          description: "Failed to open DApp browser. Try updating the app.",
          variant: "destructive",
        });
      }
    } catch (error: any) {
      console.error("[DApps] Error opening native browser:", error);
      setIsLoading(false);
      toast({
        title: "Browser Error",
        description: error?.message || "Failed to open DApp browser",
        variant: "destructive",
      });
    }
  };

  const closeNativeBrowser = async () => {
    await nativeDAppBrowser.close();
    setIsNativeBrowserOpen(false);
    setCurrentUrl("");
    setUrl("");
  };

  const handleNavigate = async () => {
    if (!url.trim()) return;
    let formattedUrl = url.trim();
    if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
      formattedUrl = "https://" + formattedUrl;
    }
    
    if (isMobile) {
      await openNativeBrowser(formattedUrl);
    } else {
      setCurrentUrl(formattedUrl);
      setUrl(formattedUrl);
      setIframeError(false);
      setIsLoading(true);
    }
  };

  const handleOpenDapp = async (dappUrl: string) => {
    if (isMobile) {
      setUrl(dappUrl);
      await openNativeBrowser(dappUrl);
    } else {
      setUrl(dappUrl);
      setCurrentUrl(dappUrl);
      setIframeError(false);
      setIsLoading(true);
    }
  };

  const handleIframeLoad = () => {
    setIsLoading(false);
  };

  const handleIframeError = () => {
    setIsLoading(false);
    setIframeError(true);
  };

  const handleChainSwitch = (chainId: number) => {
    setSelectedChainId(chainId);
    setConnectedWallet(null);
    toast({
      title: "Chain Switched",
      description: `Switched to ${EVM_CHAINS.find(c => c.chainId === chainId)?.name}`,
      duration: 2000,
    });
  };

  const handleConnectWallet = () => {
    setShowWalletSelector(true);
  };

  const handleWalletSelect = (walletAddress: string, chainName: string) => {
    setConnectedWallet(walletAddress);
    navigator.clipboard.writeText(walletAddress);
    
    toast({
      title: "Wallet Connected",
      description: `${chainName} wallet connected. Address copied.`,
      duration: 2000,
    });
    
    setShowWalletSelector(false);
  };

  if (!isConnected || !isUnlocked) {
    return (
      <div className="p-6">
        <h1 className="mb-6 text-3xl font-bold">DApps</h1>
        <HardwareStatusCard />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {/* Main Header - VaultKey Branding */}
      <div className="flex items-center justify-between gap-2 px-3 py-2 border-b bg-background">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded bg-primary flex items-center justify-center">
            <span className="text-primary-foreground text-xs font-bold">V</span>
          </div>
          <span className="font-semibold text-lg" data-testid="text-vaultkey-title">VaultKey</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1.5">
            <div className={`w-2 h-2 rounded-full ${connectedWallet ? 'bg-green-500' : 'bg-muted-foreground/40'}`} />
            <span className="text-xs text-muted-foreground" data-testid="text-connection-status">
              {connectedWallet ? 'Connected' : 'Not Connected'}
            </span>
          </div>
          <Button 
            variant="ghost" 
            size="icon"
            onClick={handleConnectWallet}
            data-testid="button-lock"
          >
            <Lock className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {/* URL Bar Header */}
      <div className="border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="flex items-center gap-1.5 p-2">
          <Button 
            variant="ghost" 
            size="icon"
            onClick={() => {
              if (isNativeBrowserOpen) {
                closeNativeBrowser();
              } else if (currentUrl) {
                setCurrentUrl("");
                setUrl("");
              }
            }}
            data-testid="button-back"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>

          <div className="flex-1 flex items-center gap-1.5 bg-muted/50 border rounded-md px-2 py-0.5">
            <Input
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleNavigate()}
              placeholder="Enter URL..."
              className="flex-1 border-0 bg-transparent h-8 text-sm focus-visible:ring-0 px-1"
              data-testid="input-browser-url"
            />
            <Button 
              size="sm" 
              onClick={handleNavigate} 
              className="h-7 px-4"
              data-testid="button-go"
            >
              Go
            </Button>
          </div>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="icon" data-testid="button-chain-selector">
                <ChainIcon symbol={selectedChain.symbol} iconColor={selectedChain.iconColor} size="sm" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-48">
              {EVM_CHAINS.map((chain) => (
                <DropdownMenuItem
                  key={chain.chainId}
                  onClick={() => handleChainSwitch(chain.chainId)}
                  className="gap-2"
                  data-testid={`menu-chain-${chain.symbol.toLowerCase()}`}
                >
                  <ChainIcon symbol={chain.symbol} iconColor={chain.iconColor} size="sm" />
                  {chain.name}
                  {chain.chainId === selectedChainId && (
                    <span className="ml-auto text-primary">•</span>
                  )}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <Button
            variant="ghost"
            size="icon"
            onClick={() => {
              if (connectedWallet) {
                navigator.clipboard.writeText(connectedWallet);
                toast({ title: "Address Copied", duration: 1500 });
              } else {
                handleConnectWallet();
              }
            }}
            data-testid="button-copy-address"
          >
            <Copy className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {/* Browser content */}
      <div className="flex-1 relative bg-muted/30">
        {isNativeBrowserOpen ? (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="text-center max-w-md p-6">
              <Globe className="mx-auto h-16 w-16 text-primary/50 mb-4 animate-pulse" />
              <h2 className="text-xl font-semibold mb-2">Native Browser Active</h2>
              <p className="text-muted-foreground mb-4">
                Web3 provider injected. Click any wallet option (MetaMask, Trust Wallet, etc.) to connect with VaultKey.
              </p>
              <div className="p-3 bg-muted rounded-lg mb-4">
                <p className="text-sm font-mono break-all">{currentUrl}</p>
              </div>
              <p className="text-xs text-muted-foreground">
                Connected: {connectedWallet?.slice(0, 8)}...{connectedWallet?.slice(-6)}
              </p>
              <Button 
                variant="outline" 
                className="mt-4"
                onClick={closeNativeBrowser}
                data-testid="button-close-native-browser"
              >
                <X className="mr-2 h-4 w-4" />
                Close Browser
              </Button>
            </div>
          </div>
        ) : !currentUrl ? (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="text-center max-w-md p-6">
              <Globe className="mx-auto h-16 w-16 text-muted-foreground/30 mb-4" />
              <h2 className="text-xl font-semibold mb-2">DApp Browser</h2>
              <p className="text-muted-foreground mb-6">
                {isMobile 
                  ? "Open a DApp and all wallet connections will use VaultKey" 
                  : "Enter a URL or select a popular DApp below"}
              </p>

              <div className="grid grid-cols-2 gap-2">
                {POPULAR_DAPPS.map((dapp) => (
                  <Button
                    key={dapp.name}
                    variant="outline"
                    size="sm"
                    onClick={() => handleOpenDapp(dapp.url)}
                    className="justify-start"
                    data-testid={`quick-${dapp.name.toLowerCase()}`}
                  >
                    {dapp.name}
                  </Button>
                ))}
              </div>
            </div>
          </div>
        ) : iframeError ? (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="text-center max-w-md p-6">
              <X className="mx-auto h-16 w-16 text-destructive/50 mb-4" />
              <h2 className="text-xl font-semibold mb-2">Cannot Load DApp</h2>
              <p className="text-muted-foreground mb-4">
                This DApp cannot be embedded. Try opening in external browser.
              </p>
              <div className="flex gap-2 justify-center">
                <Button variant="outline" onClick={() => window.open(currentUrl, "_blank")}>
                  <ExternalLink className="mr-2 h-4 w-4" />
                  Open External
                </Button>
                <Button variant="outline" onClick={() => { setCurrentUrl(""); setUrl(""); }}>
                  Back
                </Button>
              </div>
            </div>
          </div>
        ) : (
          <>
            {isLoading && (
              <div className="absolute inset-0 flex items-center justify-center bg-background/80 z-10">
                <RefreshCw className="h-8 w-8 animate-spin text-muted-foreground" />
              </div>
            )}
            <iframe
              ref={iframeRef}
              src={currentUrl}
              className="w-full h-full border-0"
              onLoad={handleIframeLoad}
              onError={handleIframeError}
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox"
              data-testid="iframe-dapp"
            />
          </>
        )}
      </div>

      {/* Wallet Selection Dialog */}
      <Dialog open={showWalletSelector} onOpenChange={setShowWalletSelector}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Wallet className="h-5 w-5" />
              Select Wallet
            </DialogTitle>
            <DialogDescription>
              Choose a wallet to connect
            </DialogDescription>
          </DialogHeader>
          
          <div className="space-y-2 max-h-64 overflow-auto">
            {chainWallets.length > 0 ? (
              chainWallets.map((wallet, index) => {
                const chain = chains.find(c => c.id === wallet.chainId);
                if (!chain) return null;
                
                const walletName = wallet.label || `${chain.name} Wallet${chainWallets.length > 1 ? ` ${index + 1}` : ''}`;
                
                return (
                  <Button
                    key={wallet.id}
                    variant={wallet.address === connectedWallet ? "default" : "outline"}
                    className="w-full justify-start gap-3 h-auto py-3"
                    onClick={() => handleWalletSelect(wallet.address, walletName)}
                    data-testid={`select-wallet-${chain.symbol.toLowerCase()}`}
                  >
                    <ChainIcon symbol={chain.symbol} iconColor={chain.iconColor} size="sm" />
                    <div className="flex-1 text-left">
                      <div className="font-medium">{walletName}</div>
                      <div className="text-xs text-muted-foreground font-mono">
                        {wallet.address.slice(0, 8)}...{wallet.address.slice(-6)}
                      </div>
                    </div>
                    {wallet.address === connectedWallet && (
                      <span className="text-xs">Connected</span>
                    )}
                  </Button>
                );
              })
            ) : (
              <p className="text-center text-muted-foreground py-4">
                No wallets for {selectedChain.name}
              </p>
            )}
          </div>

          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowWalletSelector(false)} className="w-full">
              Cancel
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Sign Request Confirmation Dialog */}
      <Dialog open={!!pendingSignRequest} onOpenChange={(open) => !open && rejectSignRequest()}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              {pendingSignRequest?.method.includes("send") ? (
                <>
                  <Send className="h-5 w-5 text-primary" />
                  Confirm Transaction
                </>
              ) : (
                <>
                  <FileSignature className="h-5 w-5 text-primary" />
                  Sign Request
                </>
              )}
            </DialogTitle>
            <DialogDescription>
              {currentUrl ? new URL(currentUrl).hostname : "DApp"} is requesting your signature
            </DialogDescription>
          </DialogHeader>

          {pendingSignRequest && (() => {
            const details = formatTransactionDetails(pendingSignRequest.method, pendingSignRequest.params);
            return (
              <div className="space-y-3">
                <div className="p-3 bg-muted rounded-lg space-y-2">
                  <div className="flex justify-between">
                    <span className="text-sm text-muted-foreground">Type</span>
                    <span className="text-sm font-medium">{details.type}</span>
                  </div>
                  
                  {"to" in details && (
                    <div className="flex justify-between">
                      <span className="text-sm text-muted-foreground">To</span>
                      <span className="text-sm font-mono">
                        {details.to.length > 20 ? `${details.to.slice(0, 10)}...${details.to.slice(-8)}` : details.to}
                      </span>
                    </div>
                  )}
                  
                  {"value" in details && (
                    <div className="flex justify-between">
                      <span className="text-sm text-muted-foreground">Value</span>
                      <span className="text-sm font-medium">{details.value}</span>
                    </div>
                  )}

                  {"hasData" in details && details.hasData && (
                    <div className="flex items-center gap-2 text-amber-600 dark:text-amber-400">
                      <AlertTriangle className="h-4 w-4" />
                      <span className="text-xs">Contains contract interaction data</span>
                    </div>
                  )}

                  {"message" in details && (
                    <div className="space-y-1">
                      <span className="text-sm text-muted-foreground">Message</span>
                      <p className="text-xs bg-background p-2 rounded font-mono break-all">
                        {details.message}
                      </p>
                    </div>
                  )}

                  {"domain" in details && (
                    <>
                      <div className="flex justify-between">
                        <span className="text-sm text-muted-foreground">Domain</span>
                        <span className="text-sm">{details.domain}</span>
                      </div>
                      {"primaryType" in details && (
                        <div className="flex justify-between">
                          <span className="text-sm text-muted-foreground">Action</span>
                          <span className="text-sm">{details.primaryType}</span>
                        </div>
                      )}
                    </>
                  )}
                </div>

                <div className="p-2 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-lg">
                  <p className="text-xs text-amber-700 dark:text-amber-300">
                    Only sign if you trust this DApp. Malicious sites can steal your funds.
                  </p>
                </div>
              </div>
            );
          })()}

          <DialogFooter className="flex-col gap-2 sm:flex-col">
            <Button 
              onClick={approveSignRequest} 
              className="w-full"
              disabled={isSigningInProgress}
              data-testid="button-approve-sign"
            >
              {isSigningInProgress ? (
                <>
                  <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
                  Signing...
                </>
              ) : (
                pendingSignRequest?.method.includes("send") ? "Confirm Transaction" : "Sign"
              )}
            </Button>
            <Button 
              variant="outline" 
              onClick={rejectSignRequest} 
              className="w-full"
              disabled={isSigningInProgress}
              data-testid="button-reject-sign"
            >
              Reject
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
