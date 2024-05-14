This seems unnecessarily complicated. Why should a strategy know it's type? Imo there is no need for a factory and all those different abstractions. Also there is no need to change the MinimumGasPriceCalculator. I'd simplify it to the following, given that all the information needed is known on boot-up:

```mermaid
classDiagram
direction RL

class MinimumGasPriceCalculator {
    Unchanged - only to illustrate use
    +construcor(long)
}
class BlockToMineBuilder {
    Unchanged - only to illustrate use
    +constuctor(..., MinimumGasPriceCalculator)
}
RskContext --* BlockToMineBuilder
BlockToMineBuilder --o MinimumGasPriceCalculator
RskContext --* MinimumGasPriceCalculator

class RskSystemProperties {
    +getMinerMinGasPriceConfig(): MinGasPriceConfig
}

class RskContext {
    -getMiningConfig(): MiningConfig
    +getBlockToMineBuilder(): BlockToMineBuilder
}

class MinGasPriceConfig {
    +providerType: "onchain" | "http" | "default"
    +url: URL
    +minGasPriceTarget: long
    +otherStuffIfAny
    +createMinGasPriceProvider(MinGasPriceConfig): MinGasPriceProvider
}

class MiningConfig {
    -minGasPriceProvider: MinGasPriceProvider
    +constructor(MinGasPriceConfig)
    +getMinGasPriceTarget(): long
}

class MinGasPriceProvider {
    <<<Interface>>>
    +getMinGasPrice()
}

class DefaultMinGasPriceProvider {
    +constructor()
}

class OffChainMinGasPriceProvider {
    +constructor(OffChainMinGasPriceProviderConfig)
}

class OnChainMinGasPriceProvider {
    +constructor(OnChainMinGasPriceProviderConfig)
}


MinGasPriceConfig *-- RskSystemProperties
RskSystemProperties *-- RskContext

MinGasPriceProvider *-- MinGasPriceConfig
MinGasPriceConfig <-- MiningConfig
MinGasPriceProvider o-- MiningConfig
MiningConfig *-- RskContext


MinGasPriceProvider <|-- DefaultMinGasPriceProvider
MinGasPriceProvider <|-- OffChainMinGasPriceProvider
MinGasPriceProvider <|-- OnChainMinGasPriceProvider
```
