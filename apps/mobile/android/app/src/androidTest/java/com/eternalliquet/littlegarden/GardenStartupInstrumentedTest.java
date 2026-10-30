package com.eternalliquet.littlegarden;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run only on a disposable test device. Uses the packaged WebView, plugin and database;
 * no fake store, clear-data, deletion, database reset or online calls. */
@RunWith(AndroidJUnit4.class)
public class GardenStartupInstrumentedTest {
    @Test public void packagedGardenOpensAgainAfterActivityRestart() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        for (int launch = 0; launch < 2; launch++) {
            Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
            try {
                long deadline = SystemClock.elapsedRealtime() + 20000;
                String status = "\"waiting\"";
                while (SystemClock.elapsedRealtime() < deadline) {
                    CountDownLatch resultReady = new CountDownLatch(1);
                    AtomicReference<String> result = new AtomicReference<>();
                    instrumentation.runOnMainSync(() -> activity.getBridge().getWebView().evaluateJavascript(
                        "(() => { const summary = document.getElementById('care-summary'); " +
                        "if (!summary) return 'waiting'; " +
                        "if (summary.textContent === 'The phone garden could not open') return 'failed'; " +
                        "if (!/saved on this phone$/.test(summary.textContent)) return 'waiting'; " +
                        "if (!window.Capacitor?.PluginHeaders?.some(p => p.name === 'CapacitorSQLite')) return 'missing-plugin'; " +
                        "return document.getElementById('add-open').disabled ? 'disabled' : 'ready'; })()",
                        value -> { result.set(value); resultReady.countDown(); }));
                    assertTrue("WebView callback timeout", resultReady.await(5, TimeUnit.SECONDS));
                    status = result.get();
                    if (!"\"waiting\"".equals(status)) break;
                    SystemClock.sleep(100);
                }
                assertEquals("Packaged startup " + launch, "\"ready\"", status);
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }
}
