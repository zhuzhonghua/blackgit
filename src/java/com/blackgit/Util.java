package com.blackgit;

import org.apache.commons.lang3.exception.ExceptionUtils;
import clojure.lang.Compiler.CompilerException;
import clojure.lang.RT;
import clojure.lang.Var;

public class Util {
    public static String fromClj() {
        return "From Java";
    }

    public static void printcp() {
        String classpath = System.getProperty("java.class.path");

        // 按照系统分隔符（如分号或冒号）拆分并打印，方便阅读
        String[] paths = classpath.split(System.getProperty("path.separator"));
        System.out.println("The following is classpath's value:");
        for (String path : paths) {
            System.out.println(path);
        }
    }

    public static void init() {
        load("blackgit/core.clj", true);
    }

    public static void load(String file, boolean die) {
        Log.clj.debug("load {}", file);
        try {
            RT.loadResourceScript(file);
        } catch (CompilerException ce) {
            Log.clj.error(ce.getMessage(), ce.getCause(), ce);
            Log.clj.error(ExceptionUtils.getStackTrace(ce));
            if (die) {
                Log.clj.error("{} {} {}", ce.getMessage(), ce.getCause(), ce);
                Log.die(4);
            }
        } catch (Exception ce) {
            Log.clj.error(ce.getMessage(), ce.getCause(), ce);
            Log.clj.error(ExceptionUtils.getStackTrace(ce));
            if (die) {
                Log.clj.error("{} {} {}", ce.getMessage(), ce.getCause(), ce);
                Log.die(5);
            }
        }
    }

    public static void cljCall(String ns, String func, Object... params) {
        //Log.clj.debug("call {} {} {}", ns, func, params);
        Var call = RT.var(ns, func);
        call.applyTo(RT.seq(params));
    }
}
