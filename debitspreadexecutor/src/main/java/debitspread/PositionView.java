package debitspread;

public class PositionView {

    private String symbol;
    private int quantity;
    private boolean isLong;
    private double entryPrice;
    private double pnl;
    private double unrealised;
    private double realised;
    private double exposure;

    // Optional advanced
    private double dayEntryPrice;

    // Getters & Setters

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public boolean isLong() { return isLong; }
    public void setLong(boolean aLong) { isLong = aLong; }

    public double getEntryPrice() { return entryPrice; }
    public void setEntryPrice(double entryPrice) { this.entryPrice = entryPrice; }

    public double getPnl() { return pnl; }
    public void setPnl(double pnl) { this.pnl = pnl; }

    public double getUnrealised() { return unrealised; }
    public void setUnrealised(double unrealised) { this.unrealised = unrealised; }

    public double getRealised() { return realised; }
    public void setRealised(double realised) { this.realised = realised; }

    public double getExposure() { return exposure; }
    public void setExposure(double exposure) { this.exposure = exposure; }

    public double getDayEntryPrice() { return dayEntryPrice; }
    public void setDayEntryPrice(double dayEntryPrice) { this.dayEntryPrice = dayEntryPrice; }
}