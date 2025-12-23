import { Capacitor, registerPlugin } from "@capacitor/core";
import { dappBridge } from "./dapp-bridge";

interface DAppBrowserPlugin {
  open(options: { url: string; address: string; chainId: number }): Promise<{ success: boolean }>;
  close(): Promise<{ success: boolean }>;
  updateAccount(options: { address: string; chainId: number }): Promise<{ success: boolean }>;
  sendResponse(options: { id: number; result?: string; error?: string }): Promise<{ success: boolean }>;
  addListener(event: "browserEvent", callback: (data: { url: string; loading: boolean }) => void): Promise<{ remove: () => void }>;
  addListener(event: "web3Request", callback: (data: { id: number; method: string; params: string }) => void): Promise<{ remove: () => void }>;
}

const DAppBrowser = registerPlugin<DAppBrowserPlugin>("DAppBrowser");

export function isNativeDAppBrowserAvailable(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === "android";
}

export class NativeDAppBrowserService {
  private browserEventListener: { remove: () => void } | null = null;
  private web3RequestListener: { remove: () => void } | null = null;
  private currentAddress: string = "";
  private currentChainId: number = 1;
  private onLoadingChange: ((loading: boolean) => void) | null = null;
  private onUrlChange: ((url: string) => void) | null = null;
  private onChainChange: ((chainId: number) => void) | null = null;
  private onSignRequest: ((method: string, params: any[]) => Promise<string | null>) | null = null;

  async open(url: string, address: string, chainId: number): Promise<boolean> {
    if (!isNativeDAppBrowserAvailable()) {
      console.log("[NativeDAppBrowser] Not available on this platform");
      return false;
    }

    this.currentAddress = address;
    this.currentChainId = chainId;

    try {
      this.browserEventListener = await DAppBrowser.addListener("browserEvent", (data) => {
        console.log("[NativeDAppBrowser] Browser event:", data);
        if (this.onLoadingChange) {
          this.onLoadingChange(data.loading);
        }
        if (this.onUrlChange) {
          this.onUrlChange(data.url);
        }
      });

      this.web3RequestListener = await DAppBrowser.addListener("web3Request", async (data) => {
        console.log("[NativeDAppBrowser] Web3 request:", data);
        await this.handleWeb3Request(data.id, data.method, data.params);
      });

      const result = await DAppBrowser.open({ url, address, chainId });
      return result.success;
    } catch (e) {
      console.error("[NativeDAppBrowser] Error opening:", e);
      return false;
    }
  }

  async close(): Promise<void> {
    if (this.browserEventListener) {
      this.browserEventListener.remove();
      this.browserEventListener = null;
    }
    if (this.web3RequestListener) {
      this.web3RequestListener.remove();
      this.web3RequestListener = null;
    }

    try {
      await DAppBrowser.close();
    } catch (e) {
      console.error("[NativeDAppBrowser] Error closing:", e);
    }
  }

  async updateAccount(address: string, chainId: number): Promise<void> {
    this.currentAddress = address;
    this.currentChainId = chainId;
    
    try {
      await DAppBrowser.updateAccount({ address, chainId });
    } catch (e) {
      console.error("[NativeDAppBrowser] Error updating account:", e);
    }
  }

  setOnLoadingChange(callback: (loading: boolean) => void): void {
    this.onLoadingChange = callback;
  }

  setOnUrlChange(callback: (url: string) => void): void {
    this.onUrlChange = callback;
  }

  setOnChainChange(callback: (chainId: number) => void): void {
    this.onChainChange = callback;
  }

  setOnSignRequest(callback: (method: string, params: any[]) => Promise<string | null>): void {
    this.onSignRequest = callback;
  }

