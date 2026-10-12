package co.rsk.peg.pegin;

public enum RejectedPeginReason {
    PEGIN_CAP_SURPASSED(1),
    LEGACY_PEGIN_MULTISIG_SENDER(2),
    LEGACY_PEGIN_UNDETERMINED_SENDER(3),
    PEGIN_V1_INVALID_PAYLOAD(4),
    INVALID_AMOUNT(5),
    PEGIN_DESTINATION_HAS_NO_CODE(6) // PoC (peg-in instructions v3): REQUIRE_CODE set and the destination holds no code at registration
    ;

    private final int value;

    RejectedPeginReason(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
