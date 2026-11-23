package co.rsk.peg;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.core.RskAddress;
import co.rsk.core.bc.BlockChainImpl;
import co.rsk.db.RepositoryLocator;
import co.rsk.db.RepositorySnapshot;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationTestUtils;
import co.rsk.peg.pegininstructions.PeginInstructionsVersion3;
import co.rsk.peg.pegininstructions.PeginRefundScriptType;
import co.rsk.test.World;
import co.rsk.test.builders.BlockBuilder;
import java.math.BigInteger;
import java.util.*;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.Constants;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.core.*;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.HashUtil;
import org.ethereum.db.TransactionInfo;
import org.ethereum.vm.DataWord;
import org.ethereum.vm.LogInfo;
import org.ethereum.vm.PrecompiledContracts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

/**
 * Experiments B1 and B4 for peg-in instructions v3 (strict REQUIRE_CODE), through real block execution:
 * blocks are built and connected with the real BlockExecutor / TransactionExecutor / VM and the real Bridge
 * precompile (World + BlockBuilder, regtest constants, every consensus rule active at 0).
 *
 * What is NOT real: the Bitcoin side. The Bridge's BTC block store is seeded directly in the genesis state
 * with a header that commits to the deposit (no proof of work, no receiveHeaders), and the deposits are
 * unsigned transactions that exist on no Bitcoin network. For B1 the fault is injected by Mockito
 * (mockConstruction of ReleaseTransactionBuilder), since no input reaches UTXO_PROVIDER_EXCEPTION on this path.
 *
 * Every test prints the logs found in the receipts (lines starting with "V3STRICT").
 */
class V3StrictExecutionExperimentTest {
    // an ordinary account, not a federation member (a federator's Bridge calls are free of gas)
    private static final ECKey SENDER = ECKey.fromPrivate(HashUtil.keccak256("v3strict-sender".getBytes()));
    private static final RskAddress SENDER_ADDRESS = new RskAddress(SENDER.getAddress());
    private static final RskAddress BRIDGE = PrecompiledContracts.BRIDGE_ADDR;
    private static final Coin DEPOSIT = Coin.valueOf(1_000_000);
    private static final int BTC_CHAIN_HEAD_HEIGHT = 500;

    // runtime: copies its calldata, CALLs the Bridge with it, stores (success + 1) at storage[0], logs it (LOG0), stops
    private static final byte[] CALLER_RUNTIME = Hex.decode(
        "3660006000376000600036600060007300000000000000000000000000000000010000065af1"
            + "600101806000556000526020" + "6000a000");
    private static final byte[] NON_EMPTY_RUNTIME = Hex.decode("00"); // STOP
    private static final byte[] EMPTY_RUNTIME = new byte[0];

    private World world;
    private BlockChainImpl blockChain;
    private RepositoryLocator repositoryLocator;
    private BridgeConstants bridgeConstants;
    private NetworkParameters btcParams;
    private ActivationConfig.ForBlock activations;
    private Federation federation;
    private Block best;
    private long nonce;
    private final Map<Sha256Hash, Integer> depositHeights = new HashMap<>();
    private final Map<Sha256Hash, PartialMerkleTree> depositPmts = new HashMap<>();
    private int nextDepositHeight = 10;
    private int depositCounter = 0;
    private Script refundScript;

    @BeforeEach
    void setUp() {
        world = new World();
        blockChain = world.getBlockChain();
        repositoryLocator = world.getRepositoryLocator();
        bridgeConstants = world.getConfig().getNetworkConstants().getBridgeConstants();
        btcParams = bridgeConstants.getBtcParams();
        activations = world.getConfig().getActivationConfig().forBlock(1);
        assertTrue(activations.isActive(ConsensusRule.RSKIP_PEGIN_INSTRUCTIONS_V3));
        federation = FederationTestUtils.getGenesisFederation(bridgeConstants.getFederationConstants());
        refundScript = PeginRefundScriptType.P2PKH.buildOutputScript(new BtcECKey().getPubKeyHash());
        nonce = 0;
    }

    // ------------------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------------------

