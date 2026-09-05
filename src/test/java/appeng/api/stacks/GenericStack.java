package appeng.api.stacks;

public class GenericStack {
    private final AEKey what;
    private final long amount;

    public GenericStack(AEKey what, long amount) {
        this.what = what;
        this.amount = amount;
    }

    public AEKey what() {
        return what;
    }

    public long amount() {
        return amount;
    }
}
