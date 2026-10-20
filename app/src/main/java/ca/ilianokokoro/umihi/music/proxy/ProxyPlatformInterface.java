package ca.ilianokokoro.umihi.music.proxy;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

/**
 * Minimal libbox platform bridge.
 *
 * Umihi never creates a TUN device — it only needs a loopback mixed inbound that its own
 * HTTP client and media player dial. Everything VPN-shaped (openTun, interface monitoring,
 * neighbors, Tailscale, SSH) therefore stays inert. Returning null here is safe because
 * sing-box only queries those callbacks when a TUN inbound or auto-detect interface is
 * configured, neither of which this config uses.
 *
 * Written in Java rather than Kotlin on purpose: gomobile's generated interfaces carry no
 * useful nullability annotations, and Kotlin's stricter override rules fight with the
 * deliberate nulls.
 */
public class ProxyPlatformInterface implements PlatformInterface {

    @Override
    public void autoDetectInterfaceControl(int fd) {
        // no TUN, nothing to protect
    }

    @Override
    public void cancelNotification(String tag, int id) {
    }

    @Override
    public void checkPlatformShell() {
    }

    @Override
    public void clearDNSCache() {
        // Resolver is created per service instance, nothing cached globally.
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public BridgeSession createBridge(BridgeOptions options) {
        return null;
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort,
                                              String destinationAddress, int destinationPort) {
        return null;
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        return null;
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        // The config pins 223.5.5.5 as bootstrap resolver, so no platform DNS bridge is needed.
        return null;
    }

    @Override
    public String lookupSFTPServer() {
        return null;
    }

    @Override
    public PlatformUser lookupUser(String username) {
        return null;
    }

    @Override
    public ShellSession openShellSession(PlatformUser user, String shell, StringIterator env,
                                        String term, int width, int height) {
        return null;
    }

    @Override
    public int openTun(TunOptions options) {
        return -1;
    }

    @Override
    public String readSystemSSHHostKey() {
        return null;
    }

    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public void registerMyInterface(String name) {
    }

    @Override
    public void sendNotification(Notification notification) {
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public String tailscaleHostname() {
        return null;
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return false;
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public boolean useProcFS() {
        return false;
    }
}
