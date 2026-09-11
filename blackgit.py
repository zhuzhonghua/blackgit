import subprocess
import sys
import os
import socket
import io
import time
from pathlib import Path
import struct

class Net:
  def __init__(self):
    self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    self.host = "127.0.0.1"
    self.port = 1666
    self.initnet()

  def close(self):
    self.sock.close()

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
      if not packet:
        raise ConnectionError("Connection closed")
      data += packet
      n -= len(packet)

    return data

  def _send_all(self, data):
    self.sock.sendall(data)

  def call(self, protocol, body=b''):
    req = protocol.encode('utf-8') + b'\n' + struct.pack('!I', len(body)) + body
    self._send_all(req)

    resp_len_data = self.recv0(4)
    resp_len = struct.unpack('!I', resp_len_data)[0]
    resp_body = self.recv0(resp_len) if resp_len > 0 else b''
    return resp_body