    private byte[] contractAddressForNonce(long creationNonce) {
        return HashUtil.calcNewAddr(SENDER.getAddress(), BigInteger.valueOf(creationNonce).toByteArray());
    }

    private BtcTransaction buildDeposit(RskAddress destination, int flags) {
        BtcTransaction deposit = new BtcTransaction(btcParams);
        deposit.addInput(BitcoinTestUtils.createHash(1000 + depositCounter++), 0, ScriptBuilder.createInputScript(null, new BtcECKey()));
        deposit.addOutput(DEPOSIT, federation.getAddress());
        deposit.addOutput(Coin.ZERO, PegTestUtils.createOpReturnScriptForRskV3(
            destination, flags, Optional.of(PeginRefundScriptType.P2PKH), refundScript.getPubKeyHash()));
        return deposit;
    }

    /** Funds the sender and the Bridge and writes the Bitcoin headers committing to the deposits into the genesis state. */
    private void startChain(BtcTransaction... deposits) throws Exception {
        Repository repository = world.getRepository();
        repository.addBalance(SENDER_ADDRESS, new co.rsk.core.Coin(new BigInteger("10000000000000000000")));
        repository.addBalance(BRIDGE, co.rsk.core.Coin.fromBitcoin(bridgeConstants.getMaxRbtc()));

        ActivationConfig.ForBlock genesisActivations = world.getConfig().getActivationConfig().forBlock(0);
        BridgeStorageProvider provider = new BridgeStorageProvider(repository, btcParams, genesisActivations);
        BtcBlockStoreWithCache store = new RepositoryBtcBlockStoreWithCache.Factory(btcParams)
            .newInstance(repository, bridgeConstants, provider, genesisActivations);
        for (BtcTransaction deposit : deposits) {
            PartialMerkleTree pmt = BridgeSupportTestUtil.createValidPmtForTransactions(List.of(deposit), btcParams);
            int height = nextDepositHeight++;
            BridgeSupportTestUtil.recreateChainFromPmt(store, BTC_CHAIN_HEAD_HEIGHT, pmt, height, btcParams);
            depositHeights.put(deposit.getHash(), height);
            depositPmts.put(deposit.getHash(), pmt);
        }
        provider.save();

        Genesis genesis = (Genesis) blockChain.getBestBlock();
        genesis.setStateRoot(repository.getRoot());
        genesis.flushRLP();
        world.getBlockStore().saveBlock(genesis, genesis.getCumulativeDifficulty(), true);
        best = genesis;
    }

    private byte[] registerCallData(BtcTransaction deposit) {
        return Bridge.REGISTER_BTC_TRANSACTION.encode(
            deposit.bitcoinSerialize(), depositHeights.get(deposit.getHash()), depositPmts.get(deposit.getHash()).bitcoinSerialize());
    }

    private Transaction sign(Transaction tx) {
        tx.sign(SENDER.getPrivKeyBytes());
        return tx;
    }

    private Transaction txTo(RskAddress to, byte[] data) {
        return sign(Transaction.builder()
            .nonce(BigInteger.valueOf(nonce++))
            .gasPrice(BigInteger.ZERO)
            .gasLimit(BigInteger.valueOf(600_000))
            .destination(to)
            .value(BigInteger.ZERO)
            .data(data)
            .chainId(Constants.REGTEST_CHAIN_ID)
            .build());
    }

    private Transaction registerDirectly(BtcTransaction deposit) {
        return txTo(BRIDGE, registerCallData(deposit));
    }

    private Transaction registerThroughContract(RskAddress caller, BtcTransaction deposit) {
        return txTo(caller, registerCallData(deposit));
    }

