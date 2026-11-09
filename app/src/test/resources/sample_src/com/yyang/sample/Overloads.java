package com.yyang.sample;

public class Overloads {
    public String echo(String s) {
        return s;
    }

    public String echo(int i) {
        return String.valueOf(i);
    }

    public void callBoth() {
        echo("text");
        echo(42);
    }
}
