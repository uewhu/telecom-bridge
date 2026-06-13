package com.telecom.gateway.diameter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Represents a Diameter message as defined in RFC 6733 §3.
 *
 * <p>Wire header format (20 bytes):</p>
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Version    |                 Message Length                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | command flags |                  Command-Code                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Application-ID                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      Hop-by-Hop Identifier                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      End-to-End Identifier                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 */
public class DiameterMessage {

    private final int            version;
    private final int            flags;
    private final int            commandCode;
    private final long           applicationId;
    private final long           hopByHopId;
    private final long           endToEndId;
    private final List<DiameterAVP> avps;

    private DiameterMessage(Builder builder) {
        this.version       = builder.version;
        this.flags         = builder.flags;
        this.commandCode   = builder.commandCode;
        this.applicationId = builder.applicationId;
        this.hopByHopId    = builder.hopByHopId;
        this.endToEndId    = builder.endToEndId;
        this.avps          = Collections.unmodifiableList(new ArrayList<>(builder.avps));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Derived helpers
    // ──────────────────────────────────────────────────────────────────────────

    public boolean isRequest() {
        return (flags & DiameterConstants.FLAG_REQUEST) != 0;
    }

    public boolean isAnswer() {
        return !isRequest();
    }

    /**
     * Finds the first AVP with the given code (ignoring vendor-specific scope).
     */
    public Optional<DiameterAVP> findAvp(int code) {
        return avps.stream().filter(a -> a.getCode() == code).findFirst();
    }

    /**
     * Retrieves the Result-Code AVP value, or -1 if absent.
     */
    public long getResultCode() {
        return findAvp(DiameterConstants.AVP_RESULT_CODE)
                .map(DiameterAVP::getValueAsUnsigned32)
                .orElse(-1L);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Getters
    // ──────────────────────────────────────────────────────────────────────────

    public int               getVersion()       { return version;       }
    public int               getFlags()         { return flags;         }
    public int               getCommandCode()   { return commandCode;   }
    public long              getApplicationId() { return applicationId; }
    public long              getHopByHopId()    { return hopByHopId;    }
    public long              getEndToEndId()    { return endToEndId;    }
    public List<DiameterAVP> getAvps()          { return avps;          }

    @Override
    public String toString() {
        return String.format(
            "DiameterMessage{cmd=%d, flags=0x%02X, appId=%d, hbh=%d, e2e=%d, avps=%d}",
            commandCode, flags, applicationId, hopByHopId, endToEndId, avps.size());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Builder
    // ──────────────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int  version       = DiameterConstants.DIAMETER_VERSION;
        private int  flags         = 0;
        private int  commandCode   = 0;
        private long applicationId = 0;
        private long hopByHopId    = 0;
        private long endToEndId    = 0;
        private final List<DiameterAVP> avps = new ArrayList<>();

        private Builder() {}

        public Builder version(int v)            { this.version       = v; return this; }
        public Builder flags(int f)              { this.flags         = f; return this; }
        public Builder requestFlag()             { this.flags        |= DiameterConstants.FLAG_REQUEST;   return this; }
        public Builder proxiableFlag()           { this.flags        |= DiameterConstants.FLAG_PROXIABLE; return this; }
        public Builder commandCode(int c)        { this.commandCode   = c; return this; }
        public Builder applicationId(long a)     { this.applicationId = a; return this; }
        public Builder hopByHopId(long h)        { this.hopByHopId    = h; return this; }
        public Builder endToEndId(long e)        { this.endToEndId    = e; return this; }

        public Builder addAvp(DiameterAVP avp) {
            this.avps.add(avp);
            return this;
        }

        public Builder addAvps(List<DiameterAVP> list) {
            this.avps.addAll(list);
            return this;
        }

        public DiameterMessage build() {
            if (commandCode == 0) throw new IllegalStateException("commandCode must be set");
            return new DiameterMessage(this);
        }
    }
}
