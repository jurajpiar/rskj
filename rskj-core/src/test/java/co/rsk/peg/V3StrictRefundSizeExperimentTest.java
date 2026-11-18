package co.rsk.peg;

import static org.junit.jupiter.api.Assertions.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.peg.ReleaseTransactionBuilder.BuildResult;
import co.rsk.peg.ReleaseTransactionBuilder.Response;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.P2shErpFederationBuilder;
import co.rsk.peg.federation.P2shP2wshErpFederationBuilder;
import co.rsk.peg.pegininstructions.PeginRefundScriptType;
import java.util.*;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest;
import org.junit.jupiter.api.Test;

/**
 * Experiment B3 (and the script dump for B5): when can the real ReleaseTransactionBuilder not build a refund?
 *
 * The builder and the wallet are the real ones, constructed as BridgeSupport constructs them for a rejection
 * release (BridgeUtils.getFederationsSpendWallet over the deposit's own UTXOs, buildEmptyWalletTo(refundScript)).
 * The federation is a fixture: 9 members from test seeds with the mainnet ERP keys and activation delay, in the
 * P2SH-P2WSH ERP format (the shape of the current mainnet federation, not its keys) and, for comparison, the
 * legacy P2SH ERP format. Output lines start with "V3STRICT-B3" / "V3STRICT-B5".
 */
class V3StrictRefundSizeExperimentTest {
    private static final NetworkParameters MAINNET = BridgeMainNetConstants.getInstance().getBtcParams();
    private static final ActivationConfig.ForBlock ACTIVATIONS = ActivationConfigsForTest.all().forBlock(0);
    private static final Coin MINIMUM_PEGIN = BridgeMainNetConstants.getInstance().getMinimumPeginTxValue(ACTIVATIONS);
    private static final long[] FEES_PER_KB = {8_000, 240_000, 500_000, 5_000_000};
    private static final long MAX_FEE_PER_KB = BridgeMainNetConstants.getInstance().getFeePerKbConstants().getMaxFeePerKb().value;
    private static final String[] NINE_MEMBERS = {
        "member01", "member02", "member03", "member04", "member05", "member06", "member07", "member08", "member09"};

    private static Federation segwitFederation() {
        return P2shP2wshErpFederationBuilder.builder()
            .withMembersBtcPublicKeys(BitcoinTestUtils.getBtcEcKeysFromSeeds(NINE_MEMBERS, true))
            .build();
    }

    private static Federation legacyFederation() {
        return P2shErpFederationBuilder.builder().build(); // 9 members by default
    }

    private static Script refund(PeginRefundScriptType type) {
        byte[] program = new byte[type.getProgramLength()];
        Arrays.fill(program, (byte) 0x11);
        return type.buildOutputScript(program);
    }

    private static BuildResult build(Federation federation, int federationOutputs, Coin valueOfEach, long feePerKb, Script refundScript) {
        List<UTXO> utxos = new ArrayList<>();
        Sha256Hash depositHash = BitcoinTestUtils.createHash(7);
        for (int i = 0; i < federationOutputs; i++) {
            utxos.add(new UTXO(depositHash, i, valueOfEach, 0, false, federation.getP2SHScript()));
        }
        Wallet wallet = BridgeUtils.getFederationsSpendWallet(new Context(MAINNET), List.of(federation), utxos, false, null);
        ReleaseTransactionBuilder builder = new ReleaseTransactionBuilder(
            MAINNET, wallet, federation.getFormatVersion(), federation.getAddress(), Coin.valueOf(feePerKb), ACTIVATIONS);
        return builder.buildEmptyWalletTo(refundScript);
    }

    private static long fee(BuildResult result, int federationOutputs, Coin valueOfEach) {
        return valueOfEach.multiply(federationOutputs).subtract(result.btcTx().getOutput(0).getValue()).value;
    }

