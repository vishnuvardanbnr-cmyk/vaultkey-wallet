import { useState, useRef } from "react";
import { ExternalLink, Globe, Wallet, ChevronRight, RefreshCw, X } from "lucide-react";
import { BackButton } from "@/components/back-button";
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
  const { isConnected, isUnlocked, wallets, chains } = useWallet();
  const { toast } = useToast();
  
  const [url, setUrl] = useState("");
  const [currentUrl, setCurrentUrl] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [iframeError, setIframeError] = useState(false);
  const [selectedChainId, setSelectedChainId] = useState<number>(56); // BNB Chain default
  const [showWalletSelector, setShowWalletSelector] = useState(false);
  const [connectedWallet, setConnectedWallet] = useState<string | null>(null);
  const iframeRef = useRef<HTMLIFrameElement>(null);

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

  const handleNavigate = () => {
    if (!url.trim()) return;
    let formattedUrl = url.trim();
    if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
      formattedUrl = "https://" + formattedUrl;
    }
    setCurrentUrl(formattedUrl);
    setUrl(formattedUrl);
    setIframeError(false);
    setIsLoading(true);
  };

  const handleOpenDapp = (dappUrl: string) => {
    setUrl(dappUrl);
    setCurrentUrl(dappUrl);
    setIframeError(false);
    setIsLoading(true);
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
      {/* Header with URL bar */}
      <div className="border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="flex items-center gap-1.5 p-2">
          <BackButton />

          <div className="flex-1 flex items-center gap-1.5 bg-muted/50 rounded-lg px-2 py-1">
            <Input
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleNavigate()}
              placeholder="Enter URL..."
              className="flex-1 border-0 bg-transparent h-8 text-sm focus-visible:ring-0 px-1"
              data-testid="input-browser-url"
            />
            <Button size="sm" onClick={handleNavigate} className="h-7 px-3" data-testid="button-go">
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
            variant={connectedWallet ? "default" : "outline"}
            size="icon"
            onClick={handleConnectWallet}
            data-testid="button-connect-wallet"
          >
            <Wallet className="h-4 w-4" />
          </Button>
        </div>

        {/* Connected wallet indicator below URL */}
        <div 
          className="mx-2 mb-2 px-3 py-1.5 rounded-md bg-muted/50 text-xs flex items-center gap-2 cursor-pointer hover-elevate"
          onClick={handleConnectWallet}
          data-testid="wallet-indicator"
        >
          <ChainIcon symbol={selectedChain.symbol} iconColor={selectedChain.iconColor} size="sm" />
          {connectedWallet ? (
            <>
              <span className="w-2 h-2 rounded-full bg-green-500" />
              <span className="font-mono">{connectedWallet.slice(0, 10)}...{connectedWallet.slice(-6)}</span>
            </>
          ) : (
            <span className="text-muted-foreground">Tap to select wallet</span>
          )}
        </div>
      </div>

      {/* Browser content */}
      <div className="flex-1 relative bg-muted/30">
        {!currentUrl ? (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="text-center max-w-md p-6">
              <Globe className="mx-auto h-16 w-16 text-muted-foreground/30 mb-4" />
              <h2 className="text-xl font-semibold mb-2">DApp Browser</h2>
              <p className="text-muted-foreground mb-6">
                Enter a URL or select a popular DApp below
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
    </div>
  );
}
