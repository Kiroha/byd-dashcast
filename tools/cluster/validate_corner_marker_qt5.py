#!/usr/bin/env python3
"""Optional existing-PyQt5 check of owned QML only; no OEM roots/plugins or vehicle calls."""
import os,sys,tempfile
from pathlib import Path
os.environ['QT_QPA_PLATFORM']='offscreen'
os.environ['QT_QUICK_BACKEND']='software'
os.environ['QSG_RENDER_LOOP']='basic'
sys.path.insert(0,str(Path(__file__).resolve().parent))
import build_corner_marker as proof
import build_live_marker as rcc
from PyQt5.QtCore import QResource,QUrl,QFile,QIODevice,QT_VERSION_STR
from PyQt5.QtGui import QGuiApplication,QColor
from PyQt5.QtQml import QQmlEngine,QQmlComponent,QQmlPropertyMap
from PyQt5.QtQuick import QQuickWindow
from PyQt5.QtTest import QTest
app=QGuiApplication([])
engine=QQmlEngine()
source=QQmlPropertyMap()
for key,val in dict(powerGear=3,playAnim=True,gearMode=1,speed=0).items():source.insert(key,val)
engine.rootContext().setContextProperty('DataSource',source)
qml=b'import QtQuick 2.15\nItem { width: 1920; height: 720; Rectangle { anchors.fill: parent; z: -2; color: "#101820" } '+proof.MARKER+b'\n Rectangle { x: 2; y: 2; width: 20; height: 20; z: 100; color: "#ff0000" } }'
# Only our marker fragment runs: no OEM root, BYD module, callback or native plugin.
with tempfile.TemporaryDirectory(prefix='dashcast-owned-qt-proof-') as tmp:
 paths=[]
 for name,data in [('first',b'first'),('second',b'second')]:
  p=Path(tmp)/(name+'.rcc');p.write_bytes(rcc.build([('dashcast_owned_proof/precedence.txt',0,1,0,data,data)]));paths.append(p)
 assert QResource.registerResource(str(paths[0])) and QResource.registerResource(str(paths[1]))
 f=QFile(':/dashcast_owned_proof/precedence.txt');assert f.open(QIODevice.ReadOnly);assert bytes(f.readAll())==b'first';f.close();del f
 assert QResource.unregisterResource(str(paths[0]))
 f=QFile(':/dashcast_owned_proof/precedence.txt');assert f.open(QIODevice.ReadOnly);assert bytes(f.readAll())==b'second';f.close();del f
 assert QResource.unregisterResource(str(paths[1]))
 # Compile the same owned fragment under each real alias in a self-owned test carrier.
 for alias in ['qml/cluster.qml','qml10_25/cluster.qml']:
  p=Path(tmp)/'fragment.rcc';p.write_bytes(rcc.build([(alias,0,1,0,qml,qml)]));assert QResource.registerResource(str(p))
  component=QQmlComponent(engine,QUrl('qrc:/'+alias));assert not component.isError(),component.errors()
  root=component.create();assert root is not None,component.errors()
  marker=[item for item in root.childItems() if item.property('z')==1][0];assert marker.property('visible')
  for w,h in [(1920,720),(1280,480)]:
   root.setProperty('width',w);root.setProperty('height',h);app.processEvents()
   rects=marker.childItems();assert len(rects)==8
   for rect in rects:
    x,y,rw,rh=[rect.property(v) for v in ['x','y','width','height']]
    assert 0<=x<=w-rw and 0<=y<=h-rh and min(rw,rh)==4,(x,y,rw,rh)
  root.setProperty('width',1920);root.setProperty('height',720)
  window=QQuickWindow();window.setColor(QColor('#101820'));window.resize(1920,720);root.setParentItem(window.contentItem());window.show();QTest.qWait(40)
  grab=root.grabToImage();QTest.qWait(150);image=grab.image();assert not image.isNull()
  for x,y,color in [(0,0,'#00e5ff'),(1919,0,'#ff40d5'),(0,719,'#ff40d5'),(1919,719,'#00e5ff'),(960,360,'#101820'),(3,3,'#ff0000')]:
   assert image.pixelColor(x,y).name()==color,(alias,x,y,image.pixelColor(x,y).name())
  for key,bad in [('powerGear',None),('powerGear',2),('playAnim',False),('gearMode',0),('gearMode',4),('speed',-1),('speed',1),('speed',None)]:
   before=source.value(key);source.insert(key,bad);app.processEvents();assert not marker.property('visible'),(key,bad)
   source.insert(key,before);app.processEvents();assert marker.property('visible')
  root.setParentItem(None);window.close();root.deleteLater();app.processEvents();del component
  engine.clearComponentCache();assert QResource.unregisterResource(str(p))
print('PASS Qt',QT_VERSION_STR,': first-registered alias lookup; 2 isolated aliases; 2 geometries; pixel colors; 8 strict guard failures. No OEM QML executed.')
