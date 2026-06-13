package com.telecom.gateway.diameter;

/**
 * Diameter protocol constants per RFC 6733 and RFC 4006.
 *
 * <p>Centralises all command codes, AVP codes, application IDs, and result codes
 * to eliminate magic numbers throughout the codebase.</p>
 */
public final class DiameterConstants {

    private DiameterConstants() {
        // utility class — do not instantiate
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Protocol version
    // ──────────────────────────────────────────────────────────────────────────
    public static final int DIAMETER_VERSION = 1;

    /** Fixed header size (bytes): version(1) + length(3) + flags(1) + cmd(3) + appId(4) + hbh(4) + e2e(4) */
    public static final int HEADER_LENGTH = 20;

    // ──────────────────────────────────────────────────────────────────────────
    // Command Codes (RFC 6733)
    // ──────────────────────────────────────────────────────────────────────────
    public static final int CMD_CAPABILITIES_EXCHANGE = 257; // CER / CEA
    public static final int CMD_DEVICE_WATCHDOG       = 280; // DWR / DWA
    public static final int CMD_DISCONNECT_PEER       = 282; // DPR / DPA
    public static final int CMD_CREDIT_CONTROL        = 272; // CCR / CCA (RFC 4006)

    // ──────────────────────────────────────────────────────────────────────────
    // Application IDs
    // ──────────────────────────────────────────────────────────────────────────
    public static final int APP_ID_BASE_ACCOUNTING = 3;
    public static final int APP_ID_CREDIT_CONTROL  = 4;  // Diameter Ro/Gy interface
    public static final int APP_ID_COMMON          = 0;

    // ──────────────────────────────────────────────────────────────────────────
    // Message Flags (bit positions in the flags byte)
    // ──────────────────────────────────────────────────────────────────────────
    public static final int FLAG_REQUEST    = 0x80; // R-bit
    public static final int FLAG_PROXIABLE  = 0x40; // P-bit
    public static final int FLAG_ERROR      = 0x20; // E-bit
    public static final int FLAG_RETRANSMIT = 0x10; // T-bit

    // ──────────────────────────────────────────────────────────────────────────
    // AVP Codes — Base protocol (RFC 6733)
    // ──────────────────────────────────────────────────────────────────────────
    public static final int AVP_USER_NAME                 = 1;
    public static final int AVP_RESULT_CODE               = 268;
    public static final int AVP_ORIGIN_HOST               = 264;
    public static final int AVP_ORIGIN_REALM              = 296;
    public static final int AVP_DEST_HOST                 = 293;
    public static final int AVP_DEST_REALM                = 283;
    public static final int AVP_AUTH_APPLICATION_ID       = 258;
    public static final int AVP_ACCT_APPLICATION_ID       = 259;
    public static final int AVP_VENDOR_SPECIFIC_APP_ID    = 260;
    public static final int AVP_SUPPORTED_VENDOR_ID       = 265;
    public static final int AVP_VENDOR_ID                 = 266;
    public static final int AVP_FIRMWARE_REVISION         = 267;
    public static final int AVP_HOST_IP_ADDRESS           = 257;
    public static final int AVP_ORIGIN_STATE_ID           = 278;
    public static final int AVP_SESSION_ID                = 263;
    public static final int AVP_ERROR_MESSAGE             = 281;
    public static final int AVP_FAILED_AVP                = 279;
    public static final int AVP_DISCONNECT_CAUSE          = 273;

    // ──────────────────────────────────────────────────────────────────────────
    // AVP Codes — Credit Control (RFC 4006)
    // ──────────────────────────────────────────────────────────────────────────
    public static final int AVP_CC_REQUEST_TYPE           = 416;
    public static final int AVP_CC_REQUEST_NUMBER         = 415;
    public static final int AVP_SUBSCRIPTION_ID           = 443;
    public static final int AVP_SUBSCRIPTION_ID_TYPE      = 450;
    public static final int AVP_SUBSCRIPTION_ID_DATA      = 444;
    public static final int AVP_REQUESTED_SERVICE_UNIT    = 437;
    public static final int AVP_GRANTED_SERVICE_UNIT      = 431;
    public static final int AVP_CC_TOTAL_OCTETS           = 421;
    public static final int AVP_CC_INPUT_OCTETS           = 412;
    public static final int AVP_CC_OUTPUT_OCTETS          = 414;
    public static final int AVP_CC_TIME                   = 420;
    public static final int AVP_SERVICE_IDENTIFIER        = 439;
    public static final int AVP_RATING_GROUP              = 432;
    public static final int AVP_VALIDITY_TIME             = 448;
    public static final int AVP_MULTIPLE_SERVICES_IND     = 455;
    public static final int AVP_MULTIPLE_SERVICES_CC      = 456;

    // ──────────────────────────────────────────────────────────────────────────
    // AVP Flags
    // ──────────────────────────────────────────────────────────────────────────
    public static final int AVP_FLAG_VENDOR_SPECIFIC = 0x80; // V-bit
    public static final int AVP_FLAG_MANDATORY       = 0x40; // M-bit
    public static final int AVP_FLAG_PROTECTED       = 0x20; // P-bit

    // ──────────────────────────────────────────────────────────────────────────
    // Result Codes (RFC 6733 §7.1)
    // ──────────────────────────────────────────────────────────────────────────
    public static final int RESULT_SUCCESS                   = 2001;
    public static final int RESULT_LIMITED_SUCCESS           = 2002;
    public static final int RESULT_COMMAND_UNSUPPORTED       = 3001;
    public static final int RESULT_UNABLE_TO_DELIVER         = 3002;
    public static final int RESULT_REALM_NOT_SERVED          = 3003;
    public static final int RESULT_TOO_BUSY                  = 3004;
    public static final int RESULT_APPLICATION_UNSUPPORTED   = 3007;
    public static final int RESULT_INVALID_HDR_BITS          = 3008;
    public static final int RESULT_INVALID_AVP_BITS          = 3009;
    public static final int RESULT_AUTHENTICATION_REJECTED   = 4001;
    public static final int RESULT_OUT_OF_SPACE              = 4002;
    public static final int RESULT_CREDIT_LIMIT_REACHED      = 4012;
    public static final int RESULT_AVP_UNSUPPORTED           = 5001;
    public static final int RESULT_UNKNOWN_SESSION_ID        = 5002;
    public static final int RESULT_AVP_NOT_ALLOWED           = 5008;
    public static final int RESULT_AVP_OCCURS_TOO_MANY_TIMES = 5009;
    public static final int RESULT_DIAMETER_NOT_SUPPORTED    = 5010;

    // ──────────────────────────────────────────────────────────────────────────
    // CC-Request-Type enumeration values (RFC 4006)
    // ──────────────────────────────────────────────────────────────────────────
    public static final int CC_REQUEST_TYPE_INITIAL   = 1;
    public static final int CC_REQUEST_TYPE_UPDATE    = 2;
    public static final int CC_REQUEST_TYPE_TERMINATE = 3;
    public static final int CC_REQUEST_TYPE_EVENT     = 4;

    // ──────────────────────────────────────────────────────────────────────────
    // Subscription-Id-Type enumeration values (RFC 4006)
    // ──────────────────────────────────────────────────────────────────────────
    public static final int SUBSCRIPTION_ID_TYPE_E164  = 0; // MSISDN
    public static final int SUBSCRIPTION_ID_TYPE_IMSI  = 1;
    public static final int SUBSCRIPTION_ID_TYPE_SIP   = 2;
    public static final int SUBSCRIPTION_ID_TYPE_NAI   = 3;
    public static final int SUBSCRIPTION_ID_TYPE_PRIVATE = 4;
}
