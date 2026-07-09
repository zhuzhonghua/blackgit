package com.blackgit;

import java.nio.ByteBuffer;
import java.util.Map;

@SuppressWarnings("ALL")
public class OPProcesser {
    public static void process(SocketClient client, ByteBuffer buf) throws Exception {
        int op = buf.getShort();
        Log.net.debug("Recv op {}", op);
        switch (op) {
            case 1:
                Message.List lst = Message.List.parseFrom(buf);
                Map<String, String> nameId = BlackGit.bg.getList();
                Message.List.Builder retlst = Message.List.newBuilder();
                for (Map.Entry<String, String> entry: nameId.entrySet()) {
                    Message.DListItem.Builder dlst = Message.DListItem.newBuilder();
                    dlst.setName(entry.getKey());
                    dlst.setSha(entry.getValue());
                    retlst.addItems(dlst);
                }
                client.write(retlst.build());
                break;
            case 2:
                Message.Fetch fetch = Message.Fetch.parseFrom(buf);
                Message.Fetch.Builder sfetch = BlackGit.bg.getFetch2(fetch);
                client.write(sfetch.build());
                break;
            default:
                throw new Exception("Unknown op "+op);
        }
    }
}
