package com.liang.pathweaver.data;

public enum PathMode {
    LINEAR("直线"),
    BEZIER("贝塞尔曲线");

    public final String chineseName;

    PathMode(String chineseName) {
        this.chineseName = chineseName;
    }

    public PathMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
