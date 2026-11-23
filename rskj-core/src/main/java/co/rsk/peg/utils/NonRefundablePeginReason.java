package co.rsk.peg.utils;

public enum NonRefundablePeginReason {
    LEGACY_PEGIN_UNDETERMINED_SENDER(1),
    PEGIN_V1_REFUND_ADDRESS_NOT_SET(2),
    INVALID_AMOUNT(3),
    OUTPUTS_SENT_TO_DIFFERENT_TYPES_OF_FEDS(4),
    OUTPUTS_SENT_TO_DIFFERENT_FEDS(5), // peg-in instructions v3: REQUIRE_CODE, no code, and the deposit pays more than one live federation
    REFUND_RELEASE_NOT_BUILT(6); // peg-in instructions v3: REQUIRE_CODE, no code, and the release builder could not make the refund

    private final int value;

    NonRefundablePeginReason(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
