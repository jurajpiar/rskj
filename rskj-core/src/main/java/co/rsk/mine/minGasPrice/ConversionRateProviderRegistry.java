package co.rsk.mine.minGasPrice;

import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class ConversionRateProviderRegistry {
    private static Map<String, Class<? extends ConversionRateProvider>> typeToProviderMap = new HashMap<>();

    public static Map<String, Class<? extends ConversionRateProvider>> registerProviderClass(String type, Class<? extends ConversionRateProvider> providerClass) {
        typeToProviderMap.put(type, providerClass);

        return typeToProviderMap;
    }

    public static Map<String, Class<? extends ConversionRateProvider>> getAvailableProviders() {
        return typeToProviderMap;
    }
}