    private Transaction deploy(byte[] runtime) {
        // init code: PUSH1 len DUP1 PUSH1 0x0b PUSH1 0 CODECOPY PUSH1 0 RETURN, then the runtime
        byte[] prefix = new byte[]{0x60, (byte) runtime.length, (byte) 0x80, 0x60, 0x0b, 0x60, 0x00, 0x39, 0x60, 0x00, (byte) 0xf3};
        byte[] init = new byte[prefix.length + runtime.length];
        System.arraycopy(prefix, 0, init, 0, prefix.length);
        System.arraycopy(runtime, 0, init, prefix.length, runtime.length);
        return sign(Transaction.builder()
            .nonce(BigInteger.valueOf(nonce++))
            .gasPrice(BigInteger.ZERO)
            .gasLimit(BigInteger.valueOf(600_000))
            .value(BigInteger.ZERO)
            .data(init)
            .chainId(Constants.REGTEST_CHAIN_ID)
            .build());
    }

    /** Builds a block with the real executor (as a miner would) and connects it (as a validating node would). */
    private Block mine(Transaction... txs) {
        Block block = new BlockBuilder(blockChain, world.getBridgeSupportFactory(), world.getBlockStore())
            .trieStore(world.getTrieStore())
            .difficulty(best.getDifficulty().asBigInteger().longValue())
            .parent(best)
            .transactions(Arrays.asList(txs))
            .uncles(new ArrayList<>())
            .build();
        assertEquals(ImportResult.IMPORTED_BEST, blockChain.tryToConnect(block));
        assertEquals(txs.length, block.getTransactionsList().size(), "a transaction was dropped from the block");
        best = block;
        return block;
    }

    private TransactionReceipt receipt(Transaction tx) {
        TransactionInfo info = blockChain.getTransactionInfo(tx.getHash().getBytes());
        assertNotNull(info, "no receipt: the transaction was not included");
        return info.getReceipt();
    }

    private static String eventName(LogInfo log) {
        if (log.getTopics().isEmpty()) {
            return "LOG0(data=" + Hex.toHexString(log.getData()) + ")";
        }
        byte[] topic = log.getTopics().get(0).getData();
        for (BridgeEvents event : BridgeEvents.values()) {
            if (Arrays.equals(event.getEvent().encodeSignatureLong(), topic)) {
                return event.name();
            }
        }
        return "unknown(" + Hex.toHexString(topic) + ")";
    }

    private List<String> logs(Transaction tx) {
        List<String> names = new ArrayList<>();
        for (LogInfo log : receipt(tx).getLogInfoList()) {
            String name = eventName(log);
            if (name.equals("REJECTED_PEGIN") || name.equals("UNREFUNDABLE_PEGIN")) {
                // last 32 bytes of the data: the reason
                byte[] data = log.getData();
                name += "(" + new BigInteger(1, Arrays.copyOfRange(data, data.length - 32, data.length)) + ")";
            }
            names.add((new RskAddress(log.getAddress()).equals(BRIDGE) ? "bridge:" : "contract:") + name);
        }
        return names;
    }

    private RepositorySnapshot state() {
        return repositoryLocator.snapshotAt(best.getHeader());
    }

    private BridgeStorageProvider bridgeState(RepositorySnapshot snapshot) {
        return new BridgeStorageProvider(repositoryLocator.startTrackingAt(best.getHeader()), btcParams, activations);
    }

    private boolean isProcessed(BtcTransaction deposit) throws Exception {
        return bridgeState(state()).getHeightIfBtcTxhashIsAlreadyProcessed(deposit.getHash()).isPresent();
    }

    private int queuedReleases() throws Exception {
        return bridgeState(state()).getPegoutsWaitingForConfirmations().getEntries(org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest.all().forBlock(0)).size();
    }

    /** Every storage cell of the Bridge account, to compare the Bridge state before and after a failed call. */
    private Map<String, String> bridgeStorage() {
        RepositorySnapshot snapshot = state();
        Map<String, String> cells = new TreeMap<>();
        Iterator<DataWord> keys = snapshot.getStorageKeys(BRIDGE);
        while (keys.hasNext()) {
            DataWord key = keys.next();
            byte[] value = snapshot.getStorageBytes(BRIDGE, key);
            cells.put(key.toString(), value == null ? "" : Hex.toHexString(value));
        }
        return cells;
    }

