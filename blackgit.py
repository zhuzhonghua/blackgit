import subprocess
import sys
import os
import socket
from multiprocessing.connection import Listener
import io
import time
from pathlib import Path
import ipc
import struct
import atexit

class Net:
  def __init__(self):
    self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    self.host = "127.0.0.1"
    self.port = 1666
    self.initnet()

  def initnet(self):
    timeout = 2
    self.sock.settimeout(timeout)
    for i in range(3):
      print("[Net] Try To Connect Server")
      try:
        self.sock.connect((self.host, self.port))
        print(f"[Net] connected {self.host}:{self.port}")
        return True
      except Exception as e:
        print(e, " try again")
        time.sleep(1)
    raise Exception(f"cannot connect to {self.host}:{self.port}")

  def recv0(self, n):
    data = self.sock.recv(n)
    n -= len(data)
    while n > 0:
      packet = self.sock.recv(n)
      data += packet
      n -= len(packet)

    return data

  def recv(self):
    len = self.recv0(4)
    len = struct.unpack('!I', len)[0]
    body = self.recv0(len)
    return body

  def send0(self, arr):
    l = len(arr)
    packet = io.BytesIO()
    packet.write(struct.pack("!I", l))
    packet.write(arr)
    value = packet.getvalue()
    self.sock.sendall(value)

  def sendstr(self, s):
    self.send0(s.encode('utf-8'))

connid = 1
class BlackGit:
  def __init__(self):
    self.net = Net()

  def run(self):
    global connid
    with Listener(ipc.ADDRESS, authkey=ipc.AUTHKEY, family=ipc.FAMILY) as listener:
      print("IPC Server is Listening")
      while True:
        with listener.accept() as conn:
          self._run(conn)
        connid = connid + 1

  def _run(self, conn):
    print(f"{connid} IPC New Client is comming")
    try:
      while True:
        raw = conn.recv()
        print(f"{connid} recv bytes len {len(raw)}")
        self.net.send0(raw)
        data = self.net.recv()
        conn.send(data)

    except EOFError:
      print(f"{connid} IPC Connection closed by Client")

def main():
  try:
    blackw = BlackGit()
    blackw.run()
  except Exception as e:
    print(f"unexpected exception {e}")
    raise e
  except KeyboardInterrupt:
    print("keyboardinterrupt exit")
    sys.exit(1)

def bye():
  print("IPC Server exit")

atexit.register(bye)

if __name__ == "__main__":
  sys.exit(main())
