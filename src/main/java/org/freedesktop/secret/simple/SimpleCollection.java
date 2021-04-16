package org.freedesktop.secret.simple;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.ObjectPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.types.Variant;
import org.freedesktop.secret.*;
import org.freedesktop.secret.interfaces.Prompt.Completed;
import org.gnome.keyring.InternalUnsupportedGuiltRiddenInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.security.AccessControlException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.freedesktop.secret.Static.DBus.DEFAULT_DELAY_MILLIS;
import static org.freedesktop.secret.Static.DEFAULT_PROMPT_TIMEOUT;

public final class SimpleCollection extends org.freedesktop.secret.simple.interfaces.SimpleCollection {

    private static final Logger log = LoggerFactory.getLogger(SimpleCollection.class);
    private static boolean testing = false;
    private static Lock disconnectLock = new ReentrantLock();
    private static DBusConnection connection = getConnection();
    private TransportEncryption transport = null;
    private Service service = null;
    private Session session = null;
    private Prompt prompt = null;
    private InternalUnsupportedGuiltRiddenInterface withoutPrompt = null;
    private Collection collection;
    private Secret encrypted = null;
    private Duration timeout = DEFAULT_PROMPT_TIMEOUT;
    private Boolean isUnlockedOnceWithUserPermission = false;
    private String collectionLabel = "";

    /**
     * The default collection.
     *
     * @throws IOException Could not communicate properly with the D-Bus. Check the logs.
     */
    public SimpleCollection() throws IOException {
        try {
            init();
            ObjectPath path = Static.Convert.toObjectPath(Static.ObjectPaths.DEFAULT_COLLECTION);
            collection = new Collection(path, service);
        } catch (RuntimeException e) {
            throw new IOException("Could not initialize the secret service.", e);
        }
    }

    /**
     * A user specified collection.
     *
     * @param label    The displayable label of the collection
     *
     *                 <p>
     *                 NOTE: The <code>label</code> of a collection may differ from the <code>id</code> of a collection.
     *                 The <code>id</code> is assigned by the Secret Service and used in the DBus object path of a
     *                 collection or item.
     *                 </p>
     *                 <p>
     *                 The SimpleCollection can't handle collections with the same label, but different ids correctly,
     *                 as the <code>id</code> is inferred by the given label.
     * @param password Password of the collection
     * @throws IOException Could not communicate properly with the D-Bus. Check the logs.
     */
    public SimpleCollection(String label, CharSequence password) throws IOException {
        try {
            init();
            if (password != null) {
                try {
                    encrypted = transport.encrypt(password);
                } catch (NoSuchAlgorithmException |
                        NoSuchPaddingException |
                        InvalidAlgorithmParameterException |
                        InvalidKeyException |
                        BadPaddingException |
                        IllegalBlockSizeException e) {
                    log.error("Could not establish transport encryption.", e);
                }
            }
            if (exists(label)) {
                ObjectPath path = getCollectionPath(label);
                collection = new Collection(path, service);
            } else {
                DBusPath path = null;
                Map<String, Variant> properties = Collection.createProperties(label);

                if (password == null) {
                    Pair<ObjectPath, ObjectPath> response = service.createCollection(properties);
                    if (!"/".equals(response.a.getPath())) {
                        path = response.a;
                    }
                    performPrompt(response.b);
                } else if (encrypted != null) {
                    path = withoutPrompt.createWithMasterPassword(properties, encrypted);
                }

                if (path == null) {
                    try {
                        Thread.currentThread().sleep(DEFAULT_DELAY_MILLIS);
                    } catch (InterruptedException e) {
                        log.error("Unexpected interrupt while waiting for a CollectionCreated signal.", e);
                    }
                    Service.CollectionCreated cc = service.getSignalHandler().getLastHandledSignal(Service.CollectionCreated.class);
                    path = cc.collection;
                }

                if (path == null) throw new IOException("Could not acquire a path for the prompt.");

                collection = new Collection(path, service);
            }
        } catch (RuntimeException e) {
            throw new IOException("Could not initialize the secret service.", e);
        }
    }

    /**
     * Try to get a D-Bus connection.
     * Sets up a shutdown hook to close the connection at the end of the static lifetime.
     *
     * @return a DBusConnection or null
     */
    private static final DBusConnection getConnection() {
        try {
            return DBusConnection.getConnection(DBusConnection.DBusBusType.SESSION);
        } catch (DBusException e) {
            if (e == null) {
                log.warn("Could not communicate properly with the D-Bus.");
            } else {
                log.warn("Could not communicate properly with the D-Bus: " + e.getMessage() + " (" + e.getClass().getSimpleName() + ")");
            }
        } finally {
            disconnect(false);
        }
        return null;
    }

