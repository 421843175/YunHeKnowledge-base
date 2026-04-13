package com.company.aiservice.JULOG;

public enum Tip {
    MESSAGE("信息"),
    WARRING("警告"),
    ERROR("错误"),
    EXCEPTION("异常"),
    TS("调试"),
    ZY("注意"),
    JJ("警戒"),
    GJ("告警"),
    YAN("严重"),
    ZM("致命");

    private final String v;

    private Tip(String v) {
        this.v = v;
    }

    public String toString() {
        return this.v;
    }
}
