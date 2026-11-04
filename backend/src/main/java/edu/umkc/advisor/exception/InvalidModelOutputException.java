package edu.umkc.advisor.exception;

/** The model returned something that is not valid, schema-conforming JSON. */
public class InvalidModelOutputException extends RuntimeException {
    public InvalidModelOutputException(String message) { super(message); }
    public InvalidModelOutputException(String message, Throwable cause) { super(message, cause); }
}
