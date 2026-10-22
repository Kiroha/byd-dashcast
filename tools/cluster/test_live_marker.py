#!/usr/bin/env python3
"""Execution-free preservation checks, also runnable without firmware corpus in CI."""
import hashlib, json, struct, unittest, zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'app/src/main/assets/cluster_live'

def members(data):
 version,tree,payload,names=struct.unpack_from('>4I',data,4)
 assert data[:4]==b'qres' and version==3
 result={}
 def walk(index,parts):
  p=tree+22*index
  no,flags=struct.unpack_from('>IH',data,p)
  size=struct.unpack_from('>H',data,names+no)[0]
  name=data[names+no+6:names+no+6+2*size].decode('utf-16be') if index else ''
  path=parts+([name] if name else [])
  if flags&2:
   count,first=struct.unpack_from('>II',data,p+6)
   hashes=[]
   for child in range(first,first+count):
    childno=struct.unpack_from('>I',data,tree+22*child)[0]
    hashes.append(struct.unpack_from('>I',data,names+childno+2)[0]);walk(child,path)
   assert hashes==sorted(hashes), 'Qt lookup requires hash-sorted siblings'
  else:
   country,lang,off=struct.unpack_from('>HHI',data,p+6);block=payload+off
   length=struct.unpack_from('>I',data,block)[0];raw=data[block+4:block+4+length]
   decoded=zlib.decompress(raw[4:]) if flags&1 else raw
   if flags&1: assert len(decoded)==struct.unpack_from('>I',raw)[0]
   key='/'.join(path);assert key not in result
   result[key]=(country,lang,flags,raw,decoded)
 walk(0,[]);return result

class LiveMarkerTest(unittest.TestCase):
 def test_exact_carrier_and_stock_preservation(self):
  manifest=json.loads((ASSETS/'manifest.json').read_text());data=(ASSETS/'marker.rcc').read_bytes()
  digest=lambda b:hashlib.sha256(b).hexdigest()
  self.assertEqual(manifest['carrier_sha256'],digest(data));self.assertEqual(manifest['carrier_size'],len(data))
  rows=members(data);self.assertEqual(86,len(rows));self.assertEqual(84,len(manifest['resources']))
  self.assertEqual(set(rows),set(manifest['resources'])|set(manifest['roots']))
  for path,record in manifest['resources'].items():
   country,lang,flags,raw,decoded=rows[path]
   self.assertEqual((country,lang,flags),(record['country'],record['language'],record['flags']))
   self.assertEqual(record['sha256'],digest(decoded));self.assertEqual(record['stored_sha256'],digest(raw))
  expected={'qml/cluster.qml':'0fd1a95f4ec754aa29f53aad5ec540a87c07a4e6a725f09471433502284f6fdb',
   'qml10_25/cluster.qml':'62edd3cbdc59196614c6c69bb7dd4463b9f4bea2539e2a5361be443dbefdc82f'}
  marker=manifest['marker'].encode('ascii')
  for path,original_sha in expected.items():
   qml=rows[path][4];self.assertEqual(1,qml.count(marker))
   self.assertEqual(original_sha,digest(qml.replace(marker,b'',1)))
   self.assertIn(b'WarinLightLayout',qml);self.assertIn(b'RECV_MSG_ID_QML_LOAD_COMPLETE',qml)
   for forbidden in [b'MouseArea',b'Timer',b'onCompleted',b'DataSource',b'Animation',b'ShaderEffect']:
    self.assertNotIn(forbidden,marker)
   self.assertIn(b'z: -1',marker)
 def test_researched_root_generator_is_pinned(self):
  import build_live_marker as builder
  self.assertEqual({'0fd1a95f4ec754aa29f53aad5ec540a87c07a4e6a725f09471433502284f6fdb','62edd3cbdc59196614c6c69bb7dd4463b9f4bea2539e2a5361be443dbefdc82f'},
   {v[1] for v in builder.ORIGINALS.values()})
if __name__=='__main__':unittest.main()
