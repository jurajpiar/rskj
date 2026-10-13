package co.rsk.peg;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.params.RegTestParams;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.bitcoinj.script.ScriptOpCodes;
import co.rsk.core.RskAddress;
import co.rsk.crypto.Keccak256;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.federation.FederationTestUtils;
import co.rsk.peg.pegin.RejectedPeginReason;
import co.rsk.peg.pegininstructions.PeginInstructions;
import co.rsk.peg.pegininstructions.PeginInstructionsException;
import co.rsk.peg.pegininstructions.PeginInstructionsParseException;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import co.rsk.peg.pegininstructions.PeginInstructionsVersion1;
import co.rsk.peg.pegininstructions.PeginInstructionsVersion3;
import co.rsk.peg.utils.NonRefundablePeginReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.core.CallTransaction;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.HashUtil;
import org.ethereum.vm.PrecompiledContracts;

/**
 * PoC-only adapter for the SLUICE conditional peg-in conformance suite (legacy-refund profile).
 *
 * Drives two live rskj regtest nodes (conditional rule on / off, see /conf/node-cond-*.conf) and a regtest
 * bitcoind over RPC. Every decision the suite scores comes from the nodes: the Bridge parses, evaluates,
 * credits, refunds, marks processed and queues releases; this adapter only builds fixtures and reports
 * what it observes (receipts, balances, code, Bridge state). The parse operation uses the in-process
 * parser classes of the same build.
 *
 * Disclosed limits: activation is a rule switch per node (vetiver900 at 0 vs never), not a boundary
 * height; the event-schema baseline is read from git master; federation signing for mempool acceptance
 * goes through the Bridge's addSignature with the regtest federation keys.
 */
public class ConditionalPeginConformanceAdapterServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final NetworkParameters PARAMS = RegTestParams.get();
    private static final BridgeConstants BRIDGE_CONSTANTS = new BridgeRegTestConstants();
    private static final byte CHAIN_ID = 33;
    private static final BigInteger GAS_PRICE = BigInteger.ONE;
    private static final ActivationConfig.ForBlock ALL = ActivationConfigsForTest.all().forBlock(0);
    private static final ActivationConfig.ForBlock ALL_BUT_V3 =
        ActivationConfigsForTest.allBut(ConsensusRule.RSKIP_PEGIN_INSTRUCTIONS_V3).forBlock(0);

    private static final List<String> SENDER_KINDS = List.of(
        "p2pkh", "p2sh_p2wpkh", "p2sh_multisig", "p2sh_p2wsh_multisig", "p2wpkh", "p2wsh", "p2tr");
    private static final Set<String> DERIVABLE = Set.of("p2pkh", "p2sh_p2wpkh", "p2sh_multisig", "p2sh_p2wsh_multisig");

    private static final Map<String, CaseContext> PENDING = new HashMap<>();

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 47778;
        new Context(PARAMS);
        Env env = new Env();
        env.setupOnce();
        try (ServerSocket server = new ServerSocket(port)) {
            System.err.println("conditional-pegin adapter listening on " + port);
            while (true) {
                try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                    String line = in.readLine();
                    ObjectNode reply;
                    try {
                        reply = env.handle(MAPPER.readTree(line));
                    } catch (Exception e) {
                        reply = MAPPER.createObjectNode();
                        reply.put("unsupported", "adapter error: " + e);
                        e.printStackTrace();
                    }
                    out.write(MAPPER.writeValueAsString(reply));
                    out.write("\n");
                    out.flush();
                }
            }
        }
    }

    // =====================================================================================================
    // Environment: nodes, accounts, bitcoind
    // =====================================================================================================

    static final class Env {
        final RskNode on = new RskNode(env("RSK_ON_URL", "http://rskj-cond-on:4444"), "rskj-cond-on", "/conf/nodes/node-cond-on.conf");
        final RskNode off = new RskNode(env("RSK_OFF_URL", "http://rskj-cond-off:4444"), "rskj-cond-off", "/conf/nodes/node-cond-off.conf");
        final BitcoinCore core = new BitcoinCore(env("BTC_RPC_URL", "http://btc-regtest-cond:18443"), env("BTC_RPC_USER", "poc"), env("BTC_RPC_PASS", "poc"));
        final Account cow = new Account(HashUtil.keccak256("cow".getBytes(StandardCharsets.UTF_8)));
        final List<Account> federators = new ArrayList<>();

        static String env(String key, String def) {
            return System.getenv().getOrDefault(key, def);
        }

        void setupOnce() throws Exception {
            if (!cow.address.toHexString().equals("cd2a3d9f938e13cd947ec05abc7fe734df8dd826")) {
                throw new IllegalStateException("unexpected regtest funded account " + cow.address);
            }
            for (BtcECKey key : FederationTestUtils.REGTEST_FEDERATION_PRIVATE_KEYS) {
                federators.add(new Account(key.getPrivKeyBytes()));
            }
            core.ensureWallets();
            for (RskNode node : List.of(on, off)) {
                node.setup(this);
            }
        }

        ObjectNode handle(JsonNode request) throws Exception {
            String operation = request.get("operation").asText();
            return switch (operation) {
                case "parse" -> parse(request);
                case "event_schema" -> eventSchema(request);
                case "pegin" -> new CaseContext(this, request).run();
                case "resume" -> {
                    CaseContext ctx = PENDING.remove(request.get("resume_token").asText());
                    if (ctx == null) {
                        throw new IllegalStateException("unknown resume token");
                    }
                    ctx.restartPerformed = request.get("restart_performed").asBoolean();
                    yield ctx.resume();
                }
                default -> throw new IllegalArgumentException("unknown operation " + operation);
            };
        }

        RskNode node(boolean conditional) {
            return conditional ? on : off;
        }

        ObjectNode configuration(boolean conditionalRequested, RskNode node) throws IOException {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("v2_enabled", v2Enabled());
            c.put("conditional_enabled", node != null ? node.conditionalActive() : conditionalRequested);
            return c;
        }

        /** Probes the actual dispatcher with a version-2 payload rather than asserting it. */
        static boolean v2Enabled() {
            byte[] payload = Hex.decode("52534b5402" + "00".repeat(20));
            try {
                parseWithProvider(payload);
                return true;
            } catch (PeginInstructionsParseException e) {
                return true; // dispatched to a version-2 parser that rejected the layout
            } catch (PeginInstructionsException e) {
                return false; // "Invalid protocol version"
            }
        }

        // ------------------------------------------------------------------ parse

        static Optional<PeginInstructions> parseWithProvider(byte[] payload) throws PeginInstructionsException {
            BtcTransaction btcTx = new BtcTransaction(PARAMS);
            btcTx.addOutput(Coin.ZERO, new ScriptBuilder().op(ScriptOpCodes.OP_RETURN).data(payload).build());
            return new PeginInstructionsProvider().buildPeginInstructions(btcTx);
        }

        ObjectNode parse(JsonNode request) throws Exception {
            boolean conditional = request.get("conditional_enabled").asBoolean();
            byte[] payload = Hex.decode(request.get("payload_hex").asText());
            ObjectNode reply = MAPPER.createObjectNode();
            reply.set("configuration", configuration(conditional, null));
            try {
                // Activation gate exactly as PeginInformation applies it, then the real dispatcher/parser
                BtcTransaction btcTx = new BtcTransaction(PARAMS);
                btcTx.addOutput(Coin.ZERO, new ScriptBuilder().op(ScriptOpCodes.OP_RETURN).data(payload).build());
                PeginInformation info = new PeginInformation(new BtcLockSenderProvider(), new PeginInstructionsProvider(), conditional ? ALL : ALL_BUT_V3);
                info.parse(btcTx);
                PeginInstructions instructions = parseWithProvider(payload).orElseThrow(() -> new PeginInstructionsException("no RSKT output"));
                reply.put("ok", true);
                reply.put("destination_hex", Hex.toHexString(instructions.getRskDestinationAddress().getBytes()));
                Optional<Address> refund = Optional.empty();
                if (instructions instanceof PeginInstructionsVersion3 v3) {
                    reply.put("flags", v3.getFlags());
                    refund = v3.getBtcRefundAddress();
                } else if (instructions instanceof PeginInstructionsVersion1 v1) {
                    reply.putNull("flags");
                    refund = v1.getBtcRefundAddress();
                }
                if (refund.isPresent()) {
                    reply.put("refund_script_hex", Hex.toHexString(ScriptBuilder.createOutputScript(refund.get()).getProgram()));
                } else {
                    reply.putNull("refund_script_hex");
                }
                reply.putNull("error");
            } catch (PeginInstructionsParseException e) {
                failed(reply, "invalid_payload");
            } catch (PeginInstructionsException e) {
                failed(reply, e.getMessage() != null && e.getMessage().startsWith("Invalid protocol version") ? "invalid_version" : "invalid_payload");
            }
            return reply;
        }

        private static void failed(ObjectNode reply, String error) {
            reply.put("ok", false);
            reply.putNull("destination_hex");
            reply.putNull("flags");
            reply.putNull("refund_script_hex");
            reply.put("error", error);
        }

        // ------------------------------------------------------------------ event schema

        ObjectNode eventSchema(JsonNode request) throws Exception {
            ObjectNode reply = MAPPER.createObjectNode();
            reply.set("configuration", configuration(request.get("conditional_enabled").asBoolean(), null));
            ObjectNode current = reply.putObject("current");
            ObjectNode currentSignatures = current.putObject("signatures");
            for (BridgeEvents event : List.of(BridgeEvents.REJECTED_PEGIN, BridgeEvents.RELEASE_REQUESTED,
                BridgeEvents.UNREFUNDABLE_PEGIN, BridgeEvents.PEGIN_BTC, BridgeEvents.RELEASE_BTC)) {
                CallTransaction.Function f = event.getEvent();
                currentSignatures.put(f.name, f.formatSignature());
            }
            ObjectNode currentReasons = current.putObject("rejection_reasons");
            for (RejectedPeginReason r : RejectedPeginReason.values()) {
                currentReasons.put(r.name(), r.getValue());
            }
            // Baseline: the declarations at git master (the branch point), read from the mounted repository
            String baselineEnum = git("show", "master:rskj-core/src/main/java/co/rsk/peg/pegin/RejectedPeginReason.java");
            boolean eventsUnchanged = git("diff", "--quiet", "master", "--", "rskj-core/src/main/java/co/rsk/peg/BridgeEvents.java").isEmpty();
            ObjectNode baseline = reply.putObject("baseline");
            baseline.set("signatures", eventsUnchanged ? currentSignatures.deepCopy() : MAPPER.createObjectNode());
            ObjectNode baselineReasons = baseline.putObject("rejection_reasons");
            Matcher m = Pattern.compile("([A-Z0-9_]+)\\((\\d+)\\)").matcher(baselineEnum);
            while (m.find()) {
                baselineReasons.put(m.group(1), Integer.parseInt(m.group(2)));
            }
            reply.put("baseline_source", "git master: RejectedPeginReason.java; BridgeEvents.java unchanged=" + eventsUnchanged);
            return reply;
        }

        private static String git(String... args) throws Exception {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", "/work"));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0 && !args[0].equals("diff")) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
            }
            return code == 0 ? out : "changed:" + out;
        }
    }

    // =====================================================================================================
    // Accounts and transactions
    // =====================================================================================================

    static final class Account {
        final byte[] privateKey;
        final RskAddress address;

        Account(byte[] privateKey) {
            this.privateKey = privateKey;
            this.address = new RskAddress(ECKey.fromPrivate(privateKey).getAddress());
        }
    }

    // =====================================================================================================
    // RSK node client
    // =====================================================================================================

    static final class RskNode {
        final String url;
        final String container;
        final String confPath;
        final HttpClient http = HttpClient.newHttpClient();
        boolean ready;

        RskNode(String url, String container, String confPath) {
            this.url = url;
            this.container = container;
            this.confPath = confPath;
        }

        boolean conditionalActive() throws IOException {
            for (String line : Files.readAllLines(Path.of(confPath))) {
                if (line.trim().startsWith("blockchain.config.hardforkActivationHeights.vetiver900")) {
                    return Long.parseLong(line.split("=")[1].trim()) >= 0;
                }
            }
            throw new IllegalStateException("activation height not found in " + confPath);
        }

        JsonNode rpc(String method, Object... params) throws Exception {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("id", 1);
            body.put("method", method);
            body.set("params", MAPPER.valueToTree(params));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = MAPPER.readTree(res.body());
            if (node.hasNonNull("error")) {
                throw new IllegalStateException(container + " " + method + ": " + node.get("error"));
            }
            return node.get("result");
        }

        long blockNumber() throws Exception {
            return Long.decode(rpc("eth_blockNumber").asText());
        }

        void mine() throws Exception {
            long before = blockNumber();
            rpc("evm_mine");
            for (int i = 0; i < 100 && blockNumber() == before; i++) {
                Thread.sleep(50);
            }
            if (blockNumber() == before) {
                throw new IllegalStateException("evm_mine did not produce a block");
            }
        }

        BigInteger nonce(Account account) throws Exception {
            return new BigInteger(rpc("eth_getTransactionCount", "0x" + account.address.toHexString(), "pending").asText().substring(2), 16);
        }

        /** Signs and submits a transaction; returns its hash. Does not mine. */
        long blockGasLimit() throws Exception {
            return Long.decode(rpc("eth_getBlockByNumber", "latest", false).get("gasLimit").asText());
        }

        Keccak256 send(Account from, RskAddress to, byte[] data, BigInteger value, long requestedGasLimit) throws Exception {
            // The pool caps a transaction at the per-sublist gas limit (RSKIP144), which drifts with the block gas
            // limit; rather than mirror that arithmetic, resend at 90% of the limit the node itself reports.
            long gasLimit = Math.min(requestedGasLimit, blockGasLimit() * 9 / 10);
            BigInteger nonce = nonce(from);
            for (int attempt = 0; ; attempt++) {
                Transaction tx = Transaction.builder()
                    .nonce(nonce)
                    .gasPrice(GAS_PRICE)
                    .gasLimit(BigInteger.valueOf(gasLimit))
                    .destination(to == null ? RskAddress.nullAddress() : to)
                    .data(data == null ? new byte[0] : data)
                    .value(value)
                    .chainId(CHAIN_ID)
                    .build();
                tx.sign(from.privateKey);
                try {
                    String hash = rpc("eth_sendRawTransaction", "0x" + Hex.toHexString(tx.getEncoded())).asText();
                    return new Keccak256(Hex.decode(hash.substring(2)));
                } catch (IllegalStateException e) {
                    String message = e.getMessage() == null ? "" : e.getMessage();
                    Matcher m = Pattern.compile("higher than the block's gas limit of (\\d+)").matcher(message);
                    if (m.find() && attempt < 5) {
                        gasLimit = Long.parseLong(m.group(1)) * 9 / 10;
                        continue;
                    }
                    // rskj rate-limits each account with a gas quota that refills over time; wait it out rather than fail
                    if (message.contains("quota") && attempt < 30) {
                        Thread.sleep(2000);
                        continue;
                    }
                    throw e;
                }
            }
        }

        Keccak256 sendAndMine(Account from, RskAddress to, byte[] data, BigInteger value, long gasLimit) throws Exception {
            Keccak256 hash = send(from, to, data, value, gasLimit);
            mine();
            receipt(hash);
            return hash;
        }

        JsonNode receipt(Keccak256 hash) throws Exception {
            for (int i = 0; i < 100; i++) {
                JsonNode r = rpc("eth_getTransactionReceipt", "0x" + hash.toHexString());
                if (r != null && !r.isNull()) {
                    return r;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("no receipt for " + hash);
        }

        byte[] bridgeCall(CallTransaction.Function f, Object... args) throws Exception {
            ObjectNode call = MAPPER.createObjectNode();
            call.put("to", "0x" + PrecompiledContracts.BRIDGE_ADDR.toHexString());
            call.put("data", "0x" + Hex.toHexString(f.encode(args)));
            String out = rpc("eth_call", call, "latest").asText();
            return Hex.decode(out.substring(2));
        }

        Object[] bridgeCallDecoded(CallTransaction.Function f, Object... args) throws Exception {
            return f.decodeResult(bridgeCall(f, args));
        }

        Keccak256 bridgeTx(Account from, CallTransaction.Function f, long gasLimit, Object... args) throws Exception {
            return send(from, PrecompiledContracts.BRIDGE_ADDR, f.encode(args), BigInteger.ZERO, gasLimit);
        }

        void sendAndMineBridge(Account from, CallTransaction.Function f, long gasLimit, Object... args) throws Exception {
            Keccak256 hash = bridgeTx(from, f, gasLimit, args);
            mine();
            JsonNode r = receipt(hash);
            if (!"0x1".equals(r.get("status").asText())) {
                throw new IllegalStateException(f.name + " failed: " + r);
            }
        }

        BigInteger balance(RskAddress addr, long block) throws Exception {
            return new BigInteger(rpc("eth_getBalance", "0x" + addr.toHexString(), "0x" + Long.toHexString(block)).asText().substring(2), 16);
        }

        String code(RskAddress addr, long block) throws Exception {
            return rpc("eth_getCode", "0x" + addr.toHexString(), "0x" + Long.toHexString(block)).asText().substring(2);
        }

        long nonceAt(RskAddress addr, long block) throws Exception {
            return Long.decode(rpc("eth_getTransactionCount", "0x" + addr.toHexString(), "0x" + Long.toHexString(block)).asText());
        }

        int btcBestHeight() throws Exception {
            return ((BigInteger) bridgeCallDecoded(Bridge.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT)[0]).intValue();
        }

        Address federationAddress() throws Exception {
            return Address.fromBase58(PARAMS, (String) bridgeCallDecoded(Bridge.GET_FEDERATION_ADDRESS)[0]);
        }

        boolean processed(Sha256Hash txid) throws Exception {
            return (Boolean) bridgeCallDecoded(Bridge.IS_BTC_TX_HASH_ALREADY_PROCESSED, txid.toString())[0];
        }

        BridgeState bridgeState() throws Exception {
            byte[] data = (byte[]) bridgeCallDecoded(Bridge.GET_STATE_FOR_DEBUGGING)[0];
            return BridgeState.create(BRIDGE_CONSTANTS, data, ALL);
        }

        // ---- one-time setup: fund federators, sync headers, add a decoy federation UTXO

        void setup(Env env) throws Exception {
            for (Account fed : env.federators) {
                if (balance(fed.address, blockNumber()).compareTo(BigInteger.TEN.pow(19)) < 0) {
                    sendAndMine(env.cow, fed.address, null, BigInteger.TEN.pow(20), 30_000);
                }
            }
            syncHeaders(env);
            // Decoy: an unrelated legacy peg-in so the federation holds UTXOs the refund must not consume
            Deposit decoy = env.core.deposit("p2pkh", List.of(Coin.valueOf(10_000_000)), federationAddress(), null);
            env.core.mine(BRIDGE_CONSTANTS.getBtc2RskMinimumAcceptableConfirmations());
            syncHeaders(env);
            register(env, decoy, false);
            ready = true;
        }

        void syncHeaders(Env env) throws Exception {
            int have = btcBestHeight();
            int want = env.core.height();
            List<byte[]> batch = new ArrayList<>();
            for (int h = have + 1; h <= want; h++) {
                batch.add(env.core.header(h));
                if (batch.size() == 12 || h == want) {
                    Keccak256 hash = bridgeTx(env.federators.get(h % env.federators.size()), Bridge.RECEIVE_HEADERS, 2_500_000, (Object) batch.toArray(new byte[0][]));
                    mine();
                    JsonNode r = receipt(hash);
                    if (!"0x1".equals(r.get("status").asText())) {
                        throw new IllegalStateException("receiveHeaders failed: " + r);
                    }
                    batch.clear();
                }
            }
            if (btcBestHeight() != want) {
                throw new IllegalStateException("header sync incomplete: " + btcBestHeight() + " vs " + want);
            }
        }

        /** Registers the deposit (coinbase info first for witness transactions); returns the registration tx hash. Does not mine when {@code deferMine}. */
        Keccak256 register(Env env, Deposit deposit, boolean deferMine) throws Exception {
            if (deposit.tx.hasWitness()) {
                env.core.registerCoinbase(this, env, deposit.blockHash);
            }
            byte[] pmt = env.core.pmtFor(deposit);
            Keccak256 hash = bridgeTx(env.cow, Bridge.REGISTER_BTC_TRANSACTION, 2_500_000,
                deposit.tx.bitcoinSerialize(), deposit.height, pmt);
            if (!deferMine) {
                mine();
                receipt(hash);
            }
            return hash;
        }
    }

    // =====================================================================================================
    // Bitcoin Core client and fixtures
    // =====================================================================================================

    record Deposit(BtcTransaction tx, Sha256Hash blockHash, int height, List<Integer> federationVouts, byte[] payload) {}

    static final class BitcoinCore {
        private final String url;
        private final String auth;
        private final HttpClient http = HttpClient.newHttpClient();
        private final Map<String, List<BtcECKey>> senderKeys = new HashMap<>();
        private final Map<String, String> senderAddresses = new HashMap<>();
        private final Map<String, String> senderScripts = new HashMap<>();
        private String mineAddress;

        BitcoinCore(String url, String user, String password) {
            this.url = url;
            this.auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        }

        JsonNode rpc(String method, Object... params) throws Exception {
            return call(url, method, params);
        }

        JsonNode wallet(String name, String method, Object... params) throws Exception {
            return call(url + "/wallet/" + name, method, params);
        }

        private JsonNode call(String endpoint, String method, Object... params) throws Exception {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("jsonrpc", "1.0");
            body.put("id", "cond");
            body.put("method", method);
            body.set("params", MAPPER.valueToTree(params));
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = MAPPER.readTree(res.body());
            if (node.hasNonNull("error")) {
                throw new IllegalStateException("bitcoind " + method + ": " + node.get("error"));
            }
            return node.get("result");
        }

        int height() throws Exception {
            return rpc("getblockcount").asInt();
        }

        byte[] header(int h) throws Exception {
            return Hex.decode(rpc("getblockheader", rpc("getblockhash", h).asText(), false).asText());
        }

        void mine(int blocks) throws Exception {
            rpc("generatetoaddress", blocks, mineAddress);
        }

        private static String wif(BtcECKey key) {
            byte[] payload = new byte[34];
            payload[0] = (byte) 0xef; // regtest/testnet private key prefix
            System.arraycopy(key.getPrivKeyBytes(), 0, payload, 1, 32);
            payload[33] = 0x01; // compressed
            byte[] checksum = Arrays.copyOf(Sha256Hash.hashTwice(payload), 4);
            byte[] full = new byte[38];
            System.arraycopy(payload, 0, full, 0, 34);
            System.arraycopy(checksum, 0, full, 34, 4);
            return Base58.encode(full);
        }

        /** One descriptor wallet per sender kind, keys generated here and imported with private keys so Core signs genuinely. */
        void ensureWallets() throws Exception {
            Set<String> loaded = new HashSet<>();
            for (JsonNode w : rpc("listwallets")) {
                loaded.add(w.asText());
            }
            if (!loaded.contains("miner")) {
                try { rpc("createwallet", "miner"); } catch (IllegalStateException e) { rpc("loadwallet", "miner"); }
            }
            mineAddress = wallet("miner", "getnewaddress", "", "bech32m").asText();
            if (wallet("miner", "getbalance").asDouble() < 50) {
                rpc("generatetoaddress", 120, mineAddress);
            }
            for (String kind : SENDER_KINDS) {
                List<BtcECKey> keys = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    keys.add(BitcoinTestUtils.getBtcEcKeyFromSeed("cond-sender-" + kind + "-" + i));
                }
                senderKeys.put(kind, keys);
                String k = wif(keys.get(0));
                String multi = "multi(2," + wif(keys.get(0)) + "," + wif(keys.get(1)) + "," + wif(keys.get(2)) + ")";
                String descriptor = switch (kind) {
                    case "p2pkh" -> "pkh(" + k + ")";
                    case "p2sh_p2wpkh" -> "sh(wpkh(" + k + "))";
                    case "p2wpkh" -> "wpkh(" + k + ")";
                    case "p2tr" -> "tr(" + k + ")";
                    case "p2sh_multisig" -> "sh(" + multi + ")";
                    case "p2sh_p2wsh_multisig" -> "sh(wsh(" + multi + "))";
                    case "p2wsh" -> "wsh(" + multi + ")";
                    default -> throw new IllegalArgumentException(kind);
                };
                String walletName = "sender-" + kind;
                if (!loaded.contains(walletName)) {
                    try {
                        ObjectNode opts = MAPPER.createObjectNode();
                        opts.put("wallet_name", walletName);
                        opts.put("blank", true);
                        opts.put("descriptors", true);
                        rpc("createwallet", walletName, false, true);
                    } catch (IllegalStateException e) {
                        rpc("loadwallet", walletName);
                    }
                }
                // getdescriptorinfo normalises to public keys; its checksum is for the input string, so keep the private form
                String withChecksum = descriptor + "#" + rpc("getdescriptorinfo", descriptor).get("checksum").asText();
                ObjectNode imp = MAPPER.createObjectNode();
                imp.put("desc", withChecksum);
                imp.put("timestamp", "now");
                imp.put("active", false);
                JsonNode res = wallet(walletName, "importdescriptors", (Object) new Object[]{imp});
                if (!res.get(0).get("success").asBoolean()) {
                    throw new IllegalStateException("importdescriptors " + kind + ": " + res);
                }
                String address = rpc("deriveaddresses", withChecksum).get(0).asText();
                senderAddresses.put(kind, address);
                // Independently constructed fallback script: the funding output's scriptPubKey as Core derives it
                senderScripts.put(kind, wallet(walletName, "getaddressinfo", address).get("scriptPubKey").asText());
            }
        }

        String senderScript(String kind) {
            return DERIVABLE.contains(kind) ? senderScripts.get(kind) : null;
        }

        /**
         * Funds the sender wallet, builds the deposit (federation outputs first, then the instruction output,
         * then change), has Core sign it with the sender kind's keys, broadcasts and mines it.
         */
        Deposit deposit(String kind, List<Coin> federationOutputs, Address federationAddress, byte[] payload) throws Exception {
            return deposit(List.of(kind), federationOutputs, federationAddress, payload);
        }

        Deposit deposit(List<String> kinds, List<Coin> federationOutputs, Address federationAddress, byte[] payload) throws Exception {
            Coin total = federationOutputs.stream().reduce(Coin.ZERO, Coin::add);
            Coin perInput = total.add(Coin.CENT).divide(kinds.size()).add(Coin.CENT);
            // fund one UTXO per input from the miner wallet
            List<JsonNode> utxos = new ArrayList<>();
            for (String kind : kinds) {
                String fundTxid = wallet("miner", "sendtoaddress", senderAddresses.get(kind), perInput.toPlainString()).asText();
                mine(1);
                JsonNode found = null;
                for (JsonNode u : wallet("sender-" + kind, "listunspent", 1, 9_999_999)) {
                    if (u.get("txid").asText().equals(fundTxid)) {
                        found = u;
                    }
                }
                if (found == null) {
                    throw new IllegalStateException("funding utxo not found for " + kind);
                }
                utxos.add(found);
            }
            BtcTransaction tx = new BtcTransaction(PARAMS);
            Coin inputValue = Coin.ZERO;
            for (JsonNode u : utxos) {
                tx.addInput(Sha256Hash.wrap(u.get("txid").asText()), u.get("vout").asLong(), new Script(new byte[]{}));
                inputValue = inputValue.add(Coin.parseCoin(u.get("amount").asText()));
            }
            List<Integer> vouts = new ArrayList<>();
            for (Coin value : federationOutputs) {
                vouts.add(tx.getOutputs().size());
                tx.addOutput(value, federationAddress);
            }
            if (payload != null) {
                tx.addOutput(Coin.ZERO, new ScriptBuilder().op(ScriptOpCodes.OP_RETURN).data(payload).build());
            }
            Coin fee = Coin.valueOf(10_000);
            String change = wallet("miner", "getnewaddress", "", "bech32m").asText();
            tx.addOutput(inputValue.subtract(total).subtract(fee), new Script(Hex.decode(wallet("miner", "getaddressinfo", change).get("scriptPubKey").asText())));
            // Each sender wallet signs its own input
            String hex = Hex.toHexString(tx.bitcoinSerialize());
            for (String kind : new LinkedHashSet<>(kinds)) {
                JsonNode signed = wallet("sender-" + kind, "signrawtransactionwithwallet", hex);
                hex = signed.get("hex").asText();
                if (kind.equals(kinds.get(kinds.size() - 1)) && !signed.get("complete").asBoolean()) {
                    throw new IllegalStateException("Core could not fully sign deposit: " + signed);
                }
            }
            String txid = rpc("sendrawtransaction", hex).asText();
            mine(1);
            int height = height();
            Sha256Hash blockHash = Sha256Hash.wrap(rpc("getblockhash", height).asText());
            BtcTransaction mined = new BtcTransaction(PARAMS, Hex.decode(hex));
            if (!mined.getHash(false).toString().equals(txid)) {
                throw new IllegalStateException("txid mismatch " + mined.getHash(false) + " vs " + txid);
            }
            return new Deposit(mined, blockHash, height, vouts, payload);
        }

        /** PMT over txids (legacy tx) or wtxids (witness tx) for the deposit's block, marking only the deposit. */
        byte[] pmtFor(Deposit deposit) throws Exception {
            boolean witness = deposit.tx.hasWitness();
            List<Sha256Hash> leaves = blockLeaves(deposit.blockHash, witness);
            Sha256Hash target = witness ? deposit.tx.getHash(true) : deposit.tx.getHash(false);
            byte[] bits = new byte[(leaves.size() + 7) / 8];
            int index = leaves.indexOf(target);
            if (index < 0) {
                throw new IllegalStateException("deposit not in block " + deposit.blockHash);
            }
            bits[index / 8] |= (byte) (1 << (index % 8));
            return PartialMerkleTree.buildFromLeaves(PARAMS, bits, leaves).bitcoinSerialize();
        }

        List<Sha256Hash> blockLeaves(Sha256Hash blockHash, boolean witness) throws Exception {
            JsonNode block = rpc("getblock", blockHash.toString(), 2);
            List<Sha256Hash> leaves = new ArrayList<>();
            int i = 0;
            for (JsonNode t : block.get("tx")) {
                if (witness) {
                    leaves.add(i == 0 ? Sha256Hash.ZERO_HASH : Sha256Hash.wrap(t.get("hash").asText()));
                } else {
                    leaves.add(Sha256Hash.wrap(t.get("txid").asText()));
                }
                i++;
            }
            return leaves;
        }

        private static byte[] legacySerialize(BtcTransaction tx) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Utils.uint32ToByteStreamLE(tx.getVersion(), out);
            out.write(new VarInt(tx.getInputs().size()).encode());
            for (TransactionInput in : tx.getInputs()) {
                out.write(in.getOutpoint().bitcoinSerialize());
                byte[] script = in.getScriptBytes();
                out.write(new VarInt(script.length).encode());
                out.write(script);
                Utils.uint32ToByteStreamLE(in.getSequenceNumber(), out);
            }
            out.write(new VarInt(tx.getOutputs().size()).encode());
            for (TransactionOutput o : tx.getOutputs()) {
                Utils.int64ToByteStreamLE(o.getValue().getValue(), out);
                byte[] script = o.getScriptBytes();
                out.write(new VarInt(script.length).encode());
                out.write(script);
            }
            Utils.uint32ToByteStreamLE(tx.getLockTime(), out);
            return out.toByteArray();
        }

        /** Registers the block's coinbase witness commitment in the Bridge so a witness-serialised deposit can be proved. */
        void registerCoinbase(RskNode node, Env env, Sha256Hash blockHash) throws Exception {
            JsonNode block = rpc("getblock", blockHash.toString(), 2);
            BtcTransaction coinbase = new BtcTransaction(PARAMS, Hex.decode(block.get("tx").get(0).get("hex").asText()));
            byte[] reserved = coinbase.getWitness(0).getPush(0);
            List<Sha256Hash> wleaves = blockLeaves(blockHash, true);
            byte[] allBits = new byte[(wleaves.size() + 7) / 8];
            Arrays.fill(allBits, (byte) 0xff);
            Sha256Hash witnessRoot = PartialMerkleTree.buildFromLeaves(PARAMS, allBits, wleaves).getTxnHashAndMerkleRoot(new ArrayList<>());
            List<Sha256Hash> leaves = blockLeaves(blockHash, false);
            byte[] bits = new byte[(leaves.size() + 7) / 8];
            bits[0] |= 1;
            byte[] pmt = PartialMerkleTree.buildFromLeaves(PARAMS, bits, leaves).bitcoinSerialize();
            Keccak256 hash = node.bridgeTx(env.cow, Bridge.REGISTER_BTC_COINBASE_TRANSACTION, 2_000_000,
                legacySerialize(coinbase), blockHash.getBytes(), pmt, witnessRoot.getBytes(), reserved);
            node.mine();
            JsonNode r = node.receipt(hash);
            if (!"0x1".equals(r.get("status").asText())) {
                throw new IllegalStateException("registerBtcCoinbaseTransaction failed: " + r);
            }
        }
    }

    // =====================================================================================================
    // One peg-in case
    // =====================================================================================================

    static final class CaseContext {
        final Env env;
        final JsonNode request;
        final RskNode node;
        final boolean conditional;
        final String schedule;
        final String fixtureKind;
        final List<String> senders = new ArrayList<>();
        final List<Coin> outputValues = new ArrayList<>();
        final Coin minimum;
        final byte[] payloadTemplate;

        RskAddress destination;
        byte[] payload;
        Deposit deposit;
        Keccak256 registrationTx;
        JsonNode registrationReceipt;
        JsonNode deploymentReceipt;
        Long paymentHeight;
        Set<String> queueBefore;
        ObjectNode reply;
        ObjectNode firstObservation;
        boolean restartPerformed;

        CaseContext(Env env, JsonNode request) throws Exception {
            this.env = env;
            this.request = request;
            this.conditional = request.get("conditional_enabled").asBoolean();
            this.node = env.node(conditional);
            this.schedule = request.get("code_schedule").asText();
            this.fixtureKind = request.get("destination_fixture").asText();
            for (JsonNode k : request.get("sender_kinds")) {
                senders.add(k.asText());
            }
            this.minimum = BRIDGE_CONSTANTS.getMinimumPeginTxValue(ALL);
            for (JsonNode label : request.get("federation_outputs")) {
                outputValues.add(switch (label.asText()) {
                    case "minimum-1" -> minimum.subtract(Coin.SATOSHI);
                    case "minimum" -> minimum;
                    case "minimum+1" -> minimum.add(Coin.SATOSHI);
                    case "large" -> Coin.valueOf(Math.max(100_000_000L, 100L * minimum.getValue()));
                    default -> throw new IllegalArgumentException("unknown federation output " + label);
                });
            }
            this.payloadTemplate = Hex.decode(request.get("payload_template_hex").asText());
        }

        ObjectNode run() throws Exception {
            reply = MAPPER.createObjectNode();
            reply.set("configuration", env.configuration(conditional, node));
            reply.put("activation_wiring", "rule switch per node (vetiver900 height 0 vs -1); boundary-height wiring untested");
            drainReleaseQueue();

            // --- destination fixture and schedule-dependent deployment
            boolean needsCode = Set.of("stop", "revert", "invalid_opcode", "empty_runtime").contains(fixtureKind)
                || (!schedule.equals("static") && !fixtureKind.equals("precompile"));
            byte[] runtime = switch (fixtureKind) {
                case "stop" -> Hex.decode("00");
                case "revert" -> Hex.decode("60006000fd");
                case "invalid_opcode" -> Hex.decode("fe");
                case "empty_runtime" -> new byte[0];
                default -> Hex.decode("00"); // schedules deploy STOP runtime
            };
            BigInteger deployNonce = null;
            switch (fixtureKind) {
                case "empty" -> destination = schedule.equals("static") ? randomAddress() : null;
                case "funded_eoa" -> {
                    destination = randomAddress();
                    node.sendAndMine(env.cow, destination, null, BigInteger.TEN.pow(18), 30_000);
                }
                case "nonce_only_eoa" -> {
                    Account eoa = new Account(HashUtil.keccak256(("nonce-only-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8)));
                    destination = eoa.address;
                    node.sendAndMine(env.cow, destination, null, BigInteger.TEN.pow(17), 30_000);
                    node.sendAndMine(eoa, destination, null, BigInteger.ZERO, 30_000);
                }
                case "precompile" -> destination = PrecompiledContracts.ECRECOVER_ADDR;
                default -> destination = null; // deployed below
            }
            if (destination == null) {
                // Contract destination: predict the CREATE address for cow's next nonce so the payload can name it
                // before the deployment is actually sent (needed for same-block ordering and next-block deployment).
                deployNonce = node.nonce(env.cow);
                if (schedule.equals("same_block_after") || schedule.equals("next_block")) {
                    deployNonce = deployNonce.add(BigInteger.ONE); // the registration transaction goes first
                }
                destination = new RskAddress(HashUtil.calcNewAddr(env.cow.address.getBytes(), nonceBytes(deployNonce)));
            }
            byte[] initCode = initCodeFor(runtime);
            if (needsCode && (schedule.equals("static") || schedule.equals("before_payment"))) {
                deploymentReceipt = node.receipt(node.sendAndMine(env.cow, null, initCode, BigInteger.ZERO, 300_000));
                checkDeployed(deploymentReceipt);
                if (schedule.equals("before_payment")) {
                    node.mine(); // payment observation height must exceed the deployment block
                }
            }

            // --- payload and deposit
            payload = payloadTemplate.clone();
            System.arraycopy(destination.getBytes(), 0, payload, 5, 20);
            Address federationAddress = node.federationAddress();
            paymentHeight = node.blockNumber();
            deposit = env.core.deposit(senders, outputValues, federationAddress, payload);
            env.core.mine(BRIDGE_CONSTANTS.getBtc2RskMinimumAcceptableConfirmations());

            if (schedule.equals("before_registration")) {
                deploymentReceipt = node.receipt(node.sendAndMine(env.cow, null, initCode, BigInteger.ZERO, 300_000));
                checkDeployed(deploymentReceipt);
            }
            node.syncHeaders(env);

            // --- registration (with same-block deployment ordering where requested)
            queueBefore = queueTxids();
            BigInteger balanceBefore = node.balance(destination, node.blockNumber());
            Keccak256 deployHash = null;
            if (schedule.equals("same_block_before")) {
                deployHash = node.send(env.cow, null, initCode, BigInteger.ZERO, 300_000);
                registrationTx = node.register(env, deposit, true);
            } else if (schedule.equals("same_block_after")) {
                registrationTx = node.register(env, deposit, true);
                deployHash = node.send(env.cow, null, initCode, BigInteger.ZERO, 300_000);
            } else {
                registrationTx = node.register(env, deposit, true);
            }
            node.mine();
            registrationReceipt = node.receipt(registrationTx);
            if (deployHash != null) {
                deploymentReceipt = node.receipt(deployHash);
                checkDeployed(deploymentReceipt);
                if (!deploymentReceipt.get("blockNumber").asText().equals(registrationReceipt.get("blockNumber").asText())) {
                    throw new IllegalStateException("same-block schedule split across blocks");
                }
            }
            if (schedule.equals("next_block")) {
                deploymentReceipt = node.receipt(node.sendAndMine(env.cow, null, initCode, BigInteger.ZERO, 300_000));
                checkDeployed(deploymentReceipt);
            }
            if (deploymentReceipt != null && !new RskAddress(deploymentReceipt.get("contractAddress").asText().substring(2)).equals(destination)) {
                throw new IllegalStateException("deployed at " + deploymentReceipt.get("contractAddress") + " but payload names " + destination);
            }

            // --- observations
            firstObservation = observe(registrationReceipt, balanceBefore, queueBefore, true);
            ObjectNode fixture = reply.putObject("fixture");
            fixture.put("destination_hex", destination.toHexString());
            fixture.put("payload_hex", Hex.toHexString(payload));
            fixture.put("deposit_tx_hex", Hex.toHexString(deposit.tx.bitcoinSerialize()));
            fixture.put("deposit_txid", deposit.tx.getHash(false).toString());
            fixture.put("minimum_pegin_sats", minimum.getValue());
            ArrayNode vouts = fixture.putArray("federation_vouts");
            deposit.federationVouts.forEach(vouts::add);
            fixture.putArray("live_federation_scripts_hex").add(Hex.toHexString(ScriptBuilder.createOutputScript(federationAddress).getProgram()));
            ArrayNode senderScripts = fixture.putArray("sender_scripts_hex");
            for (String kind : senders) {
                String s = env.core.senderScript(kind);
                if (s == null) senderScripts.addNull(); else senderScripts.add(s);
            }
            if (deploymentReceipt != null) {
                fixture.set("deployment_position", position(deploymentReceipt));
            }
            if (schedule.equals("before_payment") || schedule.equals("before_registration")) {
                fixture.put("payment_observation_rootstock_height", paymentHeight);
            }
            ArrayNode registrations = reply.putArray("registrations");
            registrations.add(firstObservation);

            if (request.get("check_mempool_acceptance").asBoolean()) {
                mempoolAcceptance();
            }

            if (request.get("replay").asBoolean()) {
                if (request.get("restart_before_replay").asBoolean()) {
                    String token = UUID.randomUUID().toString();
                    PENDING.put(token, this);
                    ObjectNode pause = MAPPER.createObjectNode();
                    pause.put("needs_restart", node.container);
                    pause.put("resume_token", token);
                    return pause;
                }
                restartPerformed = false;
                return replay();
            }
            return reply;
        }

        ObjectNode resume() throws Exception {
            if (node.blockNumber() < Long.decode(registrationReceipt.get("blockNumber").asText())) {
                throw new IllegalStateException("node lost committed state across restart");
            }
            return replay();
        }

        private ObjectNode replay() throws Exception {
            Set<String> before = queueTxids();
            BigInteger balanceBefore = node.balance(destination, node.blockNumber());
            Keccak256 second = node.register(env, deposit, true);
            node.mine();
            JsonNode receipt = node.receipt(second);
            ObjectNode repeated = observe(receipt, balanceBefore, before, false);
            ((ArrayNode) reply.get("registrations")).add(repeated);
            reply.put("restart_performed", restartPerformed);
            ArrayNode pending = reply.putArray("pending_release_txids_after_replay");
            for (PegoutsWaitingForConfirmations.Entry e : node.bridgeState().getPegoutsWaitingForConfirmations().getEntries()) {
                pending.add(e.getBtcTransaction().getHash().toString());
            }
            return reply;
        }

        /** Confirmations, updateCollections, federation signatures through the Bridge, then Core's verdict. */
        private void mempoolAcceptance() throws Exception {
            for (int i = 0; i < BRIDGE_CONSTANTS.getRsk2BtcMinimumAcceptableConfirmations() + 1; i++) {
                node.mine();
            }
            node.sendAndMineBridge(env.federators.get(0), Bridge.UPDATE_COLLECTIONS, 1_000_000);
            byte[] stateBytes = (byte[]) node.bridgeCallDecoded(Bridge.GET_STATE_FOR_BTC_RELEASE_CLIENT)[0];
            StateForFederator state = new StateForFederator(stateBytes, PARAMS);
            BtcTransaction toSign = state.getRskTxsWaitingForSignatures().get(registrationTx);
            if (toSign == null) {
                throw new IllegalStateException("release not waiting for signatures: " + state.getRskTxsWaitingForSignatures().keySet());
            }
            String signedHex = null;
            for (int i = 0; i < 2 && signedHex == null; i++) {
                BtcECKey key = FederationTestUtils.REGTEST_FEDERATION_PRIVATE_KEYS.get(i);
                List<Sha256Hash> sigHashes = BitcoinTestUtils.generateTransactionInputsSigHashes(toSign);
                List<byte[]> sigs = BitcoinTestUtils.generateSignerEncodedSignatures(key, sigHashes);
                Keccak256 h = node.bridgeTx(env.federators.get(i), Bridge.ADD_SIGNATURE, 2_000_000,
                    key.getPubKey(), (Object) sigs.toArray(new byte[0][]), registrationTx.getBytes());
                node.mine();
                JsonNode r = node.receipt(h);
                if (!"0x1".equals(r.get("status").asText())) {
                    throw new IllegalStateException("addSignature failed: " + r);
                }
                for (JsonNode log : r.get("logs")) {
                    if (topic0(log).equals(Hex.toHexString(BridgeEvents.RELEASE_BTC.getEvent().encodeSignatureLong()))) {
                        Object[] data = BridgeEvents.RELEASE_BTC.getEvent().decodeEventData(Hex.decode(log.get("data").asText().substring(2)));
                        signedHex = Hex.toHexString((byte[]) data[0]);
                    }
                }
            }
            if (signedHex == null) {
                throw new IllegalStateException("Bridge did not emit release_btc after signatures");
            }
            BtcTransaction signed = new BtcTransaction(PARAMS, Hex.decode(signedHex));
            ObjectNode release = (ObjectNode) firstObservation.get("releases").get(0);
            release.put("signed_tx_hex", signedHex);
            release.put("signed_txid", signed.getHash().toString());
            ObjectNode bitcoinCore = reply.putObject("bitcoin_core");
            JsonNode net = env.core.rpc("getnetworkinfo");
            bitcoinCore.put("version", net.get("subversion").asText() + " (" + net.get("version").asLong() + ")");
            bitcoinCore.put("acceptnonstdtxn", Boolean.parseBoolean(Env.env("BTC_ACCEPT_NONSTD_TXN", "false")));
            ArrayNode acceptance = bitcoinCore.putArray("testmempoolaccept");
            for (JsonNode verdict : env.core.rpc("testmempoolaccept", (Object) new Object[]{signedHex})) {
                ObjectNode a = acceptance.addObject();
                a.put("txid", verdict.get("txid").asText());
                a.put("allowed", verdict.get("allowed").asBoolean());
                if (verdict.has("reject-reason")) a.put("reject_reason", verdict.get("reject-reason").asText());
                if (verdict.has("fees")) a.set("fees", verdict.get("fees"));
            }
        }

        // ---- observation of one registration attempt

        private ObjectNode observe(JsonNode receipt, BigInteger balanceBefore, Set<String> queueBefore, boolean first) throws Exception {
            ObjectNode o = MAPPER.createObjectNode();
            long block = Long.decode(receipt.get("blockNumber").asText());
            o.put("status", receipt.get("status").asText());
            o.put("processed", node.processed(deposit.tx.getHash(false)));
            o.set("position", position(receipt));

            ArrayNode events = o.putArray("events");
            boolean rejected = false, unrefundable = false, released = false;
            for (JsonNode log : receipt.get("logs")) {
                ObjectNode e = decodeEvent(log);
                if (e != null) {
                    events.add(e);
                    switch (e.get("name").asText()) {
                        case "rejected_pegin" -> rejected = true;
                        case "unrefundable_pegin" -> unrefundable = true;
                        case "release_requested" -> released = true;
                        default -> { }
                    }
                }
            }

            ArrayNode credits = o.putArray("credits");
            BigInteger delta = node.balance(destination, block).subtract(node.balance(destination, block - 1));
            if (delta.signum() > 0) {
                ObjectNode c = credits.addObject();
                c.put("destination_hex", destination.toHexString());
                c.put("amount_wei", delta);
            }

            ArrayNode releases = o.putArray("releases");
            Set<String> newQueue = queueTxids();
            for (PegoutsWaitingForConfirmations.Entry e : node.bridgeState().getPegoutsWaitingForConfirmations().getEntries()) {
                BtcTransaction release = e.getBtcTransaction();
                if (queueBefore.contains(release.getHash().toString())) {
                    continue;
                }
                ObjectNode r = releases.addObject();
                r.put("queue", "pegoutsWaitingForConfirmations");
                r.put("unsigned_tx_hex", Hex.toHexString(release.bitcoinSerialize()));
                r.put("txid", release.getHash().toString());
                long inputValue = 0;
                for (TransactionInput in : release.getInputs()) {
                    TransactionOutPoint op = in.getOutpoint();
                    if (!op.getHash().equals(deposit.tx.getHash(false))) {
                        throw new IllegalStateException("release spends an unexpected prevout " + op);
                    }
                    inputValue += deposit.tx.getOutput(op.getIndex()).getValue().getValue();
                }
                long outputValue = release.getOutputs().stream().mapToLong(x -> x.getValue().getValue()).sum();
                r.put("input_value_sats", inputValue);
                r.put("fee_sats", inputValue - outputValue);
                r.put("creation_rsk_tx_hash", e.getPegoutCreationRskTxHash() == null ? null : e.getPegoutCreationRskTxHash().toHexString());
            }

            if (first) {
                ObjectNode state = o.putObject("destination_state");
                // Execution-time pre-state: the state before this transaction ran. For a same-block deployment
                // ordered before the registration, code/nonce come from the post-block state (the credit changes
                // neither); for every other schedule the parent block is the pre-state.
                boolean deployedEarlierInSameBlock = schedule.equals("same_block_before");
                long codeBlock = deployedEarlierInSameBlock ? block : block - 1;
                state.put("code_hex", node.code(destination, codeBlock));
                state.put("is_precompile", PrecompiledContracts.GENESIS_ADDRESSES.contains(destination));
                state.put("balance_wei", node.balance(destination, block - 1));
                state.put("nonce", node.nonceAt(destination, codeBlock));
                state.put("observed_at_block", codeBlock);
                o.put("destination_call_count", destinationExecutions());
                o.setAll(firstObservationExtras);
                o.put("outcome", credits.size() > 0 ? "registered" : (released ? "refunded" : (unrefundable ? "unrefundable" : "ignored")));
            }
            return o;
        }

        /** Subtraces to the destination that executed code (gas offered or used). The Bridge's credit is a plain value transfer (gas 0). */
        private int destinationExecutions() throws Exception {
            JsonNode traces = node.rpc("trace_transaction", "0x" + registrationTx.toHexString());
            int executions = 0;
            int transfers = 0;
            if (traces != null && traces.isArray()) {
                for (JsonNode t : traces) {
                    JsonNode action = t.get("action");
                    if (action == null || action.get("to") == null) continue;
                    if (!action.get("to").asText().replace("0x", "").equalsIgnoreCase(destination.toHexString())) continue;
                    // rskj reports the Bridge's value transfer as a CALL subtrace with gas 0 and gasUsed 0 (and the
                    // parent's input copied in); an actual execution of the destination offers or consumes gas.
                    long gas = action.has("gas") ? Long.decode(action.get("gas").asText()) : 0;
                    long gasUsed = t.has("result") && t.get("result") != null && !t.get("result").isNull() && t.get("result").has("gasUsed")
                        ? Long.decode(t.get("result").get("gasUsed").asText()) : 0;
                    if (gas > 0 || gasUsed > 0) executions++; else transfers++;
                }
            }
            firstObservationExtras.put("destination_transfer_count", transfers);
            return executions;
        }

        private final ObjectNode firstObservationExtras = MAPPER.createObjectNode();

        private Set<String> queueTxids() throws Exception {
            Set<String> ids = new HashSet<>();
            for (PegoutsWaitingForConfirmations.Entry e : node.bridgeState().getPegoutsWaitingForConfirmations().getEntries()) {
                ids.add(e.getBtcTransaction().getHash().toString());
            }
            return ids;
        }

        /** Moves earlier cases' releases out of the confirmations queue so this case's queue observations are its own. */
        private void drainReleaseQueue() throws Exception {
            if (queueTxids().isEmpty()) {
                return;
            }
            for (int i = 0; i < BRIDGE_CONSTANTS.getRsk2BtcMinimumAcceptableConfirmations() + 1; i++) {
                node.mine();
            }
            node.sendAndMineBridge(env.federators.get(0), Bridge.UPDATE_COLLECTIONS, 1_000_000);
            if (!queueTxids().isEmpty()) {
                throw new IllegalStateException("could not drain pegoutsWaitingForConfirmations");
            }
        }

        private static ObjectNode position(JsonNode receipt) {
            ObjectNode p = MAPPER.createObjectNode();
            p.put("block", Long.decode(receipt.get("blockNumber").asText()));
            p.put("transaction_index", Long.decode(receipt.get("transactionIndex").asText()));
            return p;
        }

        private static void checkDeployed(JsonNode receipt) {
            if (!"0x1".equals(receipt.get("status").asText()) || receipt.get("contractAddress").isNull()) {
                throw new IllegalStateException("deployment failed: " + receipt);
            }
        }

        private static RskAddress randomAddress() {
            return new RskAddress(Arrays.copyOfRange(HashUtil.keccak256(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)), 12, 32));
        }

        private static byte[] nonceBytes(BigInteger nonce) {
            return nonce.signum() == 0 ? new byte[0] : org.bouncycastle.util.BigIntegers.asUnsignedByteArray(nonce);
        }

        /** Init code that returns {@code runtime} as the contract code (zero-length runtime is a successful creation with no code). */
        private static byte[] initCodeFor(byte[] runtime) {
            // PUSH1 len PUSH1 0x0c PUSH1 0 CODECOPY PUSH1 len PUSH1 0 RETURN <runtime>
            byte[] head = Hex.decode("60" + String.format("%02x", runtime.length) + "600c60003960" + String.format("%02x", runtime.length) + "6000f3");
            byte[] code = new byte[head.length + runtime.length];
            System.arraycopy(head, 0, code, 0, head.length);
            System.arraycopy(runtime, 0, code, head.length, runtime.length);
            return code;
        }

        private static String topic0(JsonNode log) {
            return log.get("topics").get(0).asText().substring(2);
        }

        private static ObjectNode decodeEvent(JsonNode log) {
            for (BridgeEvents candidate : List.of(BridgeEvents.PEGIN_BTC, BridgeEvents.REJECTED_PEGIN,
                BridgeEvents.UNREFUNDABLE_PEGIN, BridgeEvents.RELEASE_REQUESTED, BridgeEvents.LOCK_BTC)) {
                CallTransaction.Function event = candidate.getEvent();
                if (!topic0(log).equals(Hex.toHexString(event.encodeSignatureLong()))) continue;
                ObjectNode node = MAPPER.createObjectNode();
                node.put("name", event.name);
                Object[] data = event.decodeEventData(Hex.decode(log.get("data").asText().substring(2)));
                switch (candidate) {
                    case REJECTED_PEGIN -> node.put("reason", reasonName(RejectedPeginReason.values(), data[0]));
                    case UNREFUNDABLE_PEGIN -> node.put("reason", reasonName(NonRefundablePeginReason.values(), data[0]));
                    case RELEASE_REQUESTED -> {
                        node.put("btc_txid", log.get("topics").get(2).asText().substring(2));
                        node.put("amount_sats", ((BigInteger) data[0]).longValueExact());
                    }
                    case PEGIN_BTC -> {
                        node.put("amount_sats", ((BigInteger) data[0]).longValueExact());
                        node.put("protocol_version", ((BigInteger) data[1]).intValueExact());
                    }
                    default -> { }
                }
                return node;
            }
            return null;
        }

        private static String reasonName(Enum<?>[] values, Object decoded) {
            int code = ((BigInteger) decoded).intValueExact();
            for (Enum<?> v : values) {
                int value = v instanceof RejectedPeginReason r ? r.getValue() : ((NonRefundablePeginReason) v).getValue();
                if (value == code) return v.name();
            }
            return "UNKNOWN_" + code;
        }
    }
}
