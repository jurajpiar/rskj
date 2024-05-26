package co.rsk.mine.minGasPrice;

public abstract class ConversionRateProvider {
    private final String type;

    public ConversionRateProvider(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public abstract long getGasPrice();

}
