import { clientStorage } from "./client-storage";
import { deriveAllAddresses, type DerivedAddress } from "./multi-chain-address";
import { Mnemonic, HDNodeWallet, type TransactionRequest } from "ethers";
import { 
  signNonEvmTransaction, 
  type NonEvmTransactionParams,
  type SignedTransaction 
} from "./non-evm-chains";

export type SoftWalletStatus = "disconnected" | "locked" | "unlocked";

export interface SoftWalletState {
  status: SoftWalletStatus;
  error: string | null;
  hasWallet: boolean;
}

type StateListener = (state: SoftWalletState) => void;

// Crypto constants
const PBKDF2_ITERATIONS = 100000;
const SALT_LENGTH = 16;
const IV_LENGTH = 12;

class SoftWallet {
  private state: SoftWalletState = {
    status: "disconnected",
    error: null,
    hasWallet: false,
  };
  
  private listeners: Set<StateListener> = new Set();
  private decryptedSeed: string | null = null;
  private sessionTimeout: ReturnType<typeof setTimeout> | null = null;
  private readonly SESSION_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

  getState(): SoftWalletState {
    return { ...this.state };
  }

  subscribe(listener: StateListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private notifyListeners(): void {
    const currentState = this.getState();
    this.listeners.forEach(listener => listener(currentState));
  }

  private setState(updates: Partial<SoftWalletState>): void {
    this.state = { ...this.state, ...updates };
    this.notifyListeners();
  }

  // Derive an AES-GCM key from PIN using PBKDF2
  private async deriveKey(pin: string, salt: Uint8Array): Promise<CryptoKey> {
    const encoder = new TextEncoder();
    const pinBytes = encoder.encode(pin);
    
    // Import PIN as key material
    const keyMaterial = await crypto.subtle.importKey(
      "raw",
      pinBytes,
      "PBKDF2",
      false,
      ["deriveKey"]
    );
    
    // Derive AES-GCM key using PBKDF2
    return await crypto.subtle.deriveKey(
      {
        name: "PBKDF2",
        salt: salt,
        iterations: PBKDF2_ITERATIONS,
        hash: "SHA-256",
      },
      keyMaterial,
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"]
    );
  }

  // Encrypt seed phrase with PIN using AES-GCM
  private async encryptSeed(seed: string, pin: string): Promise<string> {
    const encoder = new TextEncoder();
    const seedBytes = encoder.encode(seed);
    
    // Generate random salt and IV
    const salt = crypto.getRandomValues(new Uint8Array(SALT_LENGTH));
    const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));
    
    // Derive key
    const key = await this.deriveKey(pin, salt);
    
