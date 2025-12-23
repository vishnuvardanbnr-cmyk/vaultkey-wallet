package app.vaultkey.wallet;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbConstants;

@CapacitorPlugin(name = "UsbSerial")
public class UsbSerialPlugin extends Plugin {
    private static final String TAG = "UsbSerialPlugin";
    private static final String ACTION_USB_PERMISSION = "app.vaultkey.wallet.USB_PERMISSION";
    
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private boolean isConnected = false;
    private ExecutorService commandExecutor = Executors.newCachedThreadPool();
    private Thread listenerThread;
    private StringBuilder readBuffer = new StringBuilder();
    private PluginCall pendingCall;
    
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (dev != null && pendingCall != null) {
                            openConnection(dev, pendingCall);
                        }
                    } else {
                        Log.d(TAG, "Permission denied for device " + dev);
                        if (pendingCall != null) {
                            JSObject ret = new JSObject();
                            ret.put("success", false);
                            ret.put("error", "USB permission denied");
                            pendingCall.resolve(ret);
                            pendingCall = null;
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null) {
                    Log.d(TAG, "USB device attached: " + dev.getDeviceName());
                    JSObject deviceInfo = new JSObject();
                    deviceInfo.put("deviceId", dev.getDeviceId());
                    deviceInfo.put("vendorId", dev.getVendorId());
                    deviceInfo.put("productId", dev.getProductId());
                    deviceInfo.put("deviceName", dev.getDeviceName());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        deviceInfo.put("productName", dev.getProductName());
                        deviceInfo.put("manufacturerName", dev.getManufacturerName());
                    }
                    JSObject event = new JSObject();
                    event.put("device", deviceInfo);
                    notifyListeners("usbAttached", event);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null && device != null && dev.equals(device)) {
                    closeConnection();
                    notifyListeners("usbDisconnected", new JSObject());
                }
            }
        }
    };

    @Override
    public void load() {
        usbManager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(usbReceiver, filter);
        }
    }

    @PluginMethod
    public void getDevices(PluginCall call) {
        JSObject ret = new JSObject();
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        
        JSObject devices = new JSObject();
        for (UsbDevice dev : deviceList.values()) {
            JSObject deviceInfo = new JSObject();
            deviceInfo.put("deviceId", dev.getDeviceId());
            deviceInfo.put("vendorId", dev.getVendorId());
            deviceInfo.put("productId", dev.getProductId());
            deviceInfo.put("deviceName", dev.getDeviceName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                deviceInfo.put("productName", dev.getProductName());
                deviceInfo.put("manufacturerName", dev.getManufacturerName());
            }
            devices.put(String.valueOf(dev.getDeviceId()), deviceInfo);
        }
        
        ret.put("success", true);
        ret.put("devices", devices);
        ret.put("count", deviceList.size());
        call.resolve(ret);
    }

    @PluginMethod
    public void connect(PluginCall call) {
        int vendorId = call.getInt("vendorId", 11914);
        int productId = call.getInt("productId", 5);
        
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        UsbDevice targetDevice = null;
        
        for (UsbDevice dev : deviceList.values()) {
            if (dev.getVendorId() == vendorId) {
                if (productId == 0 || dev.getProductId() == productId) {
                    targetDevice = dev;
                    break;
                }
            }
        }
        
        if (targetDevice == null) {
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("error", "No matching USB device found. Please connect your Pico wallet.");
            call.resolve(ret);
            return;
        }
        
        if (usbManager.hasPermission(targetDevice)) {
            openConnection(targetDevice, call);
        } else {
            pendingCall = call;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(getContext(), 0, 
                new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(targetDevice, permissionIntent);
        }
    }
    
    private void openConnection(UsbDevice dev, PluginCall call) {
        try {
            device = dev;
            connection = usbManager.openDevice(device);
            
            if (connection == null) {
                JSObject ret = new JSObject();
                ret.put("success", false);
                ret.put("error", "Failed to open USB connection");
                call.resolve(ret);
                return;
            }
            
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface usbInterface = device.getInterface(i);
                if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA ||
                    usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) {
                    
                    connection.claimInterface(usbInterface, true);
                    
                    for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                        UsbEndpoint endpoint = usbInterface.getEndpoint(j);
                        if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                                endpointIn = endpoint;
                            } else {
                                endpointOut = endpoint;
                            }
                        }
                    }
                }
            }
            
            if (endpointIn == null || endpointOut == null) {
                JSObject ret = new JSObject();
                ret.put("success", false);
                ret.put("error", "USB endpoints not found. Device may not be in CDC mode.");
                call.resolve(ret);
                return;
            }
            
            isConnected = true;
            startReadThread();
            
            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("deviceName", device.getDeviceName());
            call.resolve(ret);
            
        } catch (Exception e) {
            Log.e(TAG, "Error opening connection", e);
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("error", e.getMessage());
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeConnection();
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }
    
    private void closeConnection() {
        isConnected = false;
        if (connection != null) {
            connection.close();
            connection = null;
        }
        device = null;
        endpointIn = null;
        endpointOut = null;
    }

    @PluginMethod
    public void write(PluginCall call) {
        String data = call.getString("data", "");
        
        if (!isConnected || connection == null || endpointOut == null) {
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("error", "Not connected");
            call.resolve(ret);
            return;
        }
        
        commandExecutor.execute(() -> {
            try {
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                int result = connection.bulkTransfer(endpointOut, bytes, bytes.length, 5000);
                
                JSObject ret = new JSObject();
                ret.put("success", result >= 0);
                ret.put("bytesWritten", result);
                if (result < 0) {
                    ret.put("error", "Write failed");
                }
                
                getActivity().runOnUiThread(() -> call.resolve(ret));
            } catch (Exception e) {
                JSObject ret = new JSObject();
                ret.put("success", false);
                ret.put("error", e.getMessage());
                getActivity().runOnUiThread(() -> call.resolve(ret));
            }
        });
    }

    @PluginMethod
    public void read(PluginCall call) {
        int timeout = call.getInt("timeout", 5000);
        
        if (!isConnected || connection == null || endpointIn == null) {
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("error", "Not connected");
            call.resolve(ret);
            return;
        }
        
        commandExecutor.execute(() -> {
            try {
                byte[] buffer = new byte[1024];
                int bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.length, timeout);
                
                JSObject ret = new JSObject();
                if (bytesRead > 0) {
                    String dataStr = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    ret.put("success", true);
                    ret.put("data", dataStr);
                    ret.put("bytesRead", bytesRead);
                } else {
                    ret.put("success", false);
                    ret.put("error", "No data received");
                }
                
                getActivity().runOnUiThread(() -> call.resolve(ret));
            } catch (Exception e) {
                JSObject ret = new JSObject();
                ret.put("success", false);
                ret.put("error", e.getMessage());
                getActivity().runOnUiThread(() -> call.resolve(ret));
            }
        });
    }

    @PluginMethod
    public void isConnected(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("connected", isConnected);
        call.resolve(ret);
    }
    
    private void startReadThread() {
        listenerThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (isConnected && connection != null && endpointIn != null) {
                try {
                    int bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.length, 100);
                    if (bytesRead > 0) {
                        String data = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                        JSObject event = new JSObject();
                        event.put("data", data);
                        notifyListeners("usbData", event);
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }, "UsbSerialListener");
        listenerThread.start();
    }

    @Override
    protected void handleOnDestroy() {
        try {
            getContext().unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
        closeConnection();
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        commandExecutor.shutdown();
        super.handleOnDestroy();
    }
}
