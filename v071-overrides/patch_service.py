from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.7.1 service patch {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'/** v0.7 native-SBS service with live MiDaS depth controls. */',
'/** v0.7.1 native-SBS service with paced AI depth and RayNeo display-link recovery. */',
'doc title')

one(
'    private String outputName="unknown";\n',
'''    private String outputName="unknown";
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private int externalDisplayId=-1;\n''',
'display recovery fields')

one(
'            DisplayManager displayManager=(DisplayManager)getSystemService(DISPLAY_SERVICE);\n            Display external=findBestExternalDisplay(displayManager);',
'''            displayManager=(DisplayManager)getSystemService(DISPLAY_SERVICE);
            registerDisplayListener();
            Display external=findBestExternalDisplay(displayManager);''',
'register display listener')

one(
'                usingExternalDisplay=true;Display.Mode mode=external.getMode();outputWidth=mode.getPhysicalWidth();outputHeight=mode.getPhysicalHeight();outputName=external.getName();nativeWideMode=isWide(outputWidth,outputHeight);',
'                usingExternalDisplay=true;externalDisplayId=external.getDisplayId();Display.Mode mode=external.getMode();outputWidth=mode.getPhysicalWidth();outputHeight=mode.getPhysicalHeight();outputName=external.getName();nativeWideMode=isWide(outputWidth,outputHeight);',
'remember external id')

one(
'                usingExternalDisplay=false;Display defaultDisplay=defaultWm.getDefaultDisplay();',
'                usingExternalDisplay=false;externalDisplayId=-1;Display defaultDisplay=defaultWm.getDefaultDisplay();',
'reset external id fallback')

marker='    private Display findBestExternalDisplay(DisplayManager dm){\n'
insert=r'''    private void registerDisplayListener(){
        if(displayManager==null||displayListener!=null)return;
        displayListener=new DisplayManager.DisplayListener(){
            @Override public void onDisplayAdded(int displayId){
                if(projection!=null&&glView==null){
                    mainHandler.removeCallbacks(recoverExternalRunnable);
                    mainHandler.postDelayed(recoverExternalRunnable,1200);
                }
            }
            @Override public void onDisplayRemoved(int displayId){
                if(projection!=null&&usingExternalDisplay&&displayId==externalDisplayId){
                    mainHandler.post(()->handleExternalLinkLost(displayId));
                }
            }
            @Override public void onDisplayChanged(int displayId){}
        };
        try{displayManager.registerDisplayListener(displayListener,mainHandler);}catch(Throwable ignored){}
    }

    private final Runnable recoverExternalRunnable=this::tryRecoverExternalOutput;

    private void handleExternalLinkLost(int displayId){
        if(projection==null||displayId!=externalDisplayId)return;
        broadcast("RAYNEO DISPLAY LINK RESET • Android is still running • waiting for glasses to reconnect…");
        releaseOutputOnly();
        mainHandler.removeCallbacks(recoverExternalRunnable);
        mainHandler.postDelayed(recoverExternalRunnable,1200);
    }

    private void tryRecoverExternalOutput(){
        if(projection==null||glView!=null||displayManager==null)return;
        Display external=findBestExternalDisplay(displayManager);
        if(external==null){
            mainHandler.postDelayed(recoverExternalRunnable,1500);
            return;
        }
        try{
            usingExternalDisplay=true;
            externalDisplayId=external.getDisplayId();
            Display.Mode mode=external.getMode();
            outputWidth=mode.getPhysicalWidth();outputHeight=mode.getPhysicalHeight();outputName=external.getName();nativeWideMode=isWide(outputWidth,outputHeight);
            presentation=new Presentation(this,external);
            glView=new SbsGLView(presentation.getContext(),captureWidth,captureHeight,surface->mainHandler.post(()->createVirtualDisplay(surface)));
            applyStereo();presentation.setContentView(glView);
            if(presentation.getWindow()!=null){presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);presentation.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);}
            presentation.show();glView.onResume();
            broadcast("RAYNEO RECONNECTED • SBS output restored • "+outputName+" • "+outputWidth+"×"+outputHeight+" • "+stereoSummary("STABILITY"));
        }catch(Throwable t){
            releaseOutputOnly();
            broadcast("RAYNEO RECOVERY RETRY • "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
            mainHandler.postDelayed(recoverExternalRunnable,1500);
        }
    }

    private void releaseOutputOnly(){
        try{if(virtualDisplay!=null)virtualDisplay.release();}catch(Throwable ignored){}virtualDisplay=null;
        try{if(glView!=null)glView.releaseMirror();}catch(Throwable ignored){}
        try{if(presentation!=null)presentation.dismiss();}catch(Throwable ignored){}presentation=null;
        try{if(glView!=null&&windowManager!=null)windowManager.removeViewImmediate(glView);}catch(Throwable ignored){}
        glView=null;windowManager=null;usingExternalDisplay=false;nativeWideMode=false;externalDisplayId=-1;
    }

'''
one(marker,insert+marker,'recovery methods')

one(
'        usingExternalDisplay=false;nativeWideMode=false;\n',
'''        usingExternalDisplay=false;nativeWideMode=false;externalDisplayId=-1;
        mainHandler.removeCallbacks(recoverExternalRunnable);
        if(displayManager!=null&&displayListener!=null){try{displayManager.unregisterDisplayListener(displayListener);}catch(Throwable ignored){}}
        displayListener=null;\n''',
'cleanup listener')

s=s.replace('RayNeo v0.7 AI Depth','RayNeo v0.7.1 AI Depth Stability')
s=s.replace('RayNeo-v0.7-AI-Depth-SBS','RayNeo-v0.7.1-AI-Depth-Stable-SBS')

p.write_text(s)
print('v0.7.1 service hotplug recovery transform OK')
