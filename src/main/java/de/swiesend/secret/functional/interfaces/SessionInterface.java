package de.swiesend.secret.functional.interfaces;

import org.freedesktop.secret.TransportEncryption;

import java.util.Optional;

public interface SessionInterface extends AutoCloseable {

    boolean clear();

    Optional<CollectionInterface> collection(String label, CharSequence password);

    Optional<CollectionInterface> defaultCollection();

    TransportEncryption.EncryptedSession getEncryptedSession();

    ServiceInterface getService();

    org.freedesktop.secret.Session getSession();

}
