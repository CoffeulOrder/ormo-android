import sys
from PIL import Image
import numpy as np
def rows(img,y0,y1,x0=0,x1=375,thr=235):
    a=np.array(img.convert("L"))[y0:y1,x0:x1]
    m=(a<thr)
    ys=np.where(m.any(axis=1))[0]; xs=np.where(m.any(axis=0))[0]
    if len(ys)==0: return None
    return (x0+xs.min(),y0+ys.min(),x0+xs.max(),y0+ys.max())
if __name__=="__main__":
    f=Image.open(sys.argv[1]); a=Image.open(sys.argv[2])
    y0,y1=int(sys.argv[3]),int(sys.argv[4])
    print("figma",rows(f,y0,y1)); print("app  ",rows(a,y0,y1))