    /** Lowest feePerKb at which the build no longer succeeds (the fee is monotonic in feePerKb). */
    private static long lowestFailingFeePerKb(Federation federation, int n, Script refundScript) {
        long low = 1; // succeeds
        long high = 400_000_000_000L; // fails for every n used here
        assertEquals(Response.SUCCESS, build(federation, n, MINIMUM_PEGIN, low, refundScript).responseCode());
        assertNotEquals(Response.SUCCESS, build(federation, n, MINIMUM_PEGIN, high, refundScript).responseCode());
        while (high - low > 1) {
            long mid = (low + high) / 2;
            if (build(federation, n, MINIMUM_PEGIN, mid, refundScript).responseCode() == Response.SUCCESS) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high;
    }

    private void table(String federationName, Federation federation, PeginRefundScriptType refundType, int[] counts, Set<Response> seen) {
        Script refundScript = refund(refundType);
        System.out.printf("V3STRICT-B3 federation=%s refund=%s minimumPegin=%d maxFeePerKb=%d%n",
            federationName, refundType, MINIMUM_PEGIN.value, MAX_FEE_PER_KB);
        System.out.println("V3STRICT-B3 | n | deposit sat | size used for fee | serialized bytes as built | fee@8000 | fee@240000 | fee@500000 | fee@5000000 | lowest failing feePerKb | response there | refund left at last success |");
        for (int n : counts) {
            // the size the wallet multiplies by feePerKb: at 1,000,000 sat/kB the fee is 1000 sat per byte
            // (minimum-fee floor not in play at this rate)
            BuildResult probe = build(federation, n, Coin.COIN, 1_000_000, refundScript);
            assertEquals(Response.SUCCESS, probe.responseCode());
            long sizeUsed = fee(probe, n, Coin.COIN) / 1000;
            assertEquals(sizeUsed * 1000, fee(probe, n, Coin.COIN));

            StringBuilder row = new StringBuilder();
            row.append(String.format("V3STRICT-B3 | %d | %d | %d | %d |", n, MINIMUM_PEGIN.value * n, sizeUsed, probe.btcTx().bitcoinSerialize().length));
            for (long feePerKb : FEES_PER_KB) {
                BuildResult result = build(federation, n, MINIMUM_PEGIN, feePerKb, refundScript);
                seen.add(result.responseCode());
                if (result.responseCode() == Response.SUCCESS) {
                    long fee = fee(result, n, MINIMUM_PEGIN);
                    row.append(String.format(" %d (%.2f%%) |", fee, 100.0 * fee / (MINIMUM_PEGIN.value * n)));
                } else {
                    row.append(" ").append(result.responseCode()).append(" |");
                }
            }
            long failing = lowestFailingFeePerKb(federation, n, refundScript);
            Response failure = build(federation, n, MINIMUM_PEGIN, failing, refundScript).responseCode();
            seen.add(failure);
            BuildResult last = build(federation, n, MINIMUM_PEGIN, failing - 1, refundScript);
            row.append(String.format(" %d%s | %s | %d |", failing, failing > MAX_FEE_PER_KB ? " (above the cap)" : "", failure,
                last.btcTx().getOutput(0).getValue().value));
            System.out.println(row);
        }
    }

    @Test
    void b3_feeTables() {
        Set<Response> seen = EnumSet.noneOf(Response.class);
        int[] counts = {1, 2, 5, 10, 50, 100};
        for (PeginRefundScriptType refundType : List.of(PeginRefundScriptType.P2PKH, PeginRefundScriptType.P2TR)) {
            table("P2SH-P2WSH-ERP 5-of-9", segwitFederation(), refundType, counts, seen);
            table("P2SH-ERP 5-of-9 (legacy)", legacyFederation(), refundType, counts, seen);
        }
        System.out.println("V3STRICT-B3 responses seen in the fee sweeps: " + seen);
    }

    /** Smallest number of minimum-value federation outputs for which the build fails with EXCEED_MAX_TRANSACTION_SIZE. */
    private void maxSize(String federationName, Federation federation, PeginRefundScriptType refundType, int upperBound) {
        Script refundScript = refund(refundType);
        long feePerKb = 8_000;
        int low = 1;
        int high = upperBound;
        Response atHigh = build(federation, high, MINIMUM_PEGIN, feePerKb, refundScript).responseCode();
        if (atHigh == Response.SUCCESS) {
            BuildResult result = build(federation, high, MINIMUM_PEGIN, feePerKb, refundScript);
            System.out.printf("V3STRICT-B3 maxsize federation=%s refund=%s: still SUCCESS at n=%d (serialized as built %d bytes, fee %d)%n",
                federationName, refundType, high, result.btcTx().bitcoinSerialize().length, fee(result, high, MINIMUM_PEGIN));
            return;
        }
        while (high - low > 1) {
            int mid = (low + high) / 2;
            if (build(federation, mid, MINIMUM_PEGIN, feePerKb, refundScript).responseCode() == Response.SUCCESS) {
                low = mid;
            } else {
                high = mid;
            }
        }
        Response failure = build(federation, high, MINIMUM_PEGIN, feePerKb, refundScript).responseCode();
        BuildResult last = build(federation, low, MINIMUM_PEGIN, feePerKb, refundScript);
        long sizeUsedAtLast = fee(build(federation, low, Coin.COIN, 1_000_000, refundScript), low, Coin.COIN) / 1000;
        System.out.printf("V3STRICT-B3 maxsize federation=%s refund=%s feePerKb=%d: first failure at n=%d (%s), deposit %d sat = %.4f BTC; "
                + "last success n=%d: serialized as built %d bytes, size used for fee %d, fee %d sat%n",
            federationName, refundType, feePerKb, high, failure, MINIMUM_PEGIN.value * high, MINIMUM_PEGIN.value * high / 1e8,
            low, last.btcTx().bitcoinSerialize().length, sizeUsedAtLast, fee(last, low, MINIMUM_PEGIN));
        assertEquals(Response.EXCEED_MAX_TRANSACTION_SIZE, failure);
    }

    @Test
    void b3_maximumTransactionSize() {
        for (PeginRefundScriptType refundType : List.of(PeginRefundScriptType.P2PKH, PeginRefundScriptType.P2TR)) {
            maxSize("P2SH-ERP 5-of-9 (legacy)", legacyFederation(), refundType, 400);
            maxSize("P2SH-P2WSH-ERP 5-of-9", segwitFederation(), refundType, 3000);
        }
    }

    @Test
    void b3_segwitReleaseLargerThanBitcoinCoresStandardSize() {
        // The size check in the wallet is on the serialization without witnesses; the fee is charged on the
        // virtual size. Where does the virtual size pass 100,000 vbytes (Bitcoin Core's standardness limit)?
        Script refundScript = refund(PeginRefundScriptType.P2PKH);
        for (int n : new int[]{350, 351, 1000, 2437}) {
            BuildResult probe = build(segwitFederation(), n, Coin.COIN, 1_000_000, refundScript);
            System.out.printf("V3STRICT-B3 segwit federation, n=%d: response %s, size used for fee %d vbytes, deposit at the minimum %.3f BTC%n",
                n, probe.responseCode(), fee(probe, n, Coin.COIN) / 1000, MINIMUM_PEGIN.value * n / 1e8);
            assertEquals(Response.SUCCESS, probe.responseCode());
        }
    }

    @Test
    void b3_otherResponses() {
        // nothing to spend (cannot happen for a deposit that passed the minimum-value check)
        Script refundScript = refund(PeginRefundScriptType.P2PKH);
        System.out.println("V3STRICT-B3 no federation output at all: " + build(segwitFederation(), 0, MINIMUM_PEGIN, 8_000, refundScript).responseCode());
        // at 8000 sat/kB the wallet's 5000-sat minimum fee applies (329 * 8 = 2632 < 5000), and what is left must
        // not be dust by bitcoinj's rule (2730 sat for a P2PKH output): the smallest refundable single output is 7730 sat
        for (long value : new long[]{1, 2632, 5000, 5546, 7729, 7730}) {
            System.out.println("V3STRICT-B3 one federation output of " + value + " sat at 8000 sat/kB: "
                + build(segwitFederation(), 1, Coin.valueOf(value), 8_000, refundScript).responseCode());
        }
    }

    @Test
    void b5_printRefundOutputScripts() {
        // the exact output scripts PeginRefundScriptType builds, for testmempoolaccept against Bitcoin Core
        for (PeginRefundScriptType type : PeginRefundScriptType.values()) {
            byte[] program = new byte[type.getProgramLength()];
            for (int i = 0; i < program.length; i++) {
                program[i] = (byte) (0x20 + i + type.getTypeByte());
            }
            Script script = type.buildOutputScript(program);
            System.out.println("V3STRICT-B5 " + type + " " + Hex.toHexString(script.getProgram()));
        }
    }

    @Test
    void b8_signedRefundsAroundTheRelayLimit() throws Exception {
        // Fully signed refunds (5 of 9 signatures per input) written as hex for Bitcoin Core, which reports
        // their virtual size and whether its default policy accepts that size. The outpoints do not exist,
        // so Core can judge the size only.
        java.nio.file.Path out = java.nio.file.Path.of("build/v3strict/b8");
        java.nio.file.Files.createDirectories(out);
        List<BtcECKey> keys = BitcoinTestUtils.getBtcEcKeysFromSeeds(NINE_MEMBERS, true);
        Federation legacy = P2shErpFederationBuilder.builder().withMembersBtcPublicKeys(keys).build();
        for (boolean segwit : new boolean[]{false, true}) {
            Federation federation = segwit ? segwitFederation() : legacy;
            int[] counts = segwit ? new int[]{1, 350, 351, 352, 353, 354, 355, 356, 357, 358} : new int[]{1, 112, 113, 114, 115, 116, 117, 118};
            for (int n : counts) {
                BuildResult result = build(federation, n, MINIMUM_PEGIN, 8_000, refund(PeginRefundScriptType.P2PKH));
                assertEquals(Response.SUCCESS, result.responseCode());
                BtcTransaction tx = result.btcTx();
                int unsigned = tx.bitcoinSerialize().length;
                for (int j = 0; j < n; j++) {
                    if (segwit) {
                        BitcoinTestUtils.signWitnessTransactionInputFromP2shMultiSig(tx, j, MINIMUM_PEGIN, keys);
                    } else {
                        BitcoinTestUtils.signLegacyTransactionInputFromP2shMultiSig(tx, j, keys);
                    }
                }
                int signed = tx.bitcoinSerialize().length;
                assertTrue(signed - unsigned > 330 * n, "inputs were not signed");
                String name = (segwit ? "segwit" : "legacy") + "-" + n;
                java.nio.file.Files.writeString(out.resolve(name + ".hex"), Hex.toHexString(tx.bitcoinSerialize()));
                System.out.println("V3STRICT-B8 " + name + ": builder accepted, serialised bytes unsigned=" + unsigned + " signed=" + signed);
            }
        }
    }
}
