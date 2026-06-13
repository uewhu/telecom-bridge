package com.telecom.gateway.diameter;

/**
 * Checked exception for Diameter protocol errors.
 */
public class DiameterException extends RuntimeException {

    private final int resultCode;

    public DiameterException(String message) {
        super(message);
        this.resultCode = -1;
    }

    public DiameterException(String message, int resultCode) {
        super(message);
        this.resultCode = resultCode;
    }

    public DiameterException(String message, Throwable cause) {
        super(message, cause);
        this.resultCode = -1;
    }

    public int getResultCode() {
        return resultCode;
    }
}
