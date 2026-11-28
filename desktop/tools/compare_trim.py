from pathlib import Path
from tempfile import TemporaryDirectory
import json, subprocess as s,time
import imageio_ffmpeg
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
ffmpeg=imageio_ffmpeg.get_ffmpeg_exe()
from aircursor_desktop.media import trim_args
with TemporaryDirectory() as d:
 p=Path(d)
 source=p/'source.mp4'
 s.run([ffmpeg,'-loglevel','error','-f','lavfi','-i','testsrc2=size=1280x720:rate=30','-f','lavfi','-i','sine=frequency=440','-t','6','-c:v','libx264','-g','30','-pix_fmt','yuv420p','-threads','2','-c:a','aac',str(source)],check=True)
 for i in range(3):
  for mode in (False,True):
   out=p/f'{i}-{mode}.mp4'; start=time.perf_counter()
   s.run([ffmpeg,*trim_args(source,2,5,out,stream_copy=mode)],check=True)
   elapsed=time.perf_counter()-start
   s.run([ffmpeg,'-v','error','-xerror','-i',str(out),'-f','null','-'],check=True)
   print(json.dumps(dict(stream_copy=mode, wall_seconds=round(elapsed,3), verified_decode=True)),flush=True)
