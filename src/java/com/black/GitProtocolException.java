package com.black;

/**
 * Raised by the {@code com.black} git services when a client speaking the
 * git smart-http protocol sends a malformed / invalid request. Transport
 * layers map this to a client error (HTTP 400), as opposed to the generic
 * exceptions used for server-side failures.
 */
public class GitProtocolException extends Exception {
    public GitProtocolException(String message) {
        super(message);
    }

    public GitProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}