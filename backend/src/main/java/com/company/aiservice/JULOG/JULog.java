package com.company.aiservice.JULOG;



import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;


/**
 * JULOG 1.0于 2022 年 7 月 19 日 由木星开发的日志组件
 *
 * @author jupiter
 * @version 2.0
 * @since 2023-07-10
 */
// 更新日志 2023.7.13 加入了JUPrint
@Component("julogAPI")
public class JULog {
    private Date da;
    @Value("${julog.logpath}")
    private String filename;

    private File fee;
    private String pretext = "[yyyy-MM-dd HH:mm:ss.SSS]";
    private String pretip = "";
    private String prevalue;
    private BufferedWriter fw;
    private BufferedReader fr;
    private int day = 10;

    public static void main(String[] args) {
//        Log l = new Log();
//        l.printf(Tip.EXCEPTION, "10");
//        l.printf(Tip.MESSAGE, "25");
//        l.printf(Tip.WARRING, "215");
//        l.printf(Tip.ERROR, "21d5");
    }

    public void tocreatefile() {
        if (fee == null) {
            this.fee = new File(filename);
        }

    }


    public String getPretip() {
        return this.pretip;
    }

    public void setPretip(String pretip) {
        this.pretip = pretip;
    }

    public int getDay() {
        return this.day;
    }

    public void setDay(int day) {
        this.day = day;
    }

    public String getPretext() {
        return this.pretext;
    }

    public void setPretext(String pretext) {
        this.pretext = pretext;
    }


    protected void finalize(int San) {
        if (this.fw != null) {
            try {
                this.fw.close();
            } catch (IOException var4) {
                var4.printStackTrace();
            }
        }

        if (this.fr != null) {
            try {
                this.fr.close();
            } catch (IOException var3) {
                var3.printStackTrace();
            }
        }

    }

    private void pz() {
        this.da = new Date();
        DateFormat a = DateFormat.getDateInstance();
        SimpleDateFormat df = new SimpleDateFormat(this.pretext);
        this.prevalue = df.format(this.da);
    }

    public void write(String str) {
        tocreatefile();


        this.pz();

        try {
            if (this.clearLog()) {
                this.fw = new BufferedWriter(new FileWriter(this.fee, false));
            }

            this.fw = new BufferedWriter(new FileWriter(this.fee, true));
            this.fw.write(this.prevalue + " " + this.pretip + " " + str + "\n");
            this.fw.flush();
        } catch (IOException var3) {
            var3.printStackTrace();
        }

    }

    public void write(Tip t, String str) {
        tocreatefile();
        this.pz();
        printf(t, str);

        try {
            if (this.clearLog()) {
                this.fw = new BufferedWriter(new FileWriter(this.fee, false));
            }

            this.fw = new BufferedWriter(new FileWriter(this.fee, true));
            this.fw.write(this.prevalue + " " + t + " " + this.pretip + " " + str + "\n");
            this.fw.flush();
        } catch (IOException var4) {
            var4.printStackTrace();
        }

    }

    //如果不开启也不会打印(一般用这个)
    public void write(Tip t, String str, boolean enableJuprint) {
        tocreatefile();
        this.pz();
        String s = null;
        if (enableJuprint) {
            s = JUprintf(t, str);
        }


        try {
            if (this.clearLog()) {
                this.fw = new BufferedWriter(new FileWriter(this.fee, false));
            }

            this.fw = new BufferedWriter(new FileWriter(this.fee, true));
            if (s != null)
                this.fw.write(this.prevalue + " " + t + " " + this.pretip + " " + str + "\n\t\t\t\t\t\t\t\t\t\t\t——" + s + "\n");
            else
                this.fw.write(this.prevalue + " " + t + " " + this.pretip + " " + str + "\n");
            this.fw.flush();
        } catch (IOException var4) {
            var4.printStackTrace();
        }

    }

