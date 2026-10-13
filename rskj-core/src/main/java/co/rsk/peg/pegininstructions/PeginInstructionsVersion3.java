package co.rsk.peg.pegininstructions;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.script.Script;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Peg-in instructions version 3 (PoC): the version-2 layout plus a flags byte between the Rootstock
 * destination and the optional refund output.
 *
 * <pre>RSKT | 03 | destination (20) | flags (1) | [refund script type (1) | program (20 or 32)]</pre>
 *
 * The refund is encoded exactly as in version 2 ({@link PeginRefundScriptType}: P2PKH, P2SH, P2WPKH, P2WSH,
 * P2TR), so valid data lengths are 26, 47 and 59 bytes. With {@link #REQUIRE_CODE} set, the Bridge credits
 * the destination only if it holds code at registration and refunds to the refund output otherwise; the
 * refund output is therefore mandatory with the flag.
 */
public class PeginInstructionsVersion3 extends PeginInstructionsBase {
    private static final Logger logger = LoggerFactory.getLogger(PeginInstructionsVersion3.class);

    public static final int PROTOCOL_VERSION = 3;
    public static final int REQUIRE_CODE = 0x01;
    private static final int RESERVED_FLAGS_MASK = 0xFE;

    private static final int FLAGS_INDEX = 25;
    private static final int NO_REFUND_LENGTH = 26;
    private static final int REFUND_TYPE_INDEX = 26;
    private static final int REFUND_PROGRAM_INDEX = 27;
    private static final int LENGTH_WITH_20_BYTE_PROGRAM = REFUND_PROGRAM_INDEX + 20;
    private static final int LENGTH_WITH_32_BYTE_PROGRAM = REFUND_PROGRAM_INDEX + 32;

    private final NetworkParameters params;
    private int flags;
    private Optional<PeginRefundScriptType> btcRefundScriptType = Optional.empty();
    private Optional<Script> btcRefundScript = Optional.empty();

    public PeginInstructionsVersion3(NetworkParameters params) {
        super(PROTOCOL_VERSION);
        this.params = params;
    }

    @Override
    protected void validateDataLength(byte[] data) throws PeginInstructionsParseException {
        if (data.length != NO_REFUND_LENGTH
            && data.length != LENGTH_WITH_20_BYTE_PROGRAM
            && data.length != LENGTH_WITH_32_BYTE_PROGRAM) {
            String message = String.format(
                "[validateDataLength] Invalid data length. Expected %d, %d or %d bytes, received %d",
                NO_REFUND_LENGTH, LENGTH_WITH_20_BYTE_PROGRAM, LENGTH_WITH_32_BYTE_PROGRAM, data.length);
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
                String message = "[parseAdditionalData] REQUIRE_CODE set but no refund output provided";
                logger.debug(message);
                throw new PeginInstructionsParseException(message);
            }
            this.btcRefundScriptType = Optional.empty();
            this.btcRefundScript = Optional.empty();
            return;
        }

        int typeByte = data[REFUND_TYPE_INDEX] & 0xff;
        PeginRefundScriptType scriptType = PeginRefundScriptType.fromTypeByte(typeByte)
            .orElseThrow(() -> {
                String message = String.format("[parseAdditionalData] Invalid btc refund script type: %d", typeByte);
                logger.debug(message);
                return new PeginInstructionsParseException(message);
            });

        byte[] program = Arrays.copyOfRange(data, REFUND_PROGRAM_INDEX, data.length);
        if (program.length != scriptType.getProgramLength()) {
            String message = String.format(
                "[parseAdditionalData] Invalid program length for %s. Expected %d bytes, received %d",
                scriptType, scriptType.getProgramLength(), program.length);
            logger.debug(message);
            throw new PeginInstructionsParseException(message);
        }

        Script outputScript = scriptType.buildOutputScript(program);
        logger.debug("[parseAdditionalData] Obtained {} BTC refund script: {}", scriptType, outputScript);
        this.btcRefundScriptType = Optional.of(scriptType);
        this.btcRefundScript = Optional.of(outputScript);
    }

    public int getFlags() {
        return flags;
    }

    public boolean requiresCode() {
        return (flags & REQUIRE_CODE) != 0;
    }

    public Optional<PeginRefundScriptType> getBtcRefundScriptType() {
        return btcRefundScriptType;
    }

    public Optional<Script> getBtcRefundScript() {
        return btcRefundScript;
    }

    /** Only P2PKH and P2SH refunds have an {@link Address} form; witness programs are exposed as scripts only. */
    public Optional<Address> getBtcRefundAddress() {
        if (btcRefundScriptType.filter(PeginRefundScriptType::isLegacy).isEmpty()) {
            return Optional.empty();
        }
        return btcRefundScript.map(script -> script.getToAddress(params, true));
    }
}
