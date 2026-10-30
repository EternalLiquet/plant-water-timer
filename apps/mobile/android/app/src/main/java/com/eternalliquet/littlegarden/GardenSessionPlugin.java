package com.eternalliquet.littlegarden;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.eternalliquet.littlegarden.transport.GardenSessionBridge;
import com.eternalliquet.littlegarden.transport.GardenSessionTransport;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@CapacitorPlugin(name = "GardenSession")
public final class GardenSessionPlugin extends Plugin {
    // No endpoint or runtime switch: enable only after Android acceptance in a reviewed change.
    private final GardenSessionBridge session = new GardenSessionBridge(null);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @PluginMethod public void availability(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", session.available());
        call.resolve(result);
    }

    @PluginMethod public void session(PluginCall call) { run(call, session::session); }

    @PluginMethod public void login(PluginCall call) {
        String password = call.getString("password");
        call.getData().remove("password");
        run(call, () -> session.login(password));
    }

    @PluginMethod public void logout(PluginCall call) { run(call, session::logout); }

    private interface Operation { GardenSessionTransport.Session run() throws Exception; }
    private void run(PluginCall call, Operation operation) {
        try {
            worker.execute(() -> {
                try {
                    GardenSessionTransport.Session value = operation.run();
                    JSObject result = new JSObject();
                    result.put("authenticated", value.authenticated);
                    result.put("configured", value.configured);
                    call.resolve(result);
                } catch (GardenSessionBridge.Unavailable error) {
                    call.reject("Sign-in is not available in this build.", "NATIVE_VALIDATION_REQUIRED");
                } catch (GardenSessionTransport.Failure error) {
                    call.reject("Session request failed.", error.reason.name());
                } catch (Exception error) {
                    call.reject("Session request failed.", "UNEXPECTED");
                }
            });
        } catch (RejectedExecutionException error) {
            call.reject("Session request failed.", "UNAVAILABLE");
        }
    }

    @Override protected void handleOnDestroy() {
        // Close after queued work so a late login cannot recreate credentials after destruction.
        worker.execute(session::close);
        worker.shutdown();
        super.handleOnDestroy();
    }
}