    /**
     * Checks if `org.freedesktop.secrets` is provided as D-Bus service.
     *
     * @return true if the secret service is available, otherwise false and will log an error message.
     */
    public static boolean isAvailable() {
        if (connection != null && connection.isConnected()) {
            try {
                DBus bus = connection.getRemoteObject(
                        Static.DBus.Service.DBUS,
                        Static.DBus.ObjectPaths.DBUS,
                        DBus.class);
                if (!Arrays.asList(bus.ListActivatableNames()).contains(Static.DBus.Service.DBUS)) {
                    return false;
                }

                // The following calls intent to open a session without actually generating a session.
                // Necessary in order to check if the secret service supports the expected transport encryption
                // algorithm (DH_IETF1024_SHA256_AES128_CBC_PKCS7) or raises an error, like
                // "org.freedesktop.DBus.Error.ServiceUnknown <: org.freedesktop.dbus.exceptions.DBusException"
                TransportEncryption transport = new TransportEncryption(connection);
                transport.initialize();
                boolean isSessionSupported = transport.openSession();
                transport.close();

                return isSessionSupported;
            } catch (DBusException | ExceptionInInitializerError e) {
                log.warn("The secret service is not available. You may want to install the `gnome-keyring` package. Is the `gnome-keyring-daemon` running?", e);
                return false;
            } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
                log.error("The secret service could not be initialized as the service does not provide the expected transport encryption algorithm.", e);
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * Acquire a lock and close and close the D-Bus connection.
     * Waits until the connection is closed.
     */
    static private void disconnectSynchronized() {
        disconnectLock.lock();
        try {
            if (connection != null && connection.isConnected()) {
                connection.close();
                while (connection.isConnected()) {
                    Thread.sleep(DEFAULT_DELAY_MILLIS);
                }
                connection = null;
                log.debug("Disconnected properly from the D-Bus.");
            }
        } catch (IOException | RejectedExecutionException | InterruptedException e) {
            log.error("Could not disconnect properly from the D-Bus.", e);
        } finally {
            disconnectLock.unlock();
        }
    }

    /**
     * Close the global D-Bus connection.
     *
     * @param now Either close the connection immediately or set up a shutdown hook.
     */
    static private void disconnect(boolean now) {
        if (connection != null && connection.isConnected()) {
            if (now && !testing) {
                disconnectSynchronized();
            } else {
                Thread daemonThread = new Thread(() -> disconnectSynchronized());
                daemonThread.setName("secret-service:disconnect-shutdown");
                daemonThread.setDaemon(true);
                Runtime.getRuntime().addShutdownHook(daemonThread);
            }
        }
    }

    /**
     * This is an ugly helper method in order to easily test some private static scope.
     *
     * @param testing if true then the D-Bus connection will be closed with a shutdown hook otherwise auto-close will close the connection immediately.
     */
    public static void setTesting(boolean testing) {
        SimpleCollection.testing = testing;
    }

    private void init() throws IOException {
        if (!isAvailable()) throw new IOException("The secret service is not available.");
        try {
            transport = new TransportEncryption(connection);
            transport.initialize();
            transport.openSession();
            transport.generateSessionKey();
            service = transport.getService();
            session = service.getSession();
            prompt = new Prompt(service);
            withoutPrompt = new InternalUnsupportedGuiltRiddenInterface(service);
        } catch (NoSuchAlgorithmException |
                InvalidAlgorithmParameterException |
                InvalidKeySpecException |
                InvalidKeyException |
                DBusException e) {
            throw new IOException("Cloud not initiate transport encryption.", e);
        }
    }

    private Map<ObjectPath, String> getLabels() {
        List<ObjectPath> collections = service.getCollections();

        Map<ObjectPath, String> labels = new HashMap();
        for (ObjectPath path : collections) {
            Collection c = new Collection(path, service, null);
            labels.put(path, c.getLabel());
        }

        return labels;
    }

    private boolean exists(String label) {
        Map<ObjectPath, String> labels = getLabels();
        return labels.containsValue(label);
    }

    private ObjectPath getCollectionPath(String label) {
        Map<ObjectPath, String> labels = getLabels();

        ObjectPath path = null;
        for (Map.Entry<ObjectPath, String> entry : labels.entrySet()) {
            ObjectPath p = entry.getKey();
            String l = entry.getValue();
            if (label.equals(l)) {
                path = p;
                break;
            }
        }
        return path;
    }

    private boolean isDefault() {
        if (connection != null && connection.isConnected()) {
            List<String> defaults = Arrays.asList(null, "login", "session", "default");
            return defaults.contains(collection.getId());
        } else {
            return false;
        }
    }

    private void performPrompt(ObjectPath path) {
        if (!("/".equals(path.getPath()))) {
            prompt.await(path, timeout);
        }
    }

    private Item getItem(String path) {
        if (path != null) {
            return new Item(Static.Convert.toObjectPath(path), service);
        } else {
            return null;
        }
    }

    private List<ObjectPath> lockable() {
        return Arrays.asList(collection.getPath());
    }

    @Override
    public void lock() {
        if (collection != null && !collection.isLocked()) {
            service.lock(lockable());
            try {
                Thread.currentThread().sleep(DEFAULT_DELAY_MILLIS);
            } catch (InterruptedException e) {
                log.error("Unexpected interrupt while waiting for a collection to lock.", e);
            }
        }
    }

    private void unlock() {
        if (collection != null && collection.isLocked()) {
            if (encrypted == null || isDefault()) {
                Pair<List<ObjectPath>, ObjectPath> response = service.unlock(lockable());
                performPrompt(response.b);
                if (!collection.isLocked()) {
                    isUnlockedOnceWithUserPermission = true;
                }
            } else {
                withoutPrompt.unlockWithMasterPassword(collection.getPath(), encrypted);
            }
        }
    }

    /**
     * Locks and unlocks the default collection explicitly.
     * <p>
     * The default collection gets only locked on the first call of a session.
     * <p>
     * Once the default collection is unlocked the user will not be prompted again
     * as long as the default collection stays unlocked.
     * <p>
     * This method is used to enforce user interaction for:
     * <p>
     * {@link SimpleCollection#getSecrets()}
     * <p>
     * {@link SimpleCollection#deleteItem(String)}
     * <p>
     * {@link SimpleCollection#deleteItems(List)}
     *
     * @throws AccessControlException if the user does not provide the correct credentials.
     */
    @Override
    public void unlockWithUserPermission() throws AccessControlException {
        if (!isUnlockedOnceWithUserPermission && isDefault()) lock();
        unlock();
        if (collection.isLocked()) {
            throw new AccessControlException("The collection was not unlocked with user permission.");
        }
    }

    /**
     * Clears the private key of the transport encryption and the passphrase of the collection.
     */
    @Override
    public void clear() {
        if (transport != null) {
            transport.clear();
        }
        if (encrypted != null) {
            encrypted.clear();
        }
    }

    @Override
    public void close() {
        clear();
        log.debug("Cleared secrets properly.");
        if (session != null) {
            session.close();
            log.debug("Closed session properly.");
        }
        disconnect(true);
    }

    /**
     * Delete this collection.
     */
    @Override
    public void delete() throws AccessControlException {
        if (!isDefault()) {
            ObjectPath promptPath = collection.delete();
            performPrompt(promptPath);
        } else {
            throw new AccessControlException("Default collections may not be deleted with the simple API.");
        }
    }

    /**
     * Creates an item with the provided properties in this collection.
     *
     * @param label      The displayable label of the new item
     * @param password   The password of the new item
     * @param attributes The attributes of the new item
     * @return DBus object path or null
     * @throws IllegalArgumentException The label and password are non nullable.
     */
    @Override
    public String createItem(String label, CharSequence password, Map<String, String> attributes) throws IllegalArgumentException {

        if (password == null) {
            throw new IllegalArgumentException("The password may not be null.");
        }
        if (label == null) {
            throw new IllegalArgumentException("The label of the password may not be null.");
        }

        if (collection == null || transport == null) return null;

        unlock();

        DBusPath item = null;
        final Map<String, Variant> properties = Item.createProperties(label, attributes);
        try (final Secret secret = transport.encrypt(password)) {
            Pair<ObjectPath, ObjectPath> response = collection.createItem(properties, secret, false);
            if (response == null) return null;
            item = response.a;
            if ("/".equals(item.getPath())) {
                Completed completed = prompt.await(response.b);
                if (!completed.dismissed) {
                    Collection.ItemCreated ic = collection.getSignalHandler().getLastHandledSignal(Collection.ItemCreated.class);
                    item = ic.item;
                }
            }
        } catch (NoSuchAlgorithmException |
                NoSuchPaddingException |
                InvalidAlgorithmParameterException |
                InvalidKeyException |
                BadPaddingException |
                IllegalBlockSizeException e) {
            log.error("Cloud not encrypt the secret.", e);
        }

        if (null != item) {
            return item.getPath();
        } else {
            return null;
        }
    }

    /**
     * Creates an item with the provided properties in this collection.
     *
     * @param label    The displayable label of the new item
     * @param password The password of the new item
     * @return DBus object path
     * @throws IllegalArgumentException The label and password are non nullable.
     */
    @Override
    public String createItem(String label, CharSequence password) throws IllegalArgumentException {
        return createItem(label, password, null);
    }

    /**
     * Updates an item with the provided properties.
     *
     * @param objectPath The DBus object path of the item
     * @param label      The displayable label of the new item
     * @param password   The password of the new item
     * @param attributes The attributes of the new item
     * @throws IllegalArgumentException The object path, label and password are non nullable.
     */
    @Override
    public void updateItem(String objectPath, String label, CharSequence password, Map<String, String> attributes) throws IllegalArgumentException {

        if (objectPath == null) {
            throw new IllegalArgumentException("The object path of the item may not be null.");
        }

        unlock();

        Item item = getItem(objectPath);

        if (label != null) {
            item.setLabel(label);
        }

        if (attributes != null) {
            item.setAttributes(attributes);
        }

        if (password != null) try (Secret secret = transport.encrypt(password)) {
            item.setSecret(secret);
        } catch (NoSuchAlgorithmException |
                NoSuchPaddingException |
                InvalidAlgorithmParameterException |
                InvalidKeyException |
                BadPaddingException |
                IllegalBlockSizeException e) {
            log.error("Cloud not encrypt the secret.", e);
        }
    }

    /**
     * Get the displayable label of an item.
     *
     * @param objectPath The DBus object path of the item
     * @return label or null
     */
    @Override
    public String getLabel(String objectPath) {
        if (objectPath == null) return null;
        unlock();
        return getItem(objectPath).getLabel();
    }

    /**
     * Get the user specified attributes of an item.
     * <p>
     * The attributes can contain an additional <code>xdg:schema</code> key-value pair.
     *
     * @param objectPath The DBus object path of the item
     * @return item attributes or null
     */
    @Override
    public Map<String, String> getAttributes(String objectPath) {
        if (objectPath == null) return null;
        unlock();
        return getItem(objectPath).getAttributes();
    }

    /**
     * Get the object paths of items with given attributes.
     *
     * @param attributes The attributes of the secret
     * @return object paths or null
     */
    @Override
    public List<String> getItems(Map<String, String> attributes) {
        if (attributes == null) return null;
        unlock();

        List<ObjectPath> objects = collection.searchItems(attributes);

        if (objects != null && !objects.isEmpty()) {
            return Static.Convert.toStrings(objects);
        } else {
            return null;
        }
    }

    /**
     * Get the secret of the item.
     *
     * @param objectPath The DBus object path of the item
     * @return plain chars or null
     */
    @Override
    public char[] getSecret(String objectPath) {
        if (objectPath == null) return null;
        unlock();

        final Item item = getItem(objectPath);

        char[] decrypted = null;
        try (final Secret secret = item.getSecret(session.getPath())) {
            decrypted = transport.decrypt(secret);
        } catch (NoSuchPaddingException |
                NoSuchAlgorithmException |
                InvalidAlgorithmParameterException |
                InvalidKeyException |
                BadPaddingException |
                IllegalBlockSizeException e) {
            log.error("Could not decrypt the secret.", e);
        }
        return decrypted;
    }

    /**
     * Get the secrets from this collection.
     * <p>
     * Retrieving all passwords form the default collection requires user permission.
     * <p>
     * see: {@link SimpleCollection#unlockWithUserPermission()}
     *
     * @return Mapping of DBus object paths and plain chars or null
     */
    @Override
    public Map<String, char[]> getSecrets() throws AccessControlException {
        unlockWithUserPermission();

        List<ObjectPath> items = collection.getItems();
        if (items == null) return null;

        Map<String, char[]> passwords = new HashMap();
        for (ObjectPath item : items) {
            String path = item.getPath();
            passwords.put(path, getSecret(path));
        }

        return passwords;
    }

    /**
     * Delete an item from this collection.
     * <p>
     * Deleting a password form the default collection requires user permission.
     * <p>
     * see: {@link SimpleCollection#unlockWithUserPermission()}
     *
     * @param objectPath The DBus object path of the item
     */
    @Override
    public void deleteItem(String objectPath) throws AccessControlException {
        if (objectPath == null) throw new AccessControlException("Cannot delete an unknown item.");

        unlockWithUserPermission();

        Item item = getItem(objectPath);
        ObjectPath promptPath = item.delete();
        performPrompt(promptPath);
    }

    /**
     * Delete specified items from this collection.
     * <p>
     * Deleting passwords form the default collection requires user permission.
     * <p>
     * see: {@link SimpleCollection#unlockWithUserPermission()}
     *
     * @param objectPaths The DBus object paths of the items
     */
    @Override
    public void deleteItems(List<String> objectPaths) throws AccessControlException {
        unlockWithUserPermission();
        for (String item : objectPaths) {
            deleteItem(item);
        }
    }

    @Override
    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public boolean isLocked() {
        if (connection != null && connection.isConnected()) {
            return collection.isLocked();
        } else {
            return true;
        }
    }

}