    private void report(String label, Transaction tx) {
        TransactionReceipt receipt = receipt(tx);
        System.out.println("V3STRICT " + label + ": status=" + (receipt.isSuccessful() ? "success" : "failed")
            + " gasUsed=" + new BigInteger(1, receipt.getGasUsed()) + " logs=" + logs(tx));
    }

    // ------------------------------------------------------------------------------------------------
    // B1: an internal error aborts the registration and leaves nothing behind
    // ------------------------------------------------------------------------------------------------

    private enum Fault { UTXO_PROVIDER_EXCEPTION, THROWN }

    private MockedConstruction<ReleaseTransactionBuilder> inject(Fault fault) {
        return mockConstruction(ReleaseTransactionBuilder.class, (builder, context) -> {
            if (fault == Fault.UTXO_PROVIDER_EXCEPTION) {
                when(builder.buildEmptyWalletTo(any(Script.class))).thenReturn(
                    new ReleaseTransactionBuilder.BuildResult(null, null, ReleaseTransactionBuilder.Response.UTXO_PROVIDER_EXCEPTION));
            } else {
                when(builder.buildEmptyWalletTo(any(Script.class))).thenThrow(new IllegalStateException("injected fault"));
            }
        });
    }

    private void b1(Fault fault, boolean throughContract) throws Exception {
        RskAddress destination = new RskAddress("00000000000000000000000000000000000c0de5"); // no code
        BtcTransaction deposit = buildDeposit(destination, PeginInstructionsVersion3.REQUIRE_CODE);
        startChain(deposit);

        RskAddress caller = null;
        if (throughContract) {
            caller = new RskAddress(contractAddressForNonce(nonce));
            Transaction deployCaller = deploy(CALLER_RUNTIME);
            mine(deployCaller);
            assertArrayEquals(CALLER_RUNTIME, state().getCode(caller));
        }
        String label = "B1 " + fault + (throughContract ? " via-contract" : " direct");

        Map<String, String> bridgeStorageBefore = bridgeStorage();
        co.rsk.core.Coin bridgeBalanceBefore = state().getBalance(BRIDGE);
        co.rsk.core.Coin destinationBalanceBefore = state().getBalance(destination);

        // the faulty attempt
        Transaction faulty = throughContract ? registerThroughContract(caller, deposit) : registerDirectly(deposit);
        int buildersIntercepted;
        try (MockedConstruction<ReleaseTransactionBuilder> mocked = inject(fault)) {
            mine(faulty);
            buildersIntercepted = mocked.constructed().size();
        }
        // twice: once when the block is built, once when it is connected
        System.out.println("V3STRICT " + label + ": builders intercepted=" + buildersIntercepted);
        assertTrue(buildersIntercepted >= 1, "the fault was not injected on the executing thread");
        report(label + " faulty attempt", faulty);

        assertFalse(isProcessed(deposit), "deposit marked processed by an aborted registration");
        assertEquals(0, queuedReleases(), "release queued by an aborted registration");
        assertEquals(bridgeBalanceBefore, state().getBalance(BRIDGE));
        assertEquals(destinationBalanceBefore, state().getBalance(destination));
        assertEquals(bridgeStorageBefore, bridgeStorage(), "Bridge storage changed by an aborted registration");
        List<String> faultyLogs = logs(faulty);
        System.out.println("V3STRICT " + label + ": bridge storage unchanged, not processed, nothing queued, balances unchanged");
        if (throughContract) {
            // the contract saw the call fail (storage[0] == 1), carried on, and its own log is in the receipt
            assertEquals(DataWord.valueOf(1), state().getStorageValue(caller, DataWord.ZERO));
            assertTrue(receipt(faulty).isSuccessful());
            assertEquals(List.of("contract:LOG0(data=" + "0".repeat(63) + "1)"), faultyLogs, "a Bridge log survived the failed call");
        } else {
            assertTrue(faultyLogs.isEmpty(), "a Bridge log survived the failed call: " + faultyLogs);
        }

        // the retry, with the fault removed
        Transaction retry = throughContract ? registerThroughContract(caller, deposit) : registerDirectly(deposit);
        mine(retry);
        report(label + " retry", retry);
        List<String> retryLogs = logs(retry);
        assertTrue(isProcessed(deposit));
        assertEquals(1, queuedReleases());
        assertEquals(destinationBalanceBefore, state().getBalance(destination));
        assertEquals("bridge:REJECTED_PEGIN(6)", retryLogs.get(0));
        assertEquals("bridge:RELEASE_REQUESTED", retryLogs.get(1));
        assertFalse(retryLogs.contains("bridge:PEGIN_BTC"));
        if (throughContract) {
            assertEquals(DataWord.valueOf(2), state().getStorageValue(caller, DataWord.ZERO));
        }
    }

