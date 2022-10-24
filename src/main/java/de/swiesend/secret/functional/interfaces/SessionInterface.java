package de.swiesend.secret.functional.interfaces;

import de.swiesend.secret.functional.interfaces.CollectionInterface;

import java.util.Optional;

public interface SessionInterface extends AutoCloseable {

    boolean clear();

    Optional<CollectionInterface> collection(String label, CharSequence password);

    Optional<CollectionInterface> defaultCollection();

}
