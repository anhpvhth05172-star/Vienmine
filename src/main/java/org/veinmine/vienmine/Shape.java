package org.veinmine.vienmine;

public enum Shape {
    SHAPELESS("vienmine.shape.shapeless"),
    TUNNEL("vienmine.shape.tunnel"),
    SQUARE("vienmine.shape.square"),
    COLUMN("vienmine.shape.column");

    private final String langKey;

    Shape(String langKey) {
        this.langKey = langKey;
    }

    public String langKey() {
        return langKey;
    }

    public Shape next() {
        Shape[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public static Shape byOrdinal(int o) {
        Shape[] all = values();
        if (o < 0 || o >= all.length) return SHAPELESS;
        return all[o];
    }
}
