import sys
from PIL import Image
import numpy as np
def bands(img,thr=232,gap=3,y_from=50,y_to=770):
    a=np.array(img.convert("L"))
    m=(a<thr)
    rows=m.any(axis=1)
    out=[];start=None;last=None
    for y in range(y_from,min(y_to,a.shape[0])):
        if rows[y]:
            if start is None: start=y
            last=y
        elif start is not None and y-last>gap:
            xs=np.where(m[start:last+1].any(axis=0))[0]
            out.append((start,last,xs.min(),xs.max())); start=None
    if start is not None:
        xs=np.where(m[start:last+1].any(axis=0))[0]; out.append((start,last,xs.min(),xs.max()))
    return out
if __name__=="__main__":
    f=Image.open(sys.argv[1]).convert("RGB"); a=Image.open(sys.argv[2]).convert("RGB")
    y_to=int(sys.argv[3]) if len(sys.argv)>3 else 770
    bf=bands(f,y_to=y_to); ba=bands(a,y_to=y_to)
    n=max(len(bf),len(ba))
    print(f"{'FIGMA y0-y1  x0-x1':32}{'APP y0-y1  x0-x1':32} dy")
    for i in range(n):
        l=bf[i] if i<len(bf) else None; r=ba[i] if i<len(ba) else None
        ls=f"{l[0]}-{l[1]}  {l[2]}-{l[3]}" if l else "-"
        rs=f"{r[0]}-{r[1]}  {r[2]}-{r[3]}" if r else "-"
        d=(r[0]-l[0]) if (l and r) else ""
        print(f"{ls:32}{rs:32} {d}")
