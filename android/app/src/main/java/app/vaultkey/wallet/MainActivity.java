package app.vaultkey.wallet;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UsbSerialPlugin.class);
        registerPlugin(DAppBrowserPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