    @Test
    void b1_utxoProviderException_directCall() throws Exception {
        b1(Fault.UTXO_PROVIDER_EXCEPTION, false);
    }

    @Test
    void b1_thrown_directCall() throws Exception {
        b1(Fault.THROWN, false);
    }

    @Test
    void b1_utxoProviderException_throughContract() throws Exception {
        b1(Fault.UTXO_PROVIDER_EXCEPTION, true);
    }

    @Test
    void b1_thrown_throughContract() throws Exception {
        b1(Fault.THROWN, true);
    }

    // ------------------------------------------------------------------------------------------------
    // B7: internal errors after the release is built (loading the release queue, saving Bridge state)
    // The draft requires only that the deposit stays unprocessed and can be registered again. What else a
    // failed precompile call leaves behind (events, and state in a direct call) is node behaviour and is
    // printed, not asserted: see RSKIP692.
    // ------------------------------------------------------------------------------------------------

    private enum LateFault { QUEUE_LOAD, STATE_SAVE }

    private void b7(LateFault fault, boolean throughContract) throws Exception {
        RskAddress destination = new RskAddress("00000000000000000000000000000000000c0de5");
        BtcTransaction deposit = buildDeposit(destination, PeginInstructionsVersion3.REQUIRE_CODE);
        startChain(deposit);
        RskAddress caller = null;
        if (throughContract) {
            caller = new RskAddress(contractAddressForNonce(nonce));
            mine(deploy(CALLER_RUNTIME));
        }
        String label = "B7 " + fault + (throughContract ? " via-contract" : " direct");
        Map<String, String> before = bridgeStorage();
        co.rsk.core.Coin balanceBefore = state().getBalance(BRIDGE);
        Transaction faulty = throughContract ? registerThroughContract(caller, deposit) : registerDirectly(deposit);
        java.util.concurrent.atomic.AtomicInteger injected = new java.util.concurrent.atomic.AtomicInteger();
        try (org.mockito.MockedStatic<BridgeSerializationUtils> mocked = org.mockito.Mockito.mockStatic(
                BridgeSerializationUtils.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            if (fault == LateFault.QUEUE_LOAD) {
                mocked.when(() -> BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(
                    org.mockito.ArgumentMatchers.nullable(byte[].class), org.mockito.ArgumentMatchers.eq(btcParams)))
                    .thenAnswer(invocation -> {
                        injected.incrementAndGet();
                        throw new IllegalStateException("injected: release queue cannot be loaded");
                    });
            } else {
                mocked.when(() -> BridgeSerializationUtils.serializePegoutsWaitingForConfirmations(
                    org.mockito.ArgumentMatchers.any(PegoutsWaitingForConfirmations.class)))
                    .thenAnswer(invocation -> {
                        injected.incrementAndGet();
                        throw new IllegalStateException("injected: release queue cannot be saved");
                    });
                mocked.when(() -> BridgeSerializationUtils.serializePegoutsWaitingForConfirmationsWithTxHash(
                    org.mockito.ArgumentMatchers.any(PegoutsWaitingForConfirmations.class)))
                    .thenAnswer(invocation -> {
                        injected.incrementAndGet();
                        throw new IllegalStateException("injected: release queue cannot be saved");
                    });
            }
            mine(faulty);
        }
        System.out.println("V3STRICT " + label + ": faults injected=" + injected.get());
        assertTrue(injected.get() > 0, "the fault did not execute");
        report(label + " faulty attempt", faulty);
        boolean processed = isProcessed(deposit);
        int queued = queuedReleases();
        boolean storageUnchanged = before.equals(bridgeStorage());
        Map<String, String> after = bridgeStorage();
        for (String key : after.keySet()) {
            if (!after.get(key).equals(before.get(key))) {
                String name = "unknown";
                for (BridgeStorageIndexKey index : BridgeStorageIndexKey.values()) {
                    if (index.getKey().toString().equals(key)) {
                        name = index.name();
                    }
                }
                System.out.println("V3STRICT " + label + ": changed Bridge storage cell " + name + " (" + key.substring(0, 12) + "..) was "
                    + (before.containsKey(key) ? before.get(key).length() / 2 + " bytes" : "absent") + " now " + after.get(key).length() / 2 + " bytes");
            }
        }
        boolean bridgeLog = receipt(faulty).getLogInfoList().stream().anyMatch(log -> new RskAddress(log.getAddress()).equals(BRIDGE));
        System.out.println("V3STRICT " + label + ": processed=" + processed + " queued=" + queued + " bridgeStorageUnchanged=" + storageUnchanged
            + " bridgeBalanceUnchanged=" + balanceBefore.equals(state().getBalance(BRIDGE)) + " bridgeLogInReceipt=" + bridgeLog);

        // the retry, with the fault removed
        Transaction retry = throughContract ? registerThroughContract(caller, deposit) : registerDirectly(deposit);
        mine(retry);
        report(label + " retry", retry);
        System.out.println("V3STRICT " + label + ": after retry processed=" + isProcessed(deposit) + " queued=" + queuedReleases());

        assertFalse(processed, "deposit marked processed by an aborted registration");
        assertEquals(0, queued, "release queued by an aborted registration");
        assertTrue(isProcessed(deposit), "retry did not register the deposit");
        assertEquals(1, queuedReleases());
    }

    @Test
    void b7_queueLoadFails_directCall() throws Exception {
        b7(LateFault.QUEUE_LOAD, false);
    }

    @Test
    void b7_queueLoadFails_throughContract() throws Exception {
        b7(LateFault.QUEUE_LOAD, true);
    }

    @Test
    void b7_stateSaveFails_directCall() throws Exception {
        b7(LateFault.STATE_SAVE, false);
    }

    @Test
    void b7_stateSaveFails_throughContract() throws Exception {
        b7(LateFault.STATE_SAVE, true);
    }

    // ------------------------------------------------------------------------------------------------
    // B4: the code check reads the state at the point of registration, inside the block
    // ------------------------------------------------------------------------------------------------

    private void assertCredited(String label, Transaction register, BtcTransaction deposit, RskAddress destination) throws Exception {
        report(label, register);
        assertEquals(List.of("bridge:PEGIN_BTC"), logs(register));
        assertEquals(co.rsk.core.Coin.fromBitcoin(DEPOSIT), state().getBalance(destination));
        assertTrue(isProcessed(deposit));
        assertEquals(0, queuedReleases());
    }

    private void assertRefunded(String label, Transaction register, BtcTransaction deposit, RskAddress destination) throws Exception {
        report(label, register);
        List<String> logs = logs(register);
        assertEquals("bridge:REJECTED_PEGIN(6)", logs.get(0));
        assertEquals("bridge:RELEASE_REQUESTED", logs.get(1));
        assertFalse(logs.contains("bridge:PEGIN_BTC"));
        assertEquals(co.rsk.core.Coin.ZERO, state().getBalance(destination));
        assertTrue(isProcessed(deposit));
        assertEquals(1, queuedReleases());
        BtcTransaction release = bridgeState(state()).getPegoutsWaitingForConfirmations().getEntries(org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest.all().forBlock(0)).iterator().next().getBtcTransaction();
        assertEquals(1, release.getOutputs().size());
        assertEquals(refundScript, release.getOutput(0).getScriptPubKey());
        System.out.println("V3STRICT " + label + ": refund output value=" + release.getOutput(0).getValue().value + " of " + DEPOSIT.value);
    }

    @Test
    void b4_i_codeDeployedEarlierInTheSameBlock_isCredited() throws Exception {
        RskAddress destination = new RskAddress(contractAddressForNonce(0));
        BtcTransaction deposit = buildDeposit(destination, PeginInstructionsVersion3.REQUIRE_CODE);
        startChain(deposit);

        Transaction deployment = deploy(NON_EMPTY_RUNTIME);
        Transaction register = registerDirectly(deposit);
        Block block = mine(deployment, register);

        assertEquals(deployment.getHash(), block.getTransactionsList().get(0).getHash());
        assertEquals(register.getHash(), block.getTransactionsList().get(1).getHash());
        assertArrayEquals(NON_EMPTY_RUNTIME, state().getCode(destination));
        assertCredited("B4(i) deployed earlier in the same block", register, deposit, destination);
    }

    @Test
    void b4_ii_codeDeployedLaterInTheSameBlock_isRefunded() throws Exception {
        RskAddress destination = new RskAddress(contractAddressForNonce(1));
        BtcTransaction deposit = buildDeposit(destination, PeginInstructionsVersion3.REQUIRE_CODE);
        startChain(deposit);

        Transaction register = registerDirectly(deposit);
        Transaction deployment = deploy(NON_EMPTY_RUNTIME);
        Block block = mine(register, deployment);

        assertEquals(register.getHash(), block.getTransactionsList().get(0).getHash());
        assertEquals(deployment.getHash(), block.getTransactionsList().get(1).getHash());
        assertArrayEquals(NON_EMPTY_RUNTIME, state().getCode(destination)); // the code is there at the end of the block
        assertRefunded("B4(ii) deployed later in the same block", register, deposit, destination);
    }

    @Test
    void b4_iii_codeDeployedInTheNextBlock_isRefunded_andNotReclassified() throws Exception {
        RskAddress destination = new RskAddress(contractAddressForNonce(1));
        BtcTransaction deposit = buildDeposit(destination, PeginInstructionsVersion3.REQUIRE_CODE);
        startChain(deposit);

        Transaction register = registerDirectly(deposit);
        mine(register);
        assertRefunded("B4(iii) deployed in the next block", register, deposit, destination);

        Transaction deployment = deploy(NON_EMPTY_RUNTIME);
        mine(deployment);
        assertArrayEquals(NON_EMPTY_RUNTIME, state().getCode(destination));

        // no reclassification: registering again, now that the code exists, changes nothing
        Transaction again = registerDirectly(deposit);
        mine(again);
        report("B4(iii) registered again after deployment", again);
        assertTrue(logs(again).isEmpty());
        assertEquals(co.rsk.core.Coin.ZERO, state().getBalance(destination));
        assertEquals(1, queuedReleases());
    }

    @Test
    void b4_iv_contractWithEmptyRuntimeCode_isRefunded() throws Exception {
        RskAddress destination = new RskAddress(contractAddressForNonce(0));
        BtcTransaction deposit = buildDeposit(destination, PeginInstructionsVersion3.REQUIRE_CODE);
        startChain(deposit);

        Transaction deployment = deploy(EMPTY_RUNTIME);
        mine(deployment);
        assertTrue(receipt(deployment).isSuccessful());
        assertTrue(state().isExist(destination), "the empty contract's account was not created");
        byte[] code = state().getCode(destination);
        assertTrue(code == null || code.length == 0);
        System.out.println("V3STRICT B4(iv): account exists=" + state().isExist(destination) + " isContract=" + state().isContract(destination)
            + " code=" + (code == null ? "null" : "length " + code.length));

        Transaction register = registerDirectly(deposit);
        mine(register);
        assertRefunded("B4(iv) empty runtime code", register, deposit, destination);
    }

    @Test
    void b4_control_flagClear_codelessDestination_isCredited() throws Exception {
        RskAddress destination = new RskAddress("00000000000000000000000000000000000c0de5");
        BtcTransaction deposit = buildDeposit(destination, 0);
        startChain(deposit);

        Transaction register = registerDirectly(deposit);
        mine(register);
        assertCredited("B4(control) flag clear, no code", register, deposit, destination);
    }
}
