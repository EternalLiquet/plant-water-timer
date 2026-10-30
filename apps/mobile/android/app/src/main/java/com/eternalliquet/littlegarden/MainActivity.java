package com.eternalliquet.littlegarden;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GardenPhotosPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
