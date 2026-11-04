package edu.umkc.advisor.exception;

/** The LLM API could not be reached or returned an error. Message must never contain user text or secrets. */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message) { super(message); }
    public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
}
