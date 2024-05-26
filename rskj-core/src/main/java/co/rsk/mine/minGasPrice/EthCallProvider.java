package co.rsk.mine.minGasPrice;

import co.rsk.config.StableMinGasPriceSourceConfig;

import java.util.List;

public class EthCallProvider extends ConversionRateProvider {
    private final String address;
    private final String method;
    private final List<String> params;

    public EthCallProvider(StableMinGasPriceSourceConfig sourceConfig) {
        this(
                sourceConfig.sourceContract(),
                sourceConfig.sourceContractMethod(),
                sourceConfig.sourceContractMethodParams()
        );
    }

    public EthCallProvider(
            String address,
            String method,
            List<String> params
    ) {
        super("ETH_CALL");
        this.address = address;
        this.method = method;
        this.params = params;
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


    @Override
    public long getGasPrice() {
        return 0;
    }
}
