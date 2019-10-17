package com.max480.everest.updatechecker;

import java.io.IOException;

/**
 * Much like {@link java.util.function.Supplier} but throwing an IOException (which a network operation may do).
 *
 * @param <T> The return type for the operation
 */
public interface NetworkingOperation<T> {
    T run() throws IOException;
}
