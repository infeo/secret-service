package org.freedesktop.secret;

import org.freedesktop.dbus.ObjectPath;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.secret.errors.NoSuchObjectException;
import org.freedesktop.secret.interfaces.Prompt;
import org.freedesktop.secret.test.Context;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


public class PromptTest {

    private Logger log = LoggerFactory.getLogger(getClass());
    private Context context;

    @BeforeEach
    public void beforeEach(TestInfo info) throws DBusException {
        log.info(info.getDisplayName());
        context = new Context(log);
        context.ensureCollection();
    }

    @AfterEach
    public void afterEach() {
        context.after();
    }

    @Test
    @Disabled
    public void prompt() throws DBusException {
        ObjectPath defaultCollection = Static.Convert.toObjectPath(Static.ObjectPaths.DEFAULT_COLLECTION);
        ArrayList<ObjectPath> cs = new ArrayList();
        cs.add(defaultCollection);

        log.info("lock default collection");
        Pair<List<ObjectPath>, ObjectPath> locked = context.service.lock(cs);
        log.info(locked.toString());

        log.info("unlock default collection");
        Pair<List<ObjectPath>, ObjectPath> unlocked = context.service.unlock(cs);
        log.info(unlocked.toString());
        ObjectPath prompt = unlocked.b;

        Prompt.Completed completed = context.prompt.await(prompt);
        assertNotNull(completed);
    }

    @Test
    @Disabled
    public void dismissPrompt() throws InterruptedException, NoSuchObjectException, DBusException {
        ArrayList<ObjectPath> cs = new ArrayList();
        cs.add(context.collection.getPath());
        context.service.lock(cs);

        Pair<List<ObjectPath>, ObjectPath> response = context.service.unlock(cs);
        ObjectPath prompt = response.b;

        context.prompt.prompt(prompt);
        Thread.sleep(500L);
        context.prompt.dismiss();
        Thread.sleep(500L); // await signal

        Prompt.Completed completed = context.service
                .getSignalHandler()
                .getLastHandledSignal(Prompt.Completed.class, prompt.getPath())
                .get();
        assertTrue(completed.dismissed);
    }

    @Test
    public void isRemote() {
        assertFalse(context.prompt.isRemote());
    }

    @Test
    public void getObjectPath() {
        assertEquals("/", context.prompt.getObjectPath());
    }
}
