package co.rsk.mine.minGasPrice;

import org.junit.jupiter.api.Test;

public class EthCallProviderTest {

    @Test
    public void getGasPriceTest() {
        EthCallProvider ethCallProvider = new EthCallProvider("address", "method", null, null);
        assert ethCallProvider.getGasPrice() == 0;
    }
}
