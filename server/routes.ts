import type { Express } from "express";
import { createServer, type Server } from "http";
import { WebSocketServer, WebSocket } from "ws";
import { storage } from "./storage";

// Bridge state for mobile-to-desktop Pico connection
interface BridgeSession {
  desktopWs: WebSocket | null;
  mobileWs: WebSocket | null;
  sessionId: string;
  createdAt: number;
}

const bridgeSessions: Map<string, BridgeSession> = new Map();

function generateSessionId(): string {
  return Math.random().toString(36).substring(2, 8).toUpperCase();
}

// Chain RPC endpoints for gas estimation
const CHAIN_RPC_ENDPOINTS: Record<string, string> = {
  'chain-0': 'https://eth.llamarpc.com',           // Ethereum
  'chain-1': 'https://bitcoin.drpc.org',           // Bitcoin (not used for gas)
  'chain-2': 'https://api.mainnet-beta.solana.com', // Solana
  'chain-3': 'https://bsc-dataseed1.binance.org',  // BNB Chain
  'chain-4': 'https://polygon-rpc.com',            // Polygon
  'chain-5': 'https://api.avax.network/ext/bc/C/rpc', // Avalanche
  'chain-6': 'https://arb1.arbitrum.io/rpc',       // Arbitrum
  'chain-7': 'https://mainnet.optimism.io',        // Optimism
  'chain-8': 'https://api.trongrid.io',            // TRON
};

// Default gas limits for native transfers
const DEFAULT_GAS_LIMITS: Record<string, number> = {
  'chain-0': 21000, // Ethereum
  'chain-3': 21000, // BNB Chain
  'chain-4': 21000, // Polygon
  'chain-5': 21000, // Avalanche
  'chain-6': 21000, // Arbitrum
  'chain-7': 21000, // Optimism
};

// Gas limits for ERC20 token transfers (higher due to contract interaction)
const TOKEN_GAS_LIMITS: Record<string, number> = {
  'chain-0': 65000, // Ethereum ERC20
  'chain-3': 65000, // BNB Chain BEP20
  'chain-4': 65000, // Polygon ERC20
  'chain-5': 65000, // Avalanche ERC20
  'chain-6': 65000, // Arbitrum ERC20
  'chain-7': 65000, // Optimism ERC20
};

// EVM chain IDs that support eth_gasPrice
const EVM_CHAINS = new Set(['chain-0', 'chain-3', 'chain-4', 'chain-5', 'chain-6', 'chain-7']);

async function fetchGasPrice(chainId: string): Promise<{ gasPriceWei: bigint; gasPriceGwei: string } | null> {
  const rpcUrl = CHAIN_RPC_ENDPOINTS[chainId];
  if (!rpcUrl) return null;

  try {
    // Only EVM chains support eth_gasPrice
    if (EVM_CHAINS.has(chainId)) {
      const response = await fetch(rpcUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0',
          method: 'eth_gasPrice',
          params: [],
          id: 1,
        }),
      });
      
      const data = await response.json();
      if (data.result) {
        const gasPriceWei = BigInt(data.result);
        const gasPriceGwei = (Number(gasPriceWei) / 1e9).toFixed(2);
        return { gasPriceWei, gasPriceGwei };
      }
    }
  } catch (error) {
    console.error(`Failed to fetch gas price for ${chainId}:`, error);
  }
  
  return null;
}

