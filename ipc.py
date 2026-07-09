import sys
import message_pb2
import io
import struct

ADDRESS = r"\\.\pipe\black_ipc"
AUTHKEY = b"ipc"
FAMILY = "AF_PIPE"

if sys.platform != "win32":
  ADDRESS = "/tmp/black_ipc.sock"
  FAMILY = "AF_UNIX"

OP_LIST = 1
OP_FETCH = 2

def cget_list(push):
  clist = message_pb2.List()
  clist.forpush = push
  cbins = clist.SerializeToString()
  packet = io.BytesIO()
  packet.write(struct.pack("!H", OP_LIST))
  packet.write(cbins)
  return packet.getvalue()

def sget_list(bins):
  slist = message_pb2.List()
  slist.ParseFromString(bins)
  shana = []
  for i in slist.items:
    shana.append((i.sha, i.name))
  return shana

def cget_fetch(shas):
  cfetch = message_pb2.Fetch()
  for sha in shas:
    item = cfetch.shas.add()
    item.sha = sha
  cbins = cfetch.SerializeToString()
  packet = io.BytesIO()
  packet.write(struct.pack("!H", OP_FETCH))
  packet.write(cbins)
  return packet.getvalue()

def sget_fetch(bins):
  sfetch = message_pb2.Fetch()
  sfetch.ParseFromString(bins)
  return sfetch
