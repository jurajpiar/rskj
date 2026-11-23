package co.rsk.peg;

import static co.rsk.RskTestUtils.createRskBlock;
import static co.rsk.peg.bitcoin.BitcoinUtils.*;
import static co.rsk.peg.bitcoin.UtxoUtils.extractOutpointValues;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.crypto.Keccak256;
import co.rsk.peg.ReleaseTransactionBuilder.BuildResult;
import co.rsk.peg.ReleaseTransactionBuilder.Response;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.FeePerKbSupport;
import co.rsk.peg.pegininstructions.PeginRefundScriptType;
import co.rsk.peg.storage.InMemoryStorage;
import co.rsk.peg.utils.BridgeEventLoggerImpl;
import co.rsk.test.builders.BridgeSupportBuilder;
import co.rsk.test.builders.FederationSupportBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest;
import org.ethereum.core.Block;
import org.ethereum.core.Repository;
import org.ethereum.vm.LogInfo;
import org.junit.jupiter.api.Test;

/**
 * Experiment B2: can a rejection release that spends outputs of BOTH live federations be fully signed through
 * BridgeSupport.addSignature?
 *
 * Real: the federations (9 members, 5-of-9, keys from test seeds, regtest network), the wallet and
 * ReleaseTransactionBuilder call made exactly as BridgeSupport makes them for a rejection release, the
 * BridgeSupport.addSignature path with real storage, and the signatures (each federator signs every input with
 * its own key, as addSignature requires).
 * Not real: the release is put into the "waiting for signatures" set directly, since BridgeSupport itself refuses
 * to build it for a flagged v3 deposit (and for federations of different formats in every version); the
 * confirmation wait is skipped.
 *
 * Optional Bitcoin Core check: if build/v3strict/b2-deposits.txt exists (lines "caseId rawTxHex", real regtest
 * transactions paying both federation addresses printed by this test), the releases spend those outputs and the
 * resulting transactions are written to build/v3strict/b2-releases.txt for testmempoolaccept.
 */
class V3StrictTwoFederationsSigningExperimentTest {
    private static final BridgeConstants BRIDGE_CONSTANTS = new BridgeRegTestConstants();
    private static final NetworkParameters PARAMS = BRIDGE_CONSTANTS.getBtcParams();
    private static final ActivationConfig.ForBlock ACTIVATIONS = ActivationConfigsForTest.all().forBlock(0);
    private static final Coin VALUE_PER_FEDERATION = Coin.valueOf(1_000_000);
    private static final Path DEPOSITS = Path.of("build/v3strict/b2-deposits.txt");
    private static final Path RELEASES = Path.of("build/v3strict/b2-releases.txt");

    private static final List<BtcECKey> KEYS_A = keys("member", 1, 9);
    private static final List<BtcECKey> KEYS_B = keys("other", 1, 9);
    // KEYS_A with one member replaced: 8 of 9 keys shared
    private static final List<BtcECKey> KEYS_A8 = sharedEight();

    private static List<BtcECKey> keys(String prefix, int from, int to) {
        String[] seeds = new String[to - from + 1];
        for (int i = from; i <= to; i++) {
            seeds[i - from] = String.format("%s%02d", prefix, i);
        }
        return BitcoinTestUtils.getBtcEcKeysFromSeeds(seeds, true);
    }

    private static List<BtcECKey> sharedEight() {
        List<BtcECKey> keys = new ArrayList<>(keys("member", 1, 8));
        keys.add(BitcoinTestUtils.getBtcEcKeyFromSeed("newcomer"));
        keys.sort(BtcECKey.PUBKEY_COMPARATOR);
        return keys;
    }

    private static Federation legacy(List<BtcECKey> keys, long erpDelay, long creationBlock) {
        return P2shErpFederationBuilder.builder().withMembersBtcPublicKeys(keys).withErpActivationDelay(erpDelay)
            .withCreationBlockNumber(creationBlock).withNetworkParameters(PARAMS).build();
    }

    private static Federation segwit(List<BtcECKey> keys, long erpDelay, long creationBlock) {
        return P2shP2wshErpFederationBuilder.builder().withMembersBtcPublicKeys(keys).withErpActivationDelay(erpDelay)
            .withCreationBlockNumber(creationBlock).withNetworkParameters(PARAMS).build();
    }

    private record Case(String id, String description, Federation retiring, Federation active, List<BtcECKey> signers) {}

