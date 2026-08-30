package com.redcoffee.puttputt.storage;

/** Wraps backend failures so callers never have to import JDBC types. */
public class StorageException extends Exception {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