    // Encrypt
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: iv },
      key,
      seedBytes
    );
    
    // Combine salt + iv + ciphertext and encode as base64
    const combined = new Uint8Array(salt.length + iv.length + ciphertext.byteLength);
    combined.set(salt, 0);
    combined.set(iv, salt.length);
    combined.set(new Uint8Array(ciphertext), salt.length + iv.length);
    
    return btoa(String.fromCharCode(...combined));
  }

  // Decrypt seed phrase with PIN using AES-GCM
  private async decryptSeed(encryptedData: string, pin: string): Promise<string> {
    // Decode base64
    const combined = Uint8Array.from(atob(encryptedData), c => c.charCodeAt(0));
    
    // Extract salt, iv, ciphertext
    const salt = combined.slice(0, SALT_LENGTH);
    const iv = combined.slice(SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
    const ciphertext = combined.slice(SALT_LENGTH + IV_LENGTH);
    
    // Derive key
    const key = await this.deriveKey(pin, salt);
    
    // Decrypt
    const decrypted = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: iv },
      key,
      ciphertext
    );
    
    const decoder = new TextDecoder();
    return decoder.decode(decrypted);
  }

  // Create a salted hash of the PIN for verification using PBKDF2
  private async hashPin(pin: string, salt?: Uint8Array): Promise<{ hash: string; salt: string }> {
    const pinSalt = salt || crypto.getRandomValues(new Uint8Array(SALT_LENGTH));
    const encoder = new TextEncoder();
    const pinBytes = encoder.encode(pin);
    
    // Import PIN as key material
    const keyMaterial = await crypto.subtle.importKey(
      "raw",
      pinBytes,
      "PBKDF2",
      false,
      ["deriveBits"]
    );
    
    // Derive bits for hash
    const hashBits = await crypto.subtle.deriveBits(
      {
        name: "PBKDF2",
        salt: pinSalt,
        iterations: PBKDF2_ITERATIONS,
        hash: "SHA-256",
      },
      keyMaterial,
      256
    );
    
    const hashArray = new Uint8Array(hashBits);
    return {
      hash: btoa(String.fromCharCode(...hashArray)),
      salt: btoa(String.fromCharCode(...pinSalt)),
    };
  }

  // Verify PIN against stored hash
  private async verifyPin(pin: string, storedHash: string, storedSalt: string): Promise<boolean> {
    const salt = Uint8Array.from(atob(storedSalt), c => c.charCodeAt(0));
    const { hash } = await this.hashPin(pin, salt);
    return hash === storedHash;
  }

  // Check if wallet is set up (has encrypted seed in storage)
  async checkWalletExists(): Promise<boolean> {
    const hasWallet = await clientStorage.hasEncryptedSeed();
    this.setState({ hasWallet });
    return hasWallet;
  }

  // Set up a new soft wallet with seed phrase and PIN
  async setup(seedPhrase: string, pin: string): Promise<boolean> {
    try {
      const words = seedPhrase.trim().toLowerCase().split(/\s+/);
      if (words.length !== 12 && words.length !== 24) {
        this.setState({ error: "Seed phrase must be 12 or 24 words" });
        return false;
      }

      // Encrypt seed with proper AES-GCM
      const normalizedSeed = words.join(" ");
      const encryptedSeed = await this.encryptSeed(normalizedSeed, pin);
      
      // Create salted PIN hash for verification
      const { hash: pinHash, salt: pinSalt } = await this.hashPin(pin);
      
      // Store with salt
      await clientStorage.saveEncryptedSeed(encryptedSeed, pinHash, pinSalt);
      await clientStorage.setSoftWalletSetup(true);
      
      // Keep decrypted seed in memory for this session
      this.decryptedSeed = normalizedSeed;
      
      this.setState({ 
        status: "unlocked", 
        hasWallet: true, 
        error: null 
      });
      
      this.startSessionTimeout();
      return true;
    } catch (err: any) {
      this.setState({ error: err.message || "Failed to set up wallet" });
      return false;
    }
  }

  // Unlock wallet with PIN
  async unlock(pin: string): Promise<boolean> {
    try {
      const storedPinHash = await clientStorage.getPinHash();
      const storedPinSalt = await clientStorage.getPinSalt();
      const encryptedSeed = await clientStorage.getEncryptedSeed();
      
      if (!storedPinHash || !storedPinSalt || !encryptedSeed) {
        this.setState({ error: "No wallet found. Please set up first." });
        return false;
      }

      // Verify PIN using salted hash
      const isValid = await this.verifyPin(pin, storedPinHash, storedPinSalt);
      if (!isValid) {
        this.setState({ error: "Incorrect PIN" });
        return false;
      }

      // Decrypt seed using AES-GCM
      try {
        this.decryptedSeed = await this.decryptSeed(encryptedSeed, pin);
      } catch {
        this.setState({ error: "Failed to decrypt wallet. Incorrect PIN or corrupted data." });
        return false;
      }
      
      this.setState({ status: "unlocked", error: null });
      this.startSessionTimeout();
      return true;
    } catch (err: any) {
      this.setState({ error: err.message || "Failed to unlock wallet" });
      return false;
    }
  }

  // Lock wallet
  lock(): void {
    this.decryptedSeed = null;
    this.clearSessionTimeout();
    this.setState({ status: "locked", error: null });
  }

  // Get decrypted seed phrase (only available when unlocked)
  getSeedPhrase(): string | null {
    if (this.state.status !== "unlocked") {
      return null;
    }
    return this.decryptedSeed;
  }

  // Derive addresses for all chains
  async deriveAddresses(chainSymbols: string[], accountIndex: number = 0): Promise<DerivedAddress[]> {
    if (this.state.status !== "unlocked" || !this.decryptedSeed) {
      throw new Error("Wallet must be unlocked to derive addresses");
    }
    
    return await deriveAllAddresses(this.decryptedSeed, chainSymbols, accountIndex);
  }

  // Reset/disconnect - clears all stored data
  async reset(): Promise<void> {
    this.decryptedSeed = null;
    this.clearSessionTimeout();
    await clientStorage.clearEncryptedSeed();
    await clientStorage.clearSoftWallet();
    this.setState({ 
      status: "disconnected", 
      hasWallet: false, 
      error: null 
    });
  }

  // Session timeout management
  private startSessionTimeout(): void {
    this.clearSessionTimeout();
    this.sessionTimeout = setTimeout(() => {
      this.lock();
    }, this.SESSION_TIMEOUT_MS);
  }

  private clearSessionTimeout(): void {
    if (this.sessionTimeout) {
      clearTimeout(this.sessionTimeout);
      this.sessionTimeout = null;
    }
  }

  resetSessionTimeout(): void {
    if (this.state.status === "unlocked") {
      this.startSessionTimeout();
    }
  }

  isUnlocked(): boolean {
    return this.state.status === "unlocked";
  }

  // Verify a seed phrase matches the stored wallet's seed
  async verifySeedPhrase(inputSeedPhrase: string): Promise<boolean> {
    if (this.state.status !== "unlocked" || !this.decryptedSeed) {
      return false;
    }
    
    // Normalize the input: trim, lowercase, collapse whitespace
    const inputWords = inputSeedPhrase.trim().toLowerCase().split(/\s+/);
    const storedWords = this.decryptedSeed.split(" ");
    
    // Must have same number of words
    if (inputWords.length !== storedWords.length) {
      return false;
    }
    
    // Compare each word
    for (let i = 0; i < inputWords.length; i++) {
      if (inputWords[i] !== storedWords[i]) {
        return false;
      }
    }
    
    return true;
  }

  // Get the word count of the stored seed (12 or 24)
  getSeedWordCount(): number | null {
    if (this.state.status !== "unlocked" || !this.decryptedSeed) {
      return null;
    }
    return this.decryptedSeed.split(" ").length;
  }

  async signTransaction(unsignedTx: TransactionRequest): Promise<string | null> {
    if (this.state.status !== "unlocked" || !this.decryptedSeed) {
      this.setState({ error: "Wallet must be unlocked to sign transactions" });
      return null;
    }

    try {
      const mnemonic = Mnemonic.fromPhrase(this.decryptedSeed);
      const hdNode = HDNodeWallet.fromMnemonic(mnemonic, "m/44'/60'/0'/0/0");
      const signedTx = await hdNode.signTransaction(unsignedTx);
      return signedTx;
    } catch (error: any) {
      this.setState({ error: error.message || "Failed to sign transaction" });
      return null;
    }
  }

  async signNonEvmTransaction(params: NonEvmTransactionParams): Promise<SignedTransaction | null> {
    if (this.state.status !== "unlocked" || !this.decryptedSeed) {
      this.setState({ error: "Wallet must be unlocked to sign transactions" });
      return null;
    }

    try {
      const result = await signNonEvmTransaction(params, this.decryptedSeed);
      if (!result) {
        this.setState({ error: "Failed to sign non-EVM transaction" });
        return null;
      }
      return result;
    } catch (error: any) {
      this.setState({ error: error.message || "Failed to sign non-EVM transaction" });
      return null;
    }
  }

  async getAddress(chainId?: number): Promise<string | null> {
    if (this.state.status !== "unlocked" || !this.decryptedSeed) {
      return null;
    }

    try {
      const mnemonic = Mnemonic.fromPhrase(this.decryptedSeed);
      const hdNode = HDNodeWallet.fromMnemonic(mnemonic, "m/44'/60'/0'/0/0");
      return hdNode.address;
    } catch {
      return null;
    }
  }

  // Encrypt a seed phrase for a new wallet group (independent seed)
  async encryptSeedForWalletGroup(
    seedPhrase: string,
    pin: string,
    walletGroupId: string
  ): Promise<{ encryptedSeed: string; pinHash: string; pinSalt: string }> {
    const words = seedPhrase.trim().toLowerCase().split(/\s+/);
    if (words.length !== 12 && words.length !== 24) {
      throw new Error("Seed phrase must be 12 or 24 words");
    }

    const normalizedSeed = words.join(" ");
    const encryptedSeed = await this.encryptSeed(normalizedSeed, pin);
    const { hash: pinHash, salt: pinSalt } = await this.hashPin(pin);

    return { encryptedSeed, pinHash, pinSalt };
  }

  // Decrypt a seed for a specific wallet group
  async decryptWalletGroupSeed(encryptedSeed: string, pin: string): Promise<string> {
    return await this.decryptSeed(encryptedSeed, pin);
  }

  // Verify PIN for a wallet group
  async verifyWalletGroupPin(pin: string, storedHash: string, storedSalt: string): Promise<boolean> {
    return await this.verifyPin(pin, storedHash, storedSalt);
  }

  // Generate a new random seed phrase
  generateNewSeedPhrase(): string {
    const entropy = crypto.getRandomValues(new Uint8Array(16)); // 128 bits = 12 words
    return Mnemonic.entropyToPhrase(entropy);
  }
}

export const softWallet = new SoftWallet();
