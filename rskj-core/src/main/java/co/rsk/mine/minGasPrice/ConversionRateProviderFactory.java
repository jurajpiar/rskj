package co.rsk.mine.minGasPrice;

import co.rsk.config.StableMinGasPriceSourceConfig;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ConversionRateProviderFactory {
    private static final Logger logger = LoggerFactory.getLogger(ConversionRateProviderFactory.class);

    public enum Co {
        HTTP_GET("HTTP_GET", HttpGetProvider.class),
        ETH_CALL("ETH_CALL", EthCallProvider.class);

        private final String type;
        private final Class<? extends ConversionRateProvider> providerClass;

        Co(String type, Class<? extends ConversionRateProvider> providerClass) {
            this.type = type;
            this.providerClass = providerClass;
        }

        public static Class<? extends ConversionRateProvider> get(String type) {
            return Arrays.stream(Co.values())
                    .filter(co -> co.type.equalsIgnoreCase(type))
                    .findFirst()
                    .map(co -> co.providerClass)
                    .orElse(null);
        }
    }

    public static @Nonnull List<ConversionRateProvider> getProvidersFromSourceConfig(ConfigList minerStableGasPriceSources) {
        return minerStableGasPriceSources
                .stream()
                .map(source -> createProvider((ConfigObject) source))
                .collect(Collectors.toList());
    }

    public static ConversionRateProvider createProvider(ConfigObject sourceConfigObject) {
        StableMinGasPriceSourceConfig sourceConfig = new StableMinGasPriceSourceConfig(
                sourceConfigObject
        );

        String type = sourceConfig.sourceType();
        if (type == null) {
            throw new IllegalArgumentException("Missing 'type' for a source in miner stableGasPrice");
        }
        Class<? extends ConversionRateProvider> providerClass = Co.get(type);
        if (providerClass == null) {
            logger.error("Unknown 'type' in miner stableGasPrice providers: {}", type);

            return null;
        }

        return createProvider(providerClass, sourceConfig);
    }

    private static ConversionRateProvider createProvider(Class<? extends ConversionRateProvider> providerClass, StableMinGasPriceSourceConfig sourceConfig) {
        try {
            return providerClass
                    .getConstructor(StableMinGasPriceSourceConfig.class)
                    .newInstance(sourceConfig);
        } catch (Exception e) {
            logger.error("Error creating provider", e);

            return null;
        }
    }
}
