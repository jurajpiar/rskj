package co.rsk.mine.minGasPrice;

import co.rsk.bitcoinj.core.Address;
import co.rsk.core.RskAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import co.rsk.rpc.modules.eth.EthModule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EthCallProviderTest {
    private EthModule ethModuleMock;

    private String oracle_address = RskAddress.nullAddress().toHexString();
    private String oracle_method = "getPrice()(uint256)";
    private String[] oracle_params = new String[] { "USD-RBTC" };



    @BeforeEach
    public void beforeEach() {
        ethModuleMock = mock(EthModule.class);
//        when(ethModuleMock.call(oracle_method, )).thenReturn(null);
    }

    @AfterEach
    public void afterEach() {
        ethModuleMock = null;
    }

    @Test
    public void getGasPrice() {
        EthCallProvider ethCallProvider = new EthCallProvider(oracle_address, oracle_method, Arrays.asList(oracle_params), ethModuleMock);
        assert ethCallProvider.getGasPrice() == 0;
    }

}
