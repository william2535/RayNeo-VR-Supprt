from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.7.2 renderer patch {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'        private final AtomicBoolean inferenceBusy=new AtomicBoolean(false);\n',
'        private final AtomicBoolean inferenceBusy=new AtomicBoolean(false);\n        private final AtomicBoolean renderWakePending=new AtomicBoolean(false);\n',
'render wake field')

one(
'''                // Do not cause a second unsynchronised presentation frame when
                // inference completes. The next video frame uploads this map.
                pendingDepth=out;''',
'''                // Upload on the next paced presentation tick. This uses the same
                // 60 Hz gate as captured video, so AI completion cannot burst the
                // RayNeo output but a static scene still receives the new map.
                pendingDepth=out;
                requestPacedRender();''',
'paced depth upload wake')

one(
'''        @Override public void onFrameAvailable(SurfaceTexture st){
            frameAvailable=true;
            long now=System.nanoTime();
            if(now-lastRenderRequestNs>=MIN_RENDER_NS){
                lastRenderRequestNs=now;
                owner.requestRender();
            }
        }
''',
'''        private void requestPacedRender(){
            if(released)return;
            long now=System.nanoTime();
            long waitNs=MIN_RENDER_NS-(now-lastRenderRequestNs);
            if(waitNs<=0L){
                if(renderWakePending.compareAndSet(false,true)){
                    lastRenderRequestNs=now;
                    renderWakePending.set(false);
                    try{owner.requestRender();}catch(Throwable ignored){}
                }
                return;
            }
            if(renderWakePending.compareAndSet(false,true)){
                long delayMs=Math.max(1L,(waitNs+999_999L)/1_000_000L);
                owner.postDelayed(()->{
                    renderWakePending.set(false);
                    if(released)return;
                    lastRenderRequestNs=System.nanoTime();
                    try{owner.requestRender();}catch(Throwable ignored){}
                },delayMs);
            }
        }

        @Override public void onFrameAvailable(SurfaceTexture st){
            // SurfaceTexture may coalesce callbacks until updateTexImage() drains
            // the pending buffer. Never drop the wake-up: if a frame arrives
            // inside the 60 Hz guard window, schedule the drain for the end of
            // that window instead of waiting for another callback that may never
            // arrive.
            frameAvailable=true;
            requestPacedRender();
        }
''',
'guaranteed paced drain')

p.write_text(s)
print('v0.7.2 guaranteed SurfaceTexture drain pacing transform OK')
