package ca.ilianokokoro.umihi.music.proxy;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterface;
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
 * neighbors, Tailscale, SSH) therefore stays inert.
 *
 * Written in Java rather than Kotlin on purpose: gomobile's generated interfaces carry no
 * useful nullability annotations, and Kotlin's stricter override rules fight with the
 * deliberate nulls.
 *
 * Every callback that the core could plausibly reach during a plain proxy session is traced,
 * because a crash inside Go gives us no stack trace of its own — the last traced callback is
 * what identifies the culprit.
 */
public class ProxyPlatformInterface implements PlatformInterface {

    /** Shared empty iterator for callbacks whose result Go walks without a nil check. */
    private static final StringIterator EMPTY_STRINGS = new StringIterator() {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public String next() {
            return null;
        }

        @Override
        public int len() {
            return 0;
        }
    };

    @Override
    public void autoDetectInterfaceControl(int fd) {
        CrashGuard.INSTANCE.trace("cb:autoDetectInterfaceControl");
    }

    @Override
    public void cancelNotification(String tag, int id) {
    }

    @Override
    public void checkPlatformShell() {
    }

    @Override
    public void clearDNSCache() {
        CrashGuard.INSTANCE.trace("cb:clearDNSCache");
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        CrashGuard.INSTANCE.trace("cb:closeDefaultInterfaceMonitor");
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public BridgeSession createBridge(BridgeOptions options) {
        return null;
    }

    /**
     * Must never return null.
     *
     * sing-box asks for the connection owner on *every* connection it routes
     * (`Router.prepareMatchMetadata` -> `searchProcessInfo` -> `platformSearcher.FindProcessInfo`),
     * not only when a process/package rule exists. libbox dereferences the returned struct
     * unconditionally at `experimental/libbox/service.go:226`, so returning null is an
     * immediate SIGSEGV inside the Go runtime — which surfaces as an uncatchable SIGABRT and
     * kills the whole app the moment the first proxied request goes out.
     *
     * An "unknown owner" with empty fields is the correct answer here: the loopback inbound
     * always originates from this very process, and no rule in our config matches on it.
     */
    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort,
                                              String destinationAddress, int destinationPort) {
        CrashGuard.INSTANCE.trace("cb:findConnectionOwner " + sourceAddress + ":" + sourcePort);
        ConnectionOwner owner = new ConnectionOwner();
        owner.setUserId(-1);
        owner.setUserName("");
        owner.setProcessPath("");
        owner.setAndroidPackageNames(EMPTY_STRINGS);
        return owner;
    }

    /**
     * Note: returning null here used to kill the process. gomobile proxies the return value
     * straight into a Go interface, and a nil there is dereferenced without a check — an
     * empty iterator keeps the core happy while meaning "no interfaces to report".
     */
    @Override
    public NetworkInterfaceIterator getInterfaces() {
        CrashGuard.INSTANCE.trace("cb:getInterfaces");
        return new NetworkInterfaceIterator() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public NetworkInterface next() {
                return null;
            }
        };
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
        CrashGuard.INSTANCE.trace("cb:openTun (unexpected)");
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
        CrashGuard.INSTANCE.trace("cb:registerMyInterface " + name);
    }

    @Override
    public void sendNotification(Notification notification) {
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        CrashGuard.INSTANCE.trace("cb:startDefaultInterfaceMonitor");
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
