package mygrant.impact;

public class ImpactNotFoundException extends RuntimeException {
    public ImpactNotFoundException() {
        super("Impact assessment not found");
    }
}
