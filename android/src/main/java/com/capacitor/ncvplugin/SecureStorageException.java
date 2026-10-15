package com.capacitor.ncvplugin;

/**
 * Fout van de beveiligde opslag, met een code voor JavaScript (err.code).
 * De melding bevat nooit opgeslagen waarden.
 */
public class SecureStorageException extends Exception {

    //sleutel bestaat, maar de waarde is nu niet leesbaar: geen "geen inlog"
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String AUTH_CANCELLED = "AUTH_CANCELLED";
    public static final String KEY_MISSING = "KEY_MISSING";
    public static final String KEY_INVALIDATED = "KEY_INVALIDATED";
    public static final String DECRYPT_FAILED = "DECRYPT_FAILED";
    public static final String INVALID_RECORD = "INVALID_RECORD";
    public static final String OS_ERROR = "OS_ERROR";

    public final String code;

    public SecureStorageException(String code, String message) {
        this(code, message, null);
    }

    public SecureStorageException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