export async function registerRoutes(
  httpServer: Server,
  app: Express
): Promise<Server> {
  
  // Gas estimate endpoint
  app.get('/api/gas-estimate', async (req, res) => {
    const chainId = req.query.chainId as string;
    const isNative = req.query.isNative !== 'false'; // Default to true (native transfer)
    
    if (!chainId) {
      return res.json({
        gasPrice: '0',
        gasPriceGwei: '20',
        estimatedGas: '21000',
        estimatedFee: '0.00042',
        estimatedFeeUsd: null,
        symbol: 'ETH',
        error: 'No chain specified',
      });
    }

    // Determine chain symbol
    const chainSymbols: Record<string, string> = {
      'chain-0': 'ETH',     // Ethereum
      'chain-1': 'BTC',     // Bitcoin
      'chain-2': 'SOL',     // Solana
      'chain-3': 'BNB',     // BNB Chain
      'chain-4': 'MATIC',   // Polygon
      'chain-5': 'AVAX',    // Avalanche
      'chain-6': 'ETH',     // Arbitrum (uses ETH)
      'chain-7': 'ETH',     // Optimism (uses ETH)
      'chain-8': 'TRX',     // TRON
    };
    const symbol = chainSymbols[chainId] || 'ETH';

    // Fetch real gas price for EVM chains
    const gasData = await fetchGasPrice(chainId);
    
    if (gasData) {
      // Use higher gas limit for token transfers vs native transfers
      const gasLimit = isNative 
        ? (DEFAULT_GAS_LIMITS[chainId] || 21000)
        : (TOKEN_GAS_LIMITS[chainId] || 65000);
      const estimatedFeeWei = gasData.gasPriceWei * BigInt(gasLimit);
      const estimatedFee = (Number(estimatedFeeWei) / 1e18).toFixed(6);
      
      return res.json({
        gasPrice: gasData.gasPriceWei.toString(),
        gasPriceGwei: gasData.gasPriceGwei,
        estimatedGas: gasLimit.toString(),
        estimatedFee,
        estimatedFeeUsd: null, // Would need price data to calculate
        symbol,
        isTokenTransfer: !isNative,
      });
    }

    // Fallback for non-EVM chains or errors
    const fallbackFees: Record<string, { fee: string; unit: string }> = {
      'chain-1': { fee: '0.00001', unit: 'BTC' }, // Bitcoin
      'chain-2': { fee: '0.000005', unit: 'SOL' }, // Solana
      'chain-8': { fee: '0', unit: 'TRX' }, // TRON (free for most transfers)
    };

    const fallback = fallbackFees[chainId] || { fee: '0.0001', unit: symbol };
    
    return res.json({
      gasPrice: '0',
      gasPriceGwei: chainId === 'chain-0' || chainId === 'chain-3' ? '20' : 'N/A',
      estimatedGas: DEFAULT_GAS_LIMITS[chainId]?.toString() || 'N/A',
      estimatedFee: fallback.fee,
      estimatedFeeUsd: null,
      symbol: fallback.unit,
      error: 'Using estimated values',
    });
  });

  // Bridge session management API
  app.post('/api/bridge/create', (req, res) => {
    const sessionId = generateSessionId();
    bridgeSessions.set(sessionId, {
      desktopWs: null,
      mobileWs: null,
      sessionId,
      createdAt: Date.now()
    });
    
    // Clean up old sessions (older than 1 hour)
    const oneHourAgo = Date.now() - 60 * 60 * 1000;
    Array.from(bridgeSessions.entries()).forEach(([id, session]) => {
      if (session.createdAt < oneHourAgo) {
        bridgeSessions.delete(id);
      }
    });
    
    res.json({ sessionId });
  });
  
  app.get('/api/bridge/status/:sessionId', (req, res) => {
    const session = bridgeSessions.get(req.params.sessionId);
    if (!session) {
      return res.json({ exists: false });
    }
    res.json({
      exists: true,
      desktopConnected: session.desktopWs !== null && session.desktopWs.readyState === WebSocket.OPEN,
      mobileConnected: session.mobileWs !== null && session.mobileWs.readyState === WebSocket.OPEN
    });
  });

  // WebSocket server for bridge communication
  const wss = new WebSocketServer({ server: httpServer, path: '/ws/bridge' });
  
  wss.on('connection', (ws, req) => {
    const url = new URL(req.url || '', `http://${req.headers.host}`);
    const sessionId = url.searchParams.get('sessionId');
    const role = url.searchParams.get('role'); // 'desktop' or 'mobile'
    
    if (!sessionId || !role) {
      ws.close(1008, 'Missing sessionId or role');
      return;
    }
    
    let session = bridgeSessions.get(sessionId);
    if (!session) {
      // Auto-create session for desktop
      if (role === 'desktop') {
        session = {
          desktopWs: null,
          mobileWs: null,
          sessionId,
          createdAt: Date.now()
        };
        bridgeSessions.set(sessionId, session);
      } else {
        ws.close(1008, 'Session not found');
        return;
      }
    }
    
    if (role === 'desktop') {
      session.desktopWs = ws;
      // Notify mobile if connected
      if (session.mobileWs && session.mobileWs.readyState === WebSocket.OPEN) {
        session.mobileWs.send(JSON.stringify({ type: 'desktop_connected' }));
      }
    } else if (role === 'mobile') {
      session.mobileWs = ws;
      // Notify desktop if connected
      if (session.desktopWs && session.desktopWs.readyState === WebSocket.OPEN) {
        session.desktopWs.send(JSON.stringify({ type: 'mobile_connected' }));
      }
    }
    
    ws.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString());
        const currentSession = bridgeSessions.get(sessionId);
        if (!currentSession) return;
        
        // Validate message has required fields
        if (typeof message !== 'object' || !message.type) {
          ws.send(JSON.stringify({ type: 'error', error: 'Invalid message format' }));
          return;
        }
        
        // Relay messages between desktop and mobile with role validation
        if (role === 'desktop' && currentSession.mobileWs && currentSession.mobileWs.readyState === WebSocket.OPEN) {
          currentSession.mobileWs.send(JSON.stringify({ ...message, from: 'desktop' }));
        } else if (role === 'mobile' && currentSession.desktopWs && currentSession.desktopWs.readyState === WebSocket.OPEN) {
          currentSession.desktopWs.send(JSON.stringify({ ...message, from: 'mobile' }));
        } else if (role === 'mobile' && (!currentSession.desktopWs || currentSession.desktopWs.readyState !== WebSocket.OPEN)) {
          // Desktop not connected - notify mobile
          ws.send(JSON.stringify({ type: 'error', error: 'Desktop not connected' }));
        }
      } catch (e) {
        console.error('Bridge message error:', e);
        ws.send(JSON.stringify({ type: 'error', error: 'Failed to process message' }));
      }
    });
    
    ws.on('close', () => {
      const currentSession = bridgeSessions.get(sessionId);
      if (!currentSession) return;
      
      if (role === 'desktop') {
        currentSession.desktopWs = null;
        if (currentSession.mobileWs && currentSession.mobileWs.readyState === WebSocket.OPEN) {
          currentSession.mobileWs.send(JSON.stringify({ type: 'desktop_disconnected' }));
        }
      } else if (role === 'mobile') {
        currentSession.mobileWs = null;
        if (currentSession.desktopWs && currentSession.desktopWs.readyState === WebSocket.OPEN) {
          currentSession.desktopWs.send(JSON.stringify({ type: 'mobile_disconnected' }));
        }
      }
      
      // Clean up session when both peers disconnect
      if (!currentSession.desktopWs && !currentSession.mobileWs) {
        bridgeSessions.delete(sessionId);
      }
    });
    
    // Send connection confirmation
    ws.send(JSON.stringify({ type: 'connected', role, sessionId }));
  });

  return httpServer;
}
