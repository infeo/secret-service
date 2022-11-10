package de.swiesend.secret.functional.interfaces;

import de.swiesend.secret.functional.Session;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public abstract class ServiceInterface implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServiceInterface.class);

    public static Optional<ServiceInterface> create() {
        log.warn("Do not call the interface method, but the implementation.");
        return Optional.empty();
    }

    synchronized public static boolean disconnect() {
        log.warn("Do not call the interface method, but the implementation.");
        return false;
    }

    private static Optional<DBusConnection> getConnection() {
        log.warn("Do not call the interface method, but the implementation.");
        return Optional.empty();
    }

    public static boolean isAvailable() {
        log.warn("Do not call the interface method, but the implementation.");
        return false;
    }

    public static boolean isConnected() {
        log.warn("Do not call the interface method, but the implementation.");
        return false;
    }

    private static Optional<Thread> setupShutdownHook() {
        log.warn("Do not call the interface method, but the implementation.");
        return Optional.empty();
    }

    abstract public boolean clear();

    abstract public Optional<Session> openSession();

    abstract public List<SessionInterface> getSessions();

    abstract public void registerSession(Session session);

    abstract public void unregisterSession(Session session);

    abstract public Duration getTimeout();

    abstract public void setTimeout(Duration timeout);

    abstract public org.freedesktop.secret.Service getService();

    abstract public boolean isOrgGnomeKeyringAvailable();

}
