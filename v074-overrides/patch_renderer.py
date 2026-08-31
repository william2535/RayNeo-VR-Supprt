from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.7.4 renderer patch {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'        private static final long MIN_DEPTH_NS=125_000_000L;  // ~8 AI maps/sec max\n',
'        private static final long MIN_DEPTH_NS=500_000_000L;  // ~2 AI maps/sec max; protect external display\n',
'AI pacing 2Hz')

one(
'''                Interpreter.Options options=new Interpreter.Options();
                options.setNumThreads(Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors())));
                interpreter=new Interpreter(model,options);
                modelInput=ByteBuffer.allocateDirect(DEPTH_SIZE*DEPTH_SIZE*3*4).order(ByteOrder.nativeOrder());
                modelReady=true;
                Log.i(TAG,"MiDaS depth model ready: "+bytes.length+" bytes");''',
'''                // CPU-SAFE mode: do not attach a TensorFlow GPU delegate while
                // Android is also driving the RayNeo native-SBS external display.
                // One XNNPACK CPU thread at ~2 Hz is intentionally conservative;
                // the latest depth map is reused between inferences.
                Interpreter.Options options=new Interpreter.Options();
                options.setUseXNNPACK(true);
                options.setNumThreads(1);
                interpreter=new Interpreter(model,options);
                modelInput=ByteBuffer.allocateDirect(DEPTH_SIZE*DEPTH_SIZE*3*4).order(ByteOrder.nativeOrder());
                modelReady=true;
                Log.i(TAG,"MiDaS CPU-SAFE depth ready: "+bytes.length+" bytes • 1 thread • ~2 Hz");''',
'CPU safe interpreter')

p.write_text(s)
print('v0.7.4 CPU-safe MiDaS renderer transform OK')
