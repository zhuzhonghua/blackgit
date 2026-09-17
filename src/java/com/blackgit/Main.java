package com.blackgit;

import com.black.Log;

public class Main {
    public static void main(String[] args) throws Exception {
        Util.printcp();
        //Util.init();
        if (args.length <= 0) {
            Log.logger.info("need a repo argument");
            return;
        }
        BlackGit.bg = new BlackGit(args[0]);
        int port = 1666;
        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        }
        new Server(port);
    }
}