  private async handleWeb3Request(id: number, method: string, paramsJson: string): Promise<void> {
    try {
      let params: any[] = [];
      try {
        params = JSON.parse(paramsJson);
      } catch (e) {
        params = [];
      }

      dappBridge.setChainId(this.currentChainId);
      dappBridge.setAccount(this.currentAddress);

      let result: any = null;
      let error: string = "";

      switch (method) {
        case "eth_requestAccounts":
        case "eth_accounts":
          result = [this.currentAddress];
          break;

        case "eth_chainId":
          result = "0x" + this.currentChainId.toString(16);
          break;

        case "net_version":
          result = this.currentChainId.toString();
          break;

        case "wallet_switchEthereumChain": {
          const targetChainIdHex = params[0]?.chainId;
          if (targetChainIdHex) {
            const targetChainId = parseInt(targetChainIdHex, 16);
            const supportedChains = [1, 56, 137, 43114, 42161, 10];
            
            if (supportedChains.includes(targetChainId)) {
              this.currentChainId = targetChainId;
              dappBridge.setChainId(targetChainId);
              
              if (this.onChainChange) {
                this.onChainChange(targetChainId);
              }
              
              await DAppBrowser.updateAccount({ 
                address: this.currentAddress, 
                chainId: targetChainId 
              });
              
              result = null;
            } else {
              error = "Chain not supported";
            }
          } else {
            result = null;
          }
          break;
        }

        case "eth_sendTransaction":
        case "personal_sign":
        case "eth_sign":
        case "eth_signTypedData":
        case "eth_signTypedData_v3":
        case "eth_signTypedData_v4": {
          if (this.onSignRequest) {
            try {
              const signResult = await this.onSignRequest(method, params);
              if (signResult) {
                result = signResult;
              } else {
                error = "User rejected the request";
              }
            } catch (e: any) {
              error = e.message || "Signing failed";
            }
          } else {
            try {
              const signResult = await this.executeBridgeRequest(id, method, params);
              if (signResult.error) {
                error = signResult.error.message;
              } else {
                result = signResult.result;
              }
            } catch (e: any) {
              error = e.message || "Signing failed";
            }
          }
          break;
        }

        default:
          try {
            const rpcResult = await this.rpcCall(method, params);
            result = rpcResult;
          } catch (e: any) {
            error = e.message || "RPC call failed";
          }
      }

      await DAppBrowser.sendResponse({
        id,
        result: result !== null ? JSON.stringify(result) : undefined,
        error: error || undefined,
      });
    } catch (e: any) {
      console.error("[NativeDAppBrowser] Error handling request:", e);
      await DAppBrowser.sendResponse({
        id,
        error: e.message || "Unknown error",
      });
    }
  }

  private pendingBridgeRequests: Map<number, { resolve: (value: { result?: any; error?: { code: number; message: string } }) => void }> = new Map();
  private bridgeHandlerInstalled: boolean = false;

  private installBridgeResponseHandler(): void {
    if (this.bridgeHandlerInstalled) return;
    
    dappBridge.setResponseHandler((response) => {
      const pending = this.pendingBridgeRequests.get(response.id);
      if (pending) {
        this.pendingBridgeRequests.delete(response.id);
        pending.resolve({ result: response.result, error: response.error });
      }
    });
    
    this.bridgeHandlerInstalled = true;
  }

  private async executeBridgeRequest(id: number, method: string, params: any[]): Promise<{ result?: any; error?: { code: number; message: string } }> {
    this.installBridgeResponseHandler();
    
    return new Promise((resolve) => {
      const timeoutId = setTimeout(() => {
        if (this.pendingBridgeRequests.has(id)) {
          this.pendingBridgeRequests.delete(id);
          resolve({ error: { code: 4000, message: "Request timed out" } });
        }
      }, 120000);

      this.pendingBridgeRequests.set(id, {
        resolve: (value) => {
          clearTimeout(timeoutId);
          resolve(value);
        }
      });

      const bridgeRequest = { type: "VAULTKEY_REQUEST", id, method, params };
      dappBridge.handleRequest(bridgeRequest).catch((e: any) => {
        if (this.pendingBridgeRequests.has(id)) {
          this.pendingBridgeRequests.delete(id);
          clearTimeout(timeoutId);
          resolve({ error: { code: 4000, message: e.message || "Unknown error" } });
        }
      });
    });
  }

  private async rpcCall(method: string, params: any[]): Promise<any> {
    const rpcUrls: Record<number, string> = {
      1: "https://eth.llamarpc.com",
      56: "https://bsc-dataseed.binance.org",
      137: "https://polygon-rpc.com",
      43114: "https://api.avax.network/ext/bc/C/rpc",
      42161: "https://arb1.arbitrum.io/rpc",
      10: "https://mainnet.optimism.io",
    };

    const rpcUrl = rpcUrls[this.currentChainId];
    if (!rpcUrl) {
      throw new Error("Unsupported chain");
    }

    const response = await fetch(rpcUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: Date.now(),
        method,
        params,
      }),
    });

    const data = await response.json();
    if (data.error) {
      throw new Error(data.error.message);
    }
    return data.result;
  }
}

export const nativeDAppBrowser = new NativeDAppBrowserService();
