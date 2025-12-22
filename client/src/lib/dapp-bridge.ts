import { ethers, Mnemonic, HDNodeWallet } from "ethers";
import { hardwareWallet } from "./hardware-wallet";
import { softWallet } from "./soft-wallet";

function getSoftWalletSigner(): HDNodeWallet | null {
  const seedPhrase = softWallet.getSeedPhrase();
  if (!seedPhrase) return null;
  try {
    const mnemonic = Mnemonic.fromPhrase(seedPhrase);
    return HDNodeWallet.fromMnemonic(mnemonic, "m/44'/60'/0'/0/0");
  } catch {
    return null;
  }
}

export interface DAppRequest {
  type: string;
  id: number;
  method: string;
  params: any[];
}

export interface DAppResponse {
  id: number;
  result?: any;
  error?: { code: number; message: string };
}

export type WalletMode = "hardware" | "soft_wallet";

const CHAIN_RPC_URLS: Record<number, string> = {
  1: "https://eth.llamarpc.com",
  56: "https://bsc-dataseed.binance.org",
  137: "https://polygon-rpc.com",
  43114: "https://api.avax.network/ext/bc/C/rpc",
  42161: "https://arb1.arbitrum.io/rpc",
};

export class DAppBridge {
  private currentChainId: number = 1;
  private currentAccount: string | null = null;
  private walletMode: WalletMode = "soft_wallet";
  private onResponse: ((response: DAppResponse) => void) | null = null;

  setChainId(chainId: number) {
    this.currentChainId = chainId;
  }

  setAccount(account: string | null) {
    this.currentAccount = account;
  }

  setWalletMode(mode: WalletMode) {
    this.walletMode = mode;
  }

  setResponseHandler(handler: (response: DAppResponse) => void) {
    this.onResponse = handler;
  }

  private sendResponse(id: number, result?: any, error?: { code: number; message: string }) {
    if (this.onResponse) {
      this.onResponse({ id, result, error });
    }
  }

  async handleRequest(request: DAppRequest): Promise<void> {
    const { id, method, params } = request;

    try {
      let result: any;

      switch (method) {
        case "eth_requestAccounts":
        case "eth_accounts":
          if (!this.currentAccount) {
            this.sendResponse(id, undefined, { code: 4001, message: "User rejected request" });
            return;
          }
          result = [this.currentAccount];
          break;

        case "eth_chainId":
          result = `0x${this.currentChainId.toString(16)}`;
          break;

        case "net_version":
          result = this.currentChainId.toString();
          break;

        case "wallet_switchEthereumChain":
          const targetChainId = parseInt(params[0]?.chainId, 16);
          if (CHAIN_RPC_URLS[targetChainId]) {
            this.currentChainId = targetChainId;
            result = null;
          } else {
            this.sendResponse(id, undefined, { code: 4902, message: "Chain not supported" });
            return;
          }
          break;

        case "wallet_addEthereumChain":
          this.sendResponse(id, undefined, { code: 4200, message: "Method not supported" });
          return;

        case "eth_getBalance":
          result = await this.rpcCall("eth_getBalance", params);
          break;

        case "eth_blockNumber":
          result = await this.rpcCall("eth_blockNumber", []);
          break;

        case "eth_getTransactionCount":
          result = await this.rpcCall("eth_getTransactionCount", params);
          break;

        case "eth_estimateGas":
          result = await this.rpcCall("eth_estimateGas", params);
          break;

        case "eth_gasPrice":
          result = await this.rpcCall("eth_gasPrice", []);
          break;

        case "eth_call":
          result = await this.rpcCall("eth_call", params);
          break;

        case "eth_getCode":
          result = await this.rpcCall("eth_getCode", params);
          break;

        case "eth_getStorageAt":
          result = await this.rpcCall("eth_getStorageAt", params);
          break;

        case "eth_getTransactionByHash":
          result = await this.rpcCall("eth_getTransactionByHash", params);
          break;

        case "eth_getTransactionReceipt":
          result = await this.rpcCall("eth_getTransactionReceipt", params);
          break;

        case "personal_sign":
          result = await this.signMessage(params[0], params[1]);
          break;

        case "eth_sign":
          result = await this.signMessage(params[1], params[0]);
          break;

        case "eth_signTypedData":
        case "eth_signTypedData_v3":
        case "eth_signTypedData_v4":
          result = await this.signTypedData(params[0], params[1]);
          break;

        case "eth_sendTransaction":
          result = await this.sendTransaction(params[0]);
          break;

        case "eth_signTransaction":
          result = await this.signTransaction(params[0]);
          break;

        default:
          result = await this.rpcCall(method, params);
      }

      this.sendResponse(id, result);
    } catch (error: any) {
      this.sendResponse(id, undefined, {
        code: error.code || 4000,
        message: error.message || "Unknown error",
      });
    }
  }

