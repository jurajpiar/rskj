package co.rsk.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;

import java.util.List;

public class StableMinGasPriceSourceConfig
{
    Config sourceConfig;

    public StableMinGasPriceSourceConfig(ConfigObject sourceConfig) {
        this.sourceConfig = sourceConfig.toConfig();
    }

    public String sourceType() {
        return sourceConfig.getString("type");
    }

    public String sourceParamsUrl() {
        return sourceConfig.getString("params.url");
    }

    public String sourceParamsApiKey() {
        return sourceConfig.getString("params.apiKey");
    }

    public String sourceParamsJsonPath() {
        return sourceConfig.getString("params.jsonPath");
    }

    public int sourceParamsTimeout() {
        return sourceConfig.getInt("params.timeout");
    }

    public String sourceParamsContract() {
        return sourceConfig.getString("params.contract");
    }

    public String sourceParamsContractMethod() {
        return sourceConfig.getString("params.contractMethod");
    }

    public List<String> sourceParamsContractMethodParams() {
        return sourceConfig.getStringList("params.contractMethodParams");
    }
}
