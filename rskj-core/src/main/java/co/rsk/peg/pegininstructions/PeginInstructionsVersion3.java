package co.rsk.peg.pegininstructions;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.NetworkParameters;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Peg-in instructions version 3 (PoC): the version-1 layout plus a flags byte between the Rootstock
 * destination and the optional refund address.
 *
 * <pre>RSKT | 03 | destination (20) | flags (1) | [refund type (1) | hash160 (20)]</pre>
 *
 * With {@link #REQUIRE_CODE} set, the Bridge credits the destination only if it holds code at registration
 * and refunds to the refund address otherwise; the refund address is therefore mandatory with the flag.
 * The draft RSKIP encodes the refund as in version 2 (five script types); this PoC supports the two
 * version-1 types (P2PKH, P2SH) only, so valid data lengths are 26 and 47 bytes.
 */
public class PeginInstructionsVersion3 extends PeginInstructionsBase {
    private static final Logger logger = LoggerFactory.getLogger(PeginInstructionsVersion3.class);

    public static final int PROTOCOL_VERSION = 3;
    public static final int REQUIRE_CODE = 0x01;
    private static final int RESERVED_FLAGS_MASK = 0xFE;

    private static final int FLAGS_INDEX = 25;
    private static final int NO_REFUND_LENGTH = 26;
    private static final int REFUND_TYPE_INDEX = 26;
    private static final int REFUND_HASH_INDEX = 27;
    private static final int LENGTH_WITH_REFUND = REFUND_HASH_INDEX + 20;

    private static final int P2PKH_ADDRESS_TYPE = 1;
    private static final int P2SH_ADDRESS_TYPE = 2;

    private final NetworkParameters params;
    private int flags;
    private Optional<Address> btcRefundAddress = Optional.empty();

    public PeginInstructionsVersion3(NetworkParameters params) {
        super(PROTOCOL_VERSION);
        this.params = params;
    }

    @Override
    protected void validateDataLength(byte[] data) throws PeginInstructionsParseException {
        if (data.length != NO_REFUND_LENGTH && data.length != LENGTH_WITH_REFUND) {
            String message = String.format("[validateDataLength] Invalid data length. Expected %d or %d bytes, received %d",
                NO_REFUND_LENGTH, LENGTH_WITH_REFUND, data.length);
            logger.debug(message);
            throw new PeginInstructionsParseException(message);
        }
    }

    @Override
    protected void parseAdditionalData(byte[] data) throws PeginInstructionsParseException {
        int parsedFlags = data[FLAGS_INDEX] & 0xff;
        if ((parsedFlags & RESERVED_FLAGS_MASK) != 0) {
            String message = String.format("[parseAdditionalData] Reserved flag bits set: 0x%02x", parsedFlags);
            logger.debug(message);
            throw new PeginInstructionsParseException(message);
        }
        this.flags = parsedFlags;

        if (data.length == NO_REFUND_LENGTH) {
            if (requiresCode()) {
                // A peg-in must not commit to a refund it cannot receive
                String message = "[parseAdditionalData] REQUIRE_CODE set but no refund address provided";
                logger.debug(message);
                throw new PeginInstructionsParseException(message);
            }
            this.btcRefundAddress = Optional.empty();
            return;
        }

        int refundAddressType = data[REFUND_TYPE_INDEX] & 0xff;
        byte[] hash = Arrays.copyOfRange(data, REFUND_HASH_INDEX, data.length);
        Address parsedBtcRefundAddress = switch (refundAddressType) {
            case P2PKH_ADDRESS_TYPE -> new Address(this.params, hash);
            case P2SH_ADDRESS_TYPE -> new Address(this.params, this.params.getP2SHHeader(), hash);
            default -> {
                String message = String.format("[parseAdditionalData] Invalid btc address type: %d", refundAddressType);
                logger.debug(message);
                throw new PeginInstructionsParseException(message);
            }
        };
        logger.debug("[parseAdditionalData] Obtained BTC refund address: {}", parsedBtcRefundAddress);
        this.btcRefundAddress = Optional.of(parsedBtcRefundAddress);
    }

    public int getFlags() {
        return flags;
    }

    public boolean requiresCode() {
        return (flags & REQUIRE_CODE) != 0;
    }

    public Optional<Address> getBtcRefundAddress() {
        return btcRefundAddress;
    }
}