    private static List<BtcECKey> union(List<BtcECKey> first, List<BtcECKey> second) {
        List<BtcECKey> all = new ArrayList<>(first);
        for (BtcECKey key : second) {
            if (all.stream().noneMatch(k -> Arrays.equals(k.getPubKey(), key.getPubKey()))) {
                all.add(key);
            }
        }
        return all;
    }

    private static List<Case> cases() {
        long delay = 500;
        long otherDelay = 600; // same keys and format, different redeem script, so a different federation address
        return List.of(
            new Case("disjoint-legacy-legacy", "disjoint member keys, both P2SH-ERP", legacy(KEYS_A, delay, 1), legacy(KEYS_B, delay, 10), union(KEYS_B, KEYS_A)),
            new Case("disjoint-segwit-segwit", "disjoint member keys, both P2SH-P2WSH-ERP", segwit(KEYS_A, delay, 1), segwit(KEYS_B, delay, 10), union(KEYS_B, KEYS_A)),
            new Case("disjoint-legacy-segwit", "disjoint member keys, retiring P2SH-ERP, active P2SH-P2WSH-ERP", legacy(KEYS_A, delay, 1), segwit(KEYS_B, delay, 10), union(KEYS_B, KEYS_A)),
            new Case("same-legacy-legacy", "identical member keys, both P2SH-ERP (different ERP delay)", legacy(KEYS_A, delay, 1), legacy(KEYS_A, otherDelay, 10), KEYS_A),
            new Case("same-segwit-segwit", "identical member keys, both P2SH-P2WSH-ERP (different ERP delay)", segwit(KEYS_A, delay, 1), segwit(KEYS_A, otherDelay, 10), KEYS_A),
            new Case("same-legacy-segwit", "identical member keys, retiring P2SH-ERP, active P2SH-P2WSH-ERP", legacy(KEYS_A, delay, 1), segwit(KEYS_A, delay, 10), KEYS_A),
            new Case("same-segwit-legacy", "identical member keys, retiring P2SH-P2WSH-ERP, active P2SH-ERP", segwit(KEYS_A, delay, 1), legacy(KEYS_A, delay, 10), KEYS_A),
            new Case("eight-shared-segwit-segwit", "8 of 9 member keys shared, both P2SH-P2WSH-ERP", segwit(KEYS_A, delay, 1), segwit(KEYS_A8, delay, 10), union(KEYS_A8, KEYS_A)),
            // same federations, but the eight shared members sign before the member only the active federation has
            new Case("eight-shared-segwit-segwit-shared-first", "8 of 9 member keys shared, both P2SH-P2WSH-ERP, shared members sign first", segwit(KEYS_A, delay, 1), segwit(KEYS_A8, delay, 10), union(keys("member", 1, 8), KEYS_A8)),
            new Case("eight-shared-legacy-segwit", "8 of 9 member keys shared, retiring P2SH-ERP, active P2SH-P2WSH-ERP", legacy(KEYS_A, delay, 1), segwit(KEYS_A8, delay, 10), union(KEYS_A8, KEYS_A))
        );
    }