    public void write(String t, String str) {
        tocreatefile();
        this.pz();

        try {
            if (this.clearLog()) {
                this.fw = new BufferedWriter(new FileWriter(this.fee, false));
            }

            this.fw = new BufferedWriter(new FileWriter(this.fee, true));
            this.fw.write(this.prevalue + " " + t + " " + this.pretip + " " + str + "\n");
            this.fw.flush();
        } catch (IOException var4) {
            var4.printStackTrace();
        }

    }

    public LinkedList<String> read() {
        tocreatefile();
        LinkedList ll = new LinkedList();

        try {
            this.fr = new BufferedReader(new FileReader(this.fee));

            String str;
            try {
                while ((str = this.fr.readLine()) != null) {
                    ll.add(str);
                }
            } catch (IOException var4) {
                var4.printStackTrace();
            }
        } catch (FileNotFoundException var5) {
            var5.printStackTrace();
        }

        return ll;
    }

    public String read(LinkedList<String> li) {
        StringBuffer sbu = new StringBuffer();
        Iterator var3 = li.iterator();

        while (var3.hasNext()) {
            String l = (String) var3.next();
            sbu.append(l + "\n");
        }

        return new String(sbu);
    }

    public String select(LinkedList<String> li, Tip t) {
        StringBuffer sbu = new StringBuffer();
        Iterator var4 = li.iterator();

        while (var4.hasNext()) {
            String l = (String) var4.next();
            if (l.indexOf(t.toString()) != -1) {
                sbu.append(l + "\n");
            }
        }

        return new String(sbu);
    }

    public void printf(String str) {
        this.pz();
        System.out.print(this.prevalue + str + "\n");

    }

    public void printf(Tip t, String str) {
        this.pz();
        if (t == Tip.WARRING) {
            System.out.printf("\u001b[33;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str + "\n");
        } else if (t == Tip.ERROR) {
            System.out.printf("\u001b[31;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str + "\n");
        } else if (t == Tip.MESSAGE) {
            System.out.printf("\u001b[34;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str + "\n");
        } else if (t == Tip.EXCEPTION) {
            System.out.printf("\u001b[35;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str + "\n");
        } else {
            System.out.printf("\u001b[30;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str + "\n");
        }

        System.out.print("\u001B[0m");

    }

    public String JUprintf(Tip t, String str) {
        this.pz();
        String print;
        if (t == Tip.WARRING) {
            print = JUPrint.print("\u001b[33;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str, 4);
        } else if (t == Tip.ERROR) {
            print = JUPrint.print("\u001b[31;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str, 4);
        } else if (t == Tip.MESSAGE) {
            print = JUPrint.print("\u001b[34;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str, 4);
        } else if (t == Tip.EXCEPTION) {
            print = JUPrint.print("\u001b[35;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str, 4);
        } else {
            print = JUPrint.print("\u001b[30;4m" + this.prevalue + " " + t + " " + this.pretip + " " + str, 4);
        }
        System.out.print("\u001B[0m");
        return print;

    }

    public void cocu(String b, boolean isCopy) {
        tocreatefile();
        try {
            this.fr = new BufferedReader(new FileReader(this.fee));
            this.fw = new BufferedWriter(new FileWriter(b));
            String str = "";

            while ((str = this.fr.readLine()) != null) {
                this.fw.write(str);
                this.fw.newLine();
                this.fw.flush();
            }

            this.fw.close();
            this.fr.close();
            System.gc();
            if (!isCopy) {
                this.fee.delete();
                this.fee = null;
            }
        } catch (Exception var4) {
            var4.printStackTrace();
        }

    }

    private boolean clearLog() {

//        v1.0功能
//        tocreatefile();
//        try {
//            try {
//                this.fr = new BufferedReader(new FileReader(this.fee));
//            } catch (FileNotFoundException var5) {
//                return false;
//            }
//
//            String bj = this.fr.readLine();
//            SimpleDateFormat sdf = new SimpleDateFormat(this.pretext);
//            Long t1 = sdf.parse(bj).getTime();
//            Long t2 = (new Date()).getTime();
//            return (t2 - t1) / 1000L / 60L / 60L / 24L > (long)this.day;
//        } catch (Exception var6) {
//            var6.printStackTrace();
//            return false;
//        }
        return false;
    }
}

