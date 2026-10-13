package co.rsk.peg.pegininstructions;

import static org.junit.jupiter.api.Assertions.*;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.script.Script;
import co.rsk.core.RskAddress;
import co.rsk.peg.PegTestUtils;
import java.util.Optional;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PeginInstructionsVersion3Test {
    private final NetworkParameters params = NetworkParameters.fromID(NetworkParameters.ID_REGTEST);
    private final RskAddress rskDestination = new RskAddress(Hex.decode("0101010101010101010101010101010101010101"));
    private final Address p2pkhRefund = new BtcECKey().toAddress(params);
    private final Address p2shRefund = new Address(params, params.getP2SHHeader(), new byte[20]);

    private static byte[] payloadFor(Script opReturnScript) {
        return opReturnScript.getChunks().get(1).data;
    }

    private PeginInstructionsVersion3 parse(byte[] payload) throws PeginInstructionsParseException {
        PeginInstructionsVersion3 instructions = new PeginInstructionsVersion3(params);
        instructions.parse(payload);
        return instructions;
    }

    @Test
    void getProtocolVersion() {
        assertEquals(3, new PeginInstructionsVersion3(params).getProtocolVersion());
    }

    @Test
    void parse_flagsClear_noRefund_26Bytes() throws PeginInstructionsParseException {
        byte[] payload = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, 0x00, Optional.empty()));
        assertEquals(26, payload.length);

        PeginInstructionsVersion3 instructions = parse(payload);

        assertEquals(rskDestination, instructions.getRskDestinationAddress());
        assertEquals(0, instructions.getFlags());
        assertFalse(instructions.requiresCode());
        assertTrue(instructions.getBtcRefundAddress().isEmpty());
    }

    @Test
    void parse_flagsClear_withP2pkhRefund_47Bytes() throws PeginInstructionsParseException {
        byte[] payload = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, 0x00, Optional.of(p2pkhRefund)));
        assertEquals(47, payload.length);

        PeginInstructionsVersion3 instructions = parse(payload);

        assertFalse(instructions.requiresCode());
        assertEquals(Optional.of(p2pkhRefund), instructions.getBtcRefundAddress());
    }

    @Test
    void parse_requireCode_withP2shRefund() throws PeginInstructionsParseException {
        byte[] payload = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, PeginInstructionsVersion3.REQUIRE_CODE, Optional.of(p2shRefund)));

        PeginInstructionsVersion3 instructions = parse(payload);

        assertTrue(instructions.requiresCode());
        assertEquals(Optional.of(p2shRefund), instructions.getBtcRefundAddress());
        assertTrue(instructions.getBtcRefundAddress().get().isP2SHAddress());
    }

    @Test
    void parse_requireCode_withoutRefund_isParseFailure() {
        byte[] payload = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, PeginInstructionsVersion3.REQUIRE_CODE, Optional.empty()));
        assertEquals(26, payload.length);
        assertThrows(PeginInstructionsParseException.class, () -> parse(payload));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x02, 0x03, 0x80, 0xfe, 0xff})
    void parse_reservedFlagBits_areParseFailure(int flags) {
        byte[] withRefund = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, flags, Optional.of(p2pkhRefund)));
        assertThrows(PeginInstructionsParseException.class, () -> parse(withRefund));
        byte[] withoutRefund = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, flags, Optional.empty()));
        assertThrows(PeginInstructionsParseException.class, () -> parse(withoutRefund));
    }

    @ParameterizedTest
    @EnumSource(PeginRefundScriptType.class)
    void parse_requireCode_withAnyV2RefundType_buildsTheOutputScript(PeginRefundScriptType type) throws PeginInstructionsParseException {
        byte[] program = new byte[type.getProgramLength()];
        java.util.Arrays.fill(program, (byte) 0xab);
        byte[] payload = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, PeginInstructionsVersion3.REQUIRE_CODE, Optional.of(type), program));
        assertEquals(27 + type.getProgramLength(), payload.length);

        PeginInstructionsVersion3 instructions = parse(payload);

        assertTrue(instructions.requiresCode());
        assertEquals(Optional.of(type), instructions.getBtcRefundScriptType());
        assertEquals(Optional.of(type.buildOutputScript(program)), instructions.getBtcRefundScript());
        assertEquals(type.isLegacy(), instructions.getBtcRefundAddress().isPresent(), "only legacy types have an Address form");
    }

    @Test
    void parse_unknownRefundType_isParseFailure() {
        byte[] payload = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, PeginInstructionsVersion3.REQUIRE_CODE, Optional.of(p2pkhRefund)));
        for (int type : new int[]{0x00, 0x06, 0xff}) {
            payload[26] = (byte) type;
            assertThrows(PeginInstructionsParseException.class, () -> parse(payload), "type " + type);
        }
    }

    @Test
    void parse_programLengthMismatch_isParseFailure() {
        // a 20-byte program declared as taproot (32) and a 32-byte one declared as P2WPKH (20)
        byte[] short20 = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, 0x00, Optional.of(PeginRefundScriptType.P2WPKH), new byte[20]));
        short20[26] = (byte) PeginRefundScriptType.P2TR.getTypeByte();
        assertThrows(PeginInstructionsParseException.class, () -> parse(short20));
        byte[] long32 = payloadFor(PegTestUtils.createOpReturnScriptForRskV3(rskDestination, 0x00, Optional.of(PeginRefundScriptType.P2TR), new byte[32]));
        long32[26] = (byte) PeginRefundScriptType.P2WPKH.getTypeByte();
        assertThrows(PeginInstructionsParseException.class, () -> parse(long32));
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 25, 27, 46, 48, 58, 60, 80})
    void parse_invalidLengths_areParseFailure(int length) {
        assertThrows(PeginInstructionsParseException.class, () -> parse(new byte[length]));
    }
}
