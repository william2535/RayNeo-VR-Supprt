from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.7.1 renderer patch {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'''        private static final int DEPTH_SIZE=256;
        private static final int DEPTH_INTERVAL=4;
        private static final float DEPTH_TEMPORAL_NEW=0.32f;''',
'''        private static final int DEPTH_SIZE=256;
        // Stability mode: never hammer the external display or MiDaS worker.
        // The Air 4 Pro only needs a steady 60 Hz feed; the latest depth map can
        // safely be reused for many video frames.
        private static final long MIN_RENDER_NS=16_666_667L;   // ~60 fps max
        private static final long MIN_DEPTH_NS=125_000_000L;  // ~8 AI maps/sec max
        private static final float DEPTH_TEMPORAL_NEW=0.32f;''',
'frame/depth pacing constants')

one(
'        private int viewWidth,viewHeight,frameCounter=0;\n',
'        private int viewWidth,viewHeight;\n        private volatile long lastRenderRequestNs=0L;\n        private long lastDepthCaptureNs=0L;\n',
'pacing fields')

one(
'            if(gotFrame && aiDepthPercent>0.1f && modelReady && !inferenceBusy.get() && (++frameCounter%DEPTH_INTERVAL)==0)captureDepthFrame();',
'''            long nowNs=System.nanoTime();
            if(gotFrame && aiDepthPercent>0.1f && modelReady && !inferenceBusy.get()
                    && nowNs-lastDepthCaptureNs>=MIN_DEPTH_NS){
                lastDepthCaptureNs=nowNs;
                captureDepthFrame();
            }''',
'depth time throttle')

one(
'                pendingDepth=out;owner.requestRender();\n',
'''                // Do not cause a second unsynchronised presentation frame when
                // inference completes. The next video frame uploads this map.
                pendingDepth=out;\n''',
'no inference redraw burst')

one(
'        @Override public void onFrameAvailable(SurfaceTexture st){frameAvailable=true;owner.requestRender();}\n',
'''        @Override public void onFrameAvailable(SurfaceTexture st){
            frameAvailable=true;
            long now=System.nanoTime();
            if(now-lastRenderRequestNs>=MIN_RENDER_NS){
                lastRenderRequestNs=now;
                owner.requestRender();
            }
        }\n''',
'60fps display throttle')

p.write_text(s)
print('v0.7.1 renderer stability pacing transform OK')
