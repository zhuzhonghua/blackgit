package com.blackgit;

public class Main {
    public static void main(String[] args) throws Exception {
        Util.printcp();
        //Util.init();
        BlackGit.bg = new BlackGit(args[0]);
        new Server(1666);
    }
}
