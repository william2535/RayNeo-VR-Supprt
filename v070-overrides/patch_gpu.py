from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.7 GPU patch {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'import org.tensorflow.lite.Interpreter;\n',
'import org.tensorflow.lite.Interpreter;\nimport org.tensorflow.lite.gpu.GpuDelegate;\n',
'GPU import')

one(
'        private Interpreter interpreter;\n',
'        private Interpreter interpreter;\n        private GpuDelegate gpuDelegate;\n',
'GPU field')

one(
'''                Interpreter.Options options=new Interpreter.Options();
                options.setNumThreads(Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors())));
                interpreter=new Interpreter(model,options);
                modelInput=ByteBuffer.allocateDirect(DEPTH_SIZE*DEPTH_SIZE*3*4).order(ByteOrder.nativeOrder());
                modelReady=true;
                Log.i(TAG,"MiDaS depth model ready: "+bytes.length+" bytes");''',
'''                int threads=Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors()));
                try{
                    Interpreter.Options options=new Interpreter.Options();
                    options.setNumThreads(threads);
                    gpuDelegate=new GpuDelegate();
                    options.addDelegate(gpuDelegate);
                    model.rewind();
                    interpreter=new Interpreter(model,options);
                    Log.i(TAG,"MiDaS depth model ready on GPU: "+bytes.length+" bytes");
                }catch(Throwable gpuError){
                    Log.w(TAG,"GPU depth delegate unavailable; using reference CPU fallback",gpuError);
                    try{if(gpuDelegate!=null)gpuDelegate.close();}catch(Throwable ignored){}
                    gpuDelegate=null;
                    Interpreter.Options cpu=new Interpreter.Options();
                    cpu.setUseXNNPACK(false);
                    cpu.setNumThreads(threads);
                    model.rewind();
                    interpreter=new Interpreter(model,cpu);
                    Log.i(TAG,"MiDaS depth model ready on CPU fallback: "+bytes.length+" bytes");
                }
                modelInput=ByteBuffer.allocateDirect(DEPTH_SIZE*DEPTH_SIZE*3*4).order(ByteOrder.nativeOrder());
                modelReady=true;''',
'GPU create/fallback')

one(
'            try{if(interpreter!=null)interpreter.close();}catch(Throwable ignored){}interpreter=null;\n',
'            try{if(interpreter!=null)interpreter.close();}catch(Throwable ignored){}interpreter=null;\n            try{if(gpuDelegate!=null)gpuDelegate.close();}catch(Throwable ignored){}gpuDelegate=null;\n',
'GPU release')

p.write_text(s)
print('v0.7 GPU delegate + CPU fallback transform OK')
