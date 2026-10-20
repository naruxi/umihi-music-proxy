package ca.ilianokokoro.umihi.music.proxy;

import android.util.Log;

import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.SystemProxyStatus;

/**
 * Lifecycle callbacks libbox invokes from Go. All of them are advisory for our use case:
 * we own the process, so reload/stop requests are driven by the app, not by the core.
 */
public class ProxyServerHandler implements CommandServerHandler {

    private static final String TAG = "UmihiProxy";

    @Override
    public int connectSSHAgent() {
        return -1;
    }

    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        SystemProxyStatus status = new SystemProxyStatus();
        status.setAvailable(false);
        status.setEnabled(false);
        return status;
    }

    @Override
    public void serviceReload() {
    }

    @Override
    public void serviceStop() {
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) {
    }

    @Override
    public void triggerNativeCrash() {
    }

    @Override
    public void writeDebugMessage(String message) {
        Log.d(TAG, message);
    }
}