  private async rpcCall(method: string, params: any[]): Promise<any> {
    const rpcUrl = CHAIN_RPC_URLS[this.currentChainId];
    if (!rpcUrl) throw new Error("Chain not supported");

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

  private async signMessage(message: string, _address: string): Promise<string> {
    if (!this.isWalletUnlocked()) {
      throw new Error("Wallet is locked");
    }

    const decodedMessage = message.startsWith("0x")
      ? Buffer.from(message.slice(2), "hex").toString("utf8")
      : message;

    if (this.walletMode === "hardware") {
      const result = await hardwareWallet.signMessage(decodedMessage);
      if (!result) throw new Error("Failed to sign message");
      return result;
    } else {
      const wallet = getSoftWalletSigner();
      if (!wallet) throw new Error("Wallet not available");
      return await wallet.signMessage(decodedMessage);
    }
  }

  private async signTypedData(_address: string, typedData: string): Promise<string> {
    if (!this.isWalletUnlocked()) {
      throw new Error("Wallet is locked");
    }

    const data = typeof typedData === "string" ? JSON.parse(typedData) : typedData;
    const { domain, types, message } = data;
    
    const filteredTypes = { ...types };
    delete filteredTypes.EIP712Domain;
    
    if (this.walletMode === "hardware") {
      const hash = ethers.TypedDataEncoder.hash(domain, filteredTypes, message);
      const result = await hardwareWallet.signMessage(hash);
      if (!result) throw new Error("Failed to sign typed data");
      return result;
    } else {
      const wallet = getSoftWalletSigner();
      if (!wallet) throw new Error("Wallet not available");
      return await wallet.signTypedData(domain, filteredTypes, message);
    }
  }

  private async signTransaction(txParams: any): Promise<string> {
    if (!this.isWalletUnlocked()) {
      throw new Error("Wallet is locked");
    }

    const tx = {
      to: txParams.to,
      value: txParams.value || "0x0",
      data: txParams.data || "0x",
      gasLimit: txParams.gas || txParams.gasLimit,
      gasPrice: txParams.gasPrice,
      maxFeePerGas: txParams.maxFeePerGas,
      maxPriorityFeePerGas: txParams.maxPriorityFeePerGas,
      nonce: txParams.nonce ? parseInt(txParams.nonce, 16) : undefined,
      chainId: this.currentChainId,
    };

    if (this.walletMode === "hardware") {
      const result = await hardwareWallet.signTransaction(tx);
      if (!result) throw new Error("Failed to sign transaction");
      return result;
    } else {
      const result = await softWallet.signTransaction(tx);
      if (!result) throw new Error("Failed to sign transaction");
      return result;
    }
  }

  private async sendTransaction(txParams: any): Promise<string> {
    const signedTx = await this.signTransaction(txParams);
    
    const rpcUrl = CHAIN_RPC_URLS[this.currentChainId];
    const response = await fetch(rpcUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: Date.now(),
        method: "eth_sendRawTransaction",
        params: [signedTx],
      }),
    });

    const data = await response.json();
    if (data.error) {
      throw new Error(data.error.message);
    }
    return data.result;
  }

  private isWalletUnlocked(): boolean {
    if (this.walletMode === "hardware") {
      return hardwareWallet.getState().status === "unlocked";
    } else {
      return softWallet.isUnlocked();
    }
  }
}

export const dappBridge = new DAppBridge();