    private static Map<String, BtcTransaction> realDeposits() throws Exception {
        Map<String, BtcTransaction> deposits = new HashMap<>();
        if (Files.exists(DEPOSITS)) {
            for (String line : Files.readAllLines(DEPOSITS)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    deposits.put(parts[0], new BtcTransaction(PARAMS, Hex.decode(parts[1])));
                }
            }
        }
        return deposits;
    }

    private static String eventName(LogInfo log) {
        byte[] topic = log.getTopics().get(0).getData();
        for (BridgeEvents event : BridgeEvents.values()) {
            if (Arrays.equals(event.getEvent().encodeSignatureLong(), topic)) {
                return event.name();
            }
        }
        return "unknown";
    }

    @Test
    void b2_printFederationAddresses() {
        for (Case c : cases()) {
            System.out.println("V3STRICT-B2-ADDR " + c.id() + " " + c.active().getAddress() + " " + c.retiring().getAddress());
        }
    }

    @Test
    void b2_releaseSpendingBothFederations() throws Exception {
        Map<String, BtcTransaction> realDeposits = realDeposits();
        List<String> releases = new ArrayList<>();
        Map<String, Boolean> fullySigned = new LinkedHashMap<>();
        for (Case c : cases()) {
            fullySigned.put(c.id(), run(c, realDeposits.get(c.id()), releases));
        }
        if (!realDeposits.isEmpty()) {
            Files.createDirectories(RELEASES.getParent());
            Files.write(RELEASES, releases, StandardCharsets.UTF_8);
        }
        System.out.println("V3STRICT-B2 summary (fully signed through addSignature): " + fullySigned);

        // what the runs showed; a change here means the behaviour changed
        assertFalse(fullySigned.get("disjoint-legacy-legacy"));
        assertFalse(fullySigned.get("disjoint-segwit-segwit"));
        assertFalse(fullySigned.get("disjoint-legacy-segwit"));
    }

    private boolean run(Case c, BtcTransaction realDeposit, List<String> releases) throws Exception {
        // --- a Bridge with both federations live, real storage ---
        Repository repository = co.rsk.RskTestUtils.createRepository();
        BridgeStorageProvider provider = new BridgeStorageProvider(repository, PARAMS, ACTIVATIONS);
        List<LogInfo> logs = new ArrayList<>();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(new InMemoryStorage());
        federationStorageProvider.setOldFederation(c.retiring());
        federationStorageProvider.setNewFederation(c.active());
        FederationConstants federationConstants = BRIDGE_CONSTANTS.getFederationConstants();
        long executionBlockNumber = c.active().getCreationBlockNumber() + federationConstants.getFederationActivationAge(ACTIVATIONS);
        Block executionBlock = createRskBlock(executionBlockNumber);
        FederationSupport federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(federationConstants)
            .withFederationStorageProvider(federationStorageProvider)
            .withRskExecutionBlock(executionBlock)
            .withActivations(ACTIVATIONS)
            .build();
        assertEquals(c.active().getAddress(), federationSupport.getActiveFederation().getAddress());
        assertEquals(c.retiring().getAddress(), federationSupport.getRetiringFederation().orElseThrow().getAddress());
        FeePerKbSupport feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.valueOf(10_000));
        BridgeSupport bridgeSupport = BridgeSupportBuilder.builder()
            .withActivations(ACTIVATIONS)
            .withExecutionBlock(executionBlock)
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withProvider(provider)
            .withRepository(repository)
            .withEventLogger(new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, ACTIVATIONS, logs))
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        // --- the deposit's outputs to the two federations ---
        List<UTXO> utxos = new ArrayList<>();
        if (realDeposit != null) {
            for (TransactionOutput output : realDeposit.getOutputs()) {
                Script script = output.getScriptPubKey();
                if (script.equals(c.active().getP2SHScript()) || script.equals(c.retiring().getP2SHScript())) {
                    utxos.add(new UTXO(realDeposit.getHash(), output.getIndex(), output.getValue(), 0, false, script));
                }
            }
            assertEquals(2, utxos.size(), "the real deposit does not pay both federations");
        } else {
            Sha256Hash depositHash = BitcoinTestUtils.createHash(c.id().hashCode());
            utxos.add(new UTXO(depositHash, 0, VALUE_PER_FEDERATION, 0, false, c.active().getP2SHScript()));
            utxos.add(new UTXO(depositHash, 1, VALUE_PER_FEDERATION, 0, false, c.retiring().getP2SHScript()));
        }

        // --- the release, built as BridgeSupport.buildRejectionReleaseFromFederation builds it ---
        // (getFederationFromTxOutputs picks the active federation whenever one output pays it)
        Wallet wallet = BridgeUtils.getFederationsSpendWallet(new Context(PARAMS), federationSupport.getLiveFederations(), utxos, false, provider);
        Script refundScript = PeginRefundScriptType.P2WPKH.buildOutputScript(new byte[20]);
        ReleaseTransactionBuilder builder = new ReleaseTransactionBuilder(
            PARAMS, wallet, c.active().getFormatVersion(), c.active().getAddress(), feePerKbSupport.getFeePerKb(), ACTIVATIONS);
        BuildResult result = builder.buildEmptyWalletTo(refundScript);
        System.out.println("V3STRICT-B2 [" + c.id() + "] " + c.description() + ": builder response " + result.responseCode());
        if (result.responseCode() != Response.SUCCESS) {
            return false;
        }
        BtcTransaction release = result.btcTx();
        List<Coin> outpointValues = new ArrayList<>();
        StringBuilder inputs = new StringBuilder();
        for (int i = 0; i < release.getInputs().size(); i++) {
            TransactionInput input = release.getInput(i);
            UTXO spent = utxos.stream()
                .filter(u -> u.getHash().equals(input.getOutpoint().getHash()) && u.getIndex() == input.getOutpoint().getIndex())
                .findFirst().orElseThrow();
            outpointValues.add(spent.getValue());
            boolean paysActive = spent.getScript().equals(c.active().getP2SHScript());
            Optional<Script> redeemInInput = extractRedeemScriptFromInput(release, i);
            Script expectedRedeem = paysActive ? c.active().getRedeemScript() : c.retiring().getRedeemScript();
            inputs.append(String.format(" input%d{spends=%s, witness=%s, redeemScriptInInput=%s}", i,
                paysActive ? "active" : "retiring", inputHasWitness(release, i),
                redeemInInput.isEmpty() ? "none" : redeemInInput.get().equals(expectedRedeem) ? "own federation's" : "WRONG"));
        }
        System.out.println("V3STRICT-B2 [" + c.id() + "] release:" + inputs + " outputValue=" + release.getOutput(0).getValue().value);

        // --- queue it for signing, as the Bridge does once a release has its confirmations ---
        Keccak256 releaseCreationHash = new Keccak256(Sha256Hash.of(c.id().getBytes(StandardCharsets.UTF_8)).getBytes());
        provider.setReleaseOutpointsValues(release.getHash(), outpointValues);
        provider.getPegoutsWaitingForSignatures().put(releaseCreationHash, release);

        // --- every federator of both federations signs every input with its own key ---
        Map<String, Integer> thrown = new LinkedHashMap<>();
        for (BtcECKey signer : c.signers()) {
            BtcTransaction current = provider.getPegoutsWaitingForSignatures().get(releaseCreationHash);
            if (current == null) {
                break; // fully signed
            }
            List<byte[]> signatures = new ArrayList<>();
            for (int i = 0; i < current.getInputs().size(); i++) {
                Sha256Hash sigHash = inputHasWitness(current, i)
                    ? generateSigHashForSegwitTransactionInput(current, i, outpointValues.get(i))
                    : generateSigHashForLegacyTransactionInput(current, i);
                signatures.add(signer.sign(sigHash).encodeToDER());
            }
            try {
                bridgeSupport.addSignature(BtcECKey.fromPublicOnly(signer.getPubKey()), signatures, releaseCreationHash);
            } catch (RuntimeException e) {
                // in the Bridge this fails the federator's addSignature call
                thrown.merge(e.getClass().getSimpleName() + ": " + e.getMessage(), 1, Integer::sum);
            }
        }
        long addSignatureEvents = logs.stream().filter(l -> eventName(l).equals("ADD_SIGNATURE")).count();
        boolean releaseBtc = logs.stream().anyMatch(l -> eventName(l).equals("RELEASE_BTC"));
        boolean stillWaiting = provider.getPegoutsWaitingForSignatures().containsKey(releaseCreationHash);
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < release.getInputs().size(); i++) {
            missing.append(" input").append(i).append("=").append(signaturesIn(release, i));
        }
        System.out.println("V3STRICT-B2 [" + c.id() + "] addSignature: federators tried=" + c.signers().size()
            + " add_signature events=" + addSignatureEvents + " release_btc=" + releaseBtc + " still waiting for signatures=" + stillWaiting
            + " signatures present per input (needs 5):" + missing + " addSignature calls that threw=" + thrown);
        assertEquals(releaseBtc, !stillWaiting);
        assertEquals(extractOutpointValues(release).size(), release.getInputs().size());
        releases.add(c.id() + " " + (releaseBtc ? "fully-signed" : "incomplete") + " " + Hex.toHexString(release.bitcoinSerialize()));
        return releaseBtc;
    }

    /** Non-empty signature pushes in the input (script sig for legacy, witness for segwit). */
    private static int signaturesIn(BtcTransaction tx, int inputIndex) {
        int count = 0;
        if (inputHasWitness(tx, inputIndex)) {
            TransactionWitness witness = tx.getWitness(inputIndex);
            for (int i = 0; i < witness.getPushCount() - 1; i++) {
                byte[] push = witness.getPush(i);
                if (push.length > 60 && push.length < 75 && push[0] == 0x30) {
                    count++;
                }
            }
        } else {
            for (co.rsk.bitcoinj.script.ScriptChunk chunk : tx.getInput(inputIndex).getScriptSig().getChunks()) {
                if (chunk.data != null && chunk.data.length > 60 && chunk.data.length < 75 && chunk.data[0] == 0x30) {
                    count++;
                }
            }
        }
        return count;
    }
}
