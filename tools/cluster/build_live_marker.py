#!/usr/bin/env python3
"""Deterministic, execution-free RCC builder. Preserves the 84 stock locale resources.
Only change to each OEM root is MARKER inserted after root id. No target QML runs.
"""
import argparse, hashlib, json, struct, zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'app/src/main/assets/cluster_live'
STOCK='5a0f1c39d0921752e863978d85a9f5bc0e0e75f5bd8b5fefa0b0af10624669c8'
# Deliberately below default-z functional children; visibility is not assured.
MARKER=b'\r\n    // DASHCAST_PASSIVE_MARKER_BEGIN\r\n    Rectangle {\r\n        x: 8; y: 346; width: 136; height: 28; z: -1\r\n        color: "#112a30"; border.color: "#00cddd"; border.width: 1\r\n        Text { anchors.centerIn: parent; text: "DASHCAST TEST"; color: "#00cddd"; font.pixelSize: 16 }\r\n    }\r\n    // DASHCAST_PASSIVE_MARKER_END\r\n'
ORIGINALS={
 'qml/cluster.qml':('theme1','0fd1a95f4ec754aa29f53aad5ec540a87c07a4e6a725f09471433502284f6fdb'),
 'qml10_25/cluster.qml':('theme2','62edd3cbdc59196614c6c69bb7dd4463b9f4bea2539e2a5361be443dbefdc82f')}

def sha(b): return hashlib.sha256(b).hexdigest()
def qhash(s):
 h=0
 for c in s:
  h=(h<<4)+ord(c); h^=(h&0xf0000000)>>23; h&=0x0fffffff
 return h

def decode(b):
 assert b[:4]==b'qres'
 v,t,d,n=struct.unpack_from('>4I',b,4); assert v==3
 rows=[]
 def walk(i,parts):
  o=t+22*i; no,flags=struct.unpack_from('>IH',b,o)
  l=struct.unpack_from('>H',b,n+no)[0]
  name=b[n+no+6:n+no+6+2*l].decode('utf-16be') if i else ''
  path=parts+([name] if name else [])
  if flags&2:
   count,first=struct.unpack_from('>II',b,o+6)
   for j in range(first,first+count):walk(j,path)
  else:
   country,lang,off=struct.unpack_from('>HHI',b,o+6)
   size=struct.unpack_from('>I',b,d+off)[0];raw=b[d+off+4:d+off+4+size]
   val=zlib.decompress(raw[4:]) if flags&1 else raw
   rows.append(('/'.join(path),country,lang,flags,raw,val))
 walk(0,[]);return rows

def build(rows):
 root={'name':'','children':{}}
 for path,country,lang,flags,raw,val in rows:
  parts=path.split('/');node=root
  for part in parts[:-1]:node=node['children'].setdefault(part,{'name':part,'children':{}})
  assert parts[-1] not in node['children']
  node['children'][parts[-1]]={'name':parts[-1],'record':(country,lang,flags,raw)}
 nodes=[root]
 for node in nodes:
  if 'children' in node:
   children=sorted(node['children'].values(),key=lambda x:(qhash(x['name']),x['name']))
   node['first']=len(nodes);node['count']=len(children);nodes.extend(children)
 names=bytearray();nameoffs={};payload=bytearray();tree=bytearray()
 for node in nodes:
  name=node['name']
  if name not in nameoffs:
   nameoffs[name]=len(names);names+=struct.pack('>HI',len(name),qhash(name))+name.encode('utf-16be')
  if 'children' in node:tree+=struct.pack('>IHIIQ',nameoffs[name],2,node['count'],node['first'],0)
  else:
   country,lang,flags,raw=node['record'];off=len(payload)
   payload+=struct.pack('>I',len(raw))+raw
   tree+=struct.pack('>IHHHIQ',nameoffs[name],flags,country,lang,off,0)
 d=24;n=d+len(payload);t=n+len(names)
 return b'qres'+struct.pack('>5I',3,t,d,n,1)+payload+names+tree

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--stock',type=Path,required=True);p.add_argument('--roots',type=Path,required=True)
 p.add_argument('--check',action='store_true');a=p.parse_args()
 stock=a.stock.read_bytes();assert sha(stock)==STOCK
 rows=decode(stock);assert len(rows)==84 and all(r[0].startswith('common/language/') for r in rows)
 manifest={'schema':1,'stock_sha256':STOCK,'resources':{},'roots':{},'marker':MARKER.decode('ascii')}
 for path,country,lang,flags,raw,val in rows:manifest['resources'][path]={'sha256':sha(val),'stored_sha256':sha(raw),'country':country,'language':lang,'flags':flags}
 for path,(theme,original_sha) in ORIGINALS.items():
  original=(a.roots/'extracted'/theme/path).read_bytes()
  assert sha(original)==original_sha, 'Unexpected original OEM root'
  anchor=b'    id:rootItemId\r\n';assert original.count(anchor)==1
  marked=original.replace(anchor,anchor+MARKER,1)
  raw=struct.pack('>I',len(marked))+zlib.compress(marked,9)
  rows.append((path,0,1,1,raw,marked))
  manifest['roots'][path]={'original_sha256':sha(original),'marked_sha256':sha(marked),'original_size':len(original)}
 result=build(rows);assert len(result)<4*1024*1024
 actual=decode(result);assert len(actual)==86
 assert [(r[0],r[1],r[2],sha(r[4]),sha(r[5])) for r in sorted(actual)]==[(r[0],r[1],r[2],sha(r[4]),sha(r[5])) for r in sorted(rows)]
 manifest['carrier_sha256']=sha(result);manifest['carrier_size']=len(result)
 text=(json.dumps(manifest,indent=2,ensure_ascii=True)+'\n').encode()
 if a.check:
  assert (OUT/'marker.rcc').read_bytes()==result
  assert (OUT/'manifest.json').read_bytes()==text
 else:
  OUT.mkdir(parents=True,exist_ok=True);(OUT/'marker.rcc').write_bytes(result);(OUT/'manifest.json').write_bytes(text)
 print(f'86 resources, {len(result)} bytes, SHA256 {sha(result)}; no runtime execution')
if __name__=='__main__':main()
