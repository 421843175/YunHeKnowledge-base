package com.company.aiservice.JULOG;

public class JUPrint {

    public static void print(String str) {
        StackTraceElement se = Thread.currentThread().getStackTrace()[2];
        System.out.println(str + "\t\t\t\t\t\t[line:" + se.getLineNumber()
                + "(file:" + se.getFileName() + ")]");
    }

    //n 高级栈参数
    public static String print(String str, int n) {
        StackTraceElement se = Thread.currentThread().getStackTrace()[n];
        String strr = "line:" + se.getLineNumber() + "(file:" + se.getFileName() + ")";
        System.out.println(str + "\n\t\t\t\t\t\t\t\t\t\t——" + strr);
        return strr;
    }
}
