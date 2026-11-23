import sys,re
import xml.etree.ElementTree as ET
t=ET.parse('figma_meta.xml'); root=t.getroot()
def find(n,i):
    if n.get('id')==i: return n
    for c in n:
        r=find(c,i)
        if r is not None: return r
def walk(n,ox,oy,depth,maxd,out):
    x=float(n.get('x',0));y=float(n.get('y',0))
    ax,ay=ox+x,oy+y
    nm=n.get('name','')
    out.append(f"{'  '*depth}{n.tag[:4]} {n.get('id')} [{ax:.0f},{ay:.0f} {float(n.get('width',0)):.0f}x{float(n.get('height',0)):.0f}] {nm[:50]}")
    if depth<maxd:
        for c in n: walk(c,ax,ay,depth+1,maxd,out)
n=find(root,sys.argv[1]); maxd=int(sys.argv[2]) if len(sys.argv)>2 else 3
out=[]
# frame-relative: origin at frame
walk(n,-float(n.get('x',0)),-float(n.get('y',0)),0,maxd,out)
print('\n'.join(out))
