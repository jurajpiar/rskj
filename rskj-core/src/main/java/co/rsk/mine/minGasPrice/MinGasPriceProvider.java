package co.rsk.mine.minGasPrice;

import co.rsk.core.Coin;

import java.util.List;

public class MinGasPriceProvider {
    private final boolean enabled;
    private final long minFixedGasPriceTarget;
    private final long minStableGasPrice;
    private final int refreshRate;
    private final List<ConversionRateProvider> providers;

    public MinGasPriceProvider(boolean isStableMinGasPrice, long minFixedGasPriceTarget, long minStableGasPrice, int refreshRate, List<ConversionRateProvider> providers) {
        this.enabled = isStableMinGasPrice;
        this.minFixedGasPriceTarget = minFixedGasPriceTarget;
        this.minStableGasPrice = minStableGasPrice;
        this.refreshRate = refreshRate;
        this.providers = providers;
    }

    public MinGasPriceProvider(long minFixedGasPriceTarget) {
        this(false, minFixedGasPriceTarget, 0, 0, null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getMinStableGasPrice() {
        return minStableGasPrice;
    }

    public int getRefreshRate() {
        return refreshRate;
    }

    public List<ConversionRateProvider> getProviders() {
        return providers;
    }

    public long getMinFixedGasPriceTarget() {
        return minFixedGasPriceTarget;
    }

    public long getMinGasPrice() {
        long currentPrice = findFirstNonZeroRate();

        return currentPrice > 0 ? currentPrice : minFixedGasPriceTarget;
    }

    public Coin getMinGasPriceAsCoin() {
        return Coin.valueOf(getMinGasPrice());
    }

    private long findFirstNonZeroRate() {
        for (ConversionRateProvider provider : providers) {
            long gasPrice = provider.getGasPrice();
            if (gasPrice > 0) {

                return gasPrice; // TODO: Here we probably want to do some math to get the minimum gas price
            }
        }

        return 0;
    }
}
