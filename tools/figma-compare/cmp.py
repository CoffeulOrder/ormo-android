import sys,subprocess,io
from PIL import Image, ImageChops
SP=sys.argv[0].rsplit('/',1)[0] if '/' in sys.argv[0] else '.'
def app(path):
    adb="C:/dev/android-sdk/platform-tools/adb.exe"
    data=subprocess.run([adb,"exec-out","screencap","-p"],capture_output=True).stdout
    im=Image.open(io.BytesIO(data)).convert("RGB").resize((375,812),Image.LANCZOS)
    im.save(path); return im
if __name__=="__main__":
    fig=Image.open(sys.argv[1]).convert("RGB")   # figma frame
    out=sys.argv[2]
    a=app(out.replace('.png','_app.png'))
    h=min(fig.height,a.height)
    fig=fig.crop((0,0,375,h)); a=a.crop((0,0,375,h))
    diff=ImageChops.difference(fig,a).convert("L").point(lambda v:255 if v>40 else 0)
    M=Image.new("RGB",(375*3+20,h),"gray")
    M.paste(fig,(0,0)); M.paste(a,(385,0)); M.paste(diff.convert("RGB"),(770,0)); M.save(out)
