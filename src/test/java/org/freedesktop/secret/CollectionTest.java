package org.freedesktop.secret;

import org.freedesktop.dbus.ObjectPath;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.types.UInt64;
import org.freedesktop.dbus.types.Variant;
import org.freedesktop.secret.test.Context;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.freedesktop.secret.test.Context.label;
import static org.junit.jupiter.api.Assertions.*;


public class CollectionTest {

    private Logger log = LoggerFactory.getLogger(getClass());
    private Context context;

    @BeforeEach
    public void beforeEach(TestInfo info) throws DBusException {
        log.info(info.getDisplayName());
        context = new Context(log);
        context.ensureItem();
    }

    @AfterEach
    public void afterEach() {
        context.after();
    }

    @Test
    @DisplayName("delete test collection")
    public void delete() throws DBusException {
        List<ObjectPath> expected = context.service.getCollections();
        ObjectPath promptPath = context.collection.delete();
        log.info(promptPath.toString());
        assertEquals("/", promptPath.getPath());
        // assertTrue(promptPath.getPath().startsWith("/org/freedesktop/secrets/prompt/p"));

        List<ObjectPath> actual = context.service.getCollections();
        assertEquals(expected.size() - 1, actual.size());
    }

    @Test
    public void searchItems() throws DBusException {
        // search by attribute
        Map<String, String> attributes = new HashMap();
        attributes.put("Attribute1", "Value1");

        List<ObjectPath> items = context.collection.searchItems(attributes);
        log.info(Arrays.toString(items.toArray()));
        assertEquals(1, items.size());
        assertTrue(items.get(0).getPath().startsWith("/org/freedesktop/secrets/collection/test/"));
    }

    @Test
    public void createItem() throws DBusException {

        // some empty cipher parameters
        byte[] parameters = "".getBytes();
        byte[] value = "super secret".getBytes();
        Secret secret = new Secret(context.session.getPath(), parameters, value);

        Map<String, String> attributes = new HashMap();
        attributes.put("Attribute1", "Value1");
        Map<String, Variant> properties = Item.createProperties("TestItem", attributes);

        Pair<ObjectPath, ObjectPath> response = context.collection.createItem(properties, secret, true);
        log.info(response.toString());
        assertTrue(response.a.getPath().startsWith("/org/freedesktop/secrets/collection/test/"));
        assertEquals("/", response.b.getPath());

        List<ObjectPath> items = context.collection.getItems();
        assertEquals(1, items.size());

        context.collection.createItem(properties, secret, false);
        items = context.collection.getItems();
        assertEquals(2, items.size());
    }

    @Test
    public void getItems() throws DBusException {
        List<ObjectPath> items = context.collection.getItems();
        log.info(Arrays.toString(items.toArray()));
        assertEquals(1, items.size());
        assertTrue(items.get(0).getPath().startsWith("/org/freedesktop/secrets/collection/test/"));
    }

    @Test
    public void getLabel() throws DBusException {
        Collection collection = new Collection("login", context.service);
        String response = collection.getLabel();
        log.info(response);
        List<String> labels = Arrays.asList(new String[]{
                "login",     // en
                "inicio",    // es
                "ouverture", // fr
                "Anmeldung", // de
                "ログイン",   // ja
                "登录"});    // zh
        assertTrue(labels.contains(response));
    }

    @Test
    public void setLabel() throws DBusException {
        String label = context.collection.getLabel();
        assertEquals("test", label);

        context.collection.setLabel("test-renamed");
        label = context.collection.getLabel();
        assertEquals("test-renamed", label);

        context.collection.setLabel("test");
        label = context.collection.getLabel();
        assertEquals("test", label);
    }

    @Test
    public void isLocked() throws DBusException {
        boolean locked = context.collection.isLocked();
        log.info(label("locked", String.valueOf(locked)));
        assertFalse(locked);
    }

    @Test
    @DisplayName("created at unixtime")
    public void created() throws DBusException {
        Collection collection;
        UInt64 response;

        collection = new Collection("test", context.service);
        response = collection.created();
        log.info("test: " + response);
        assertTrue(response.longValue() >= 0L);

        collection = new Collection("login", context.service);
        response = collection.created();
        log.info("login: " + response);
        assertTrue(response.longValue() >= 0L);

        collection = new Collection("session", context.service);
        response = collection.created();
        log.info("session: " + response);
        assertTrue(response.longValue() == 0L);
    }

    @Test
    @DisplayName("modified at unixtime")
    public void modified() throws DBusException {
        Collection collection;
        UInt64 response;

        collection = new Collection("test", context.service);
        response = collection.modified();
        log.info("test: " + response);
        assertTrue(response.longValue() >= 0L);
        collection = new Collection("login", context.service);
        response = collection.modified();
        log.info("login: " + response);
        assertTrue(response.longValue() >= 0L);

        collection = new Collection("session", context.service);
        response = collection.modified();
        log.info("session: " + response);
        assertTrue(response.longValue() == 0L);
    }

    @Test
    public void isRemote() {
        Collection collection = new Collection("test", context.service);
        assertFalse(collection.isRemote());
    }

    @Test
    public void getObjectPath() {
        String test = context.collection.getObjectPath();
        log.info(test);
        assertEquals("/org/freedesktop/secrets/collection/test", test);

        Collection login = new Collection("login", context.service);
        log.info(login.getObjectPath());
        assertEquals("/org/freedesktop/secrets/collection/login", login.getObjectPath());

        Collection session = new Collection("session", context.service);
        log.info(session.getObjectPath());
        assertEquals("/org/freedesktop/secrets/collection/session", session.getObjectPath());
    }

}
