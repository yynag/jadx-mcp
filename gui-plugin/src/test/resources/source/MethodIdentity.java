package regression;

public class MethodIdentity {
    public String adjacent() { return "ADJACENT_ONLY"; }
    public String echo(int value) { return "INTEGER_ONLY:" + value; }
    public String echo(String value) { return "STRING_ONLY:" + value; }
    public String unique() { return "UNIQUE_ONLY"; }
    public static class Inner {
        public String echo(String value) { return "INNER_ONLY:" + value; }
        public String after() { return "AFTER_ONLY"; }
    }
}
