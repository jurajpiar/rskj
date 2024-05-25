package co.rsk.mine.minGasPrice;

import co.rsk.rpc.modules.eth.EthModule;

import java.util.List;

public class EthCallProvider extends ConversionRateProvider {
    private final String address;
    private final String method;
    private final List<String> params;
//    private final EthModule ethModule;

    public EthCallProvider(
            String address,
            String method,
            List<String> params,
//            EthModule ethModule
    ) {
        super("ETH_CALL");
        this.address = address;
        this.method = method;
        this.params = params;
//        this.ethModule = ethModule;
    }

    public String getAddress() {
        return address;
    }

    public String getMethod() {
        return method;
    }

    public List<String> getParams() {
        return params;
    }

    public EthModule getEthModule() {
        return ethModule;
    }

    @Override
    public long getGasPrice() {
        return 0;
    }
}
