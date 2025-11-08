package signal;

public enum SignalStrength {
    WEAK("Слабый"),
    MEDIUM("Средний"),
    STRONG("Сильный");

    public final String ru;
    SignalStrength(String ru) { this.ru = ru; }

    @Override public String toString() { return ru; }
}
