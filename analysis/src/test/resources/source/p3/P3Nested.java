package p3.source;

public class P3Nested {
    public static class Inner {
        public String echo(String input) { return "string:{" + input + "}"; }
        public int echo(int input) { return input + 73; }
        public String other() { return "not-the-requested-method"; }
    }
}
