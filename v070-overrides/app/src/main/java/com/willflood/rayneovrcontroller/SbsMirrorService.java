package com.willflood.rayneovrcontroller;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;

/** v0.7 native-SBS service with live MiDaS depth controls. */
public class SbsMirrorService extends Service {
    public static final String ACTION_START="com.willflood.rayneovrcontroller.SBS_START";
    public static final String ACTION_STOP="com.willflood.rayneovrcontroller.SBS_STOP";
    public static final String ACTION_CONFIG="com.willflood.rayneovrcontroller.SBS_CONFIG";
    public static final String ACTION_STATUS="com.willflood.rayneovrcontroller.SBS_STATUS";
    public static final String EXTRA_RESULT_CODE="resultCode";
    public static final String EXTRA_RESULT_DATA="resultData";
    public static final String EXTRA_SEPARATION_PX="separationPx";
    public static final String EXTRA_ZOOM_PERCENT="zoomPercent";
    public static final String EXTRA_VERTICAL_PX="verticalTrimPx";
    public static final String EXTRA_SWAP_EYES="swapEyes";
    public static final String EXTRA_AI_DEPTH_PERCENT="aiDepthPercent";
    public static final String EXTRA_CONVERGENCE_PERCENT="convergencePercent";

    private static final String CHANNEL="rayneo_sbs_v070";
    private static final int NOTIFICATION_ID=70;
    private static final double WIDE_SBS_RATIO=2.60;

    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private WindowManager windowManager;
    private Presentation presentation;
    private boolean usingExternalDisplay=false,nativeWideMode=false;
    private SbsGLView glView;
    private int captureWidth,captureHeight,density,outputWidth,outputHeight;
    private String outputName="unknown";

    private float separationPx=0f,zoomPercent=100f,verticalTrimPx=0f,aiDepthPercent=0f,convergencePercent=50f;
    private boolean swapEyes=false;

    @Override public void onCreate(){super.onCreate();createChannel();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        if(ACTION_STOP.equals(action)){stopMirror("AI DEPTH SBS: stopped");return START_NOT_STICKY;}
        if(ACTION_CONFIG.equals(action)){
            readStereo(intent);applyStereo();broadcast(stereoSummary("AI DEPTH LIVE"));return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(action))return START_NOT_STICKY;
        readStereo(intent);
        startForeground(NOTIFICATION_ID,notification("Starting v0.7 AI depth SBS…"));
        int resultCode=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED);
        Intent resultData=intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if(resultCode!=Activity.RESULT_OK||resultData==null){broadcast("AI DEPTH SBS ERROR • screen capture permission missing");stopSelf();return START_NOT_STICKY;}
        startMirror(resultCode,resultData);
        return START_NOT_STICKY;
    }

    private void readStereo(Intent i){
        separationPx=clamp(i.getFloatExtra(EXTRA_SEPARATION_PX,separationPx),0f,24f);
        zoomPercent=clamp(i.getFloatExtra(EXTRA_ZOOM_PERCENT,zoomPercent),100f,115f);
        verticalTrimPx=clamp(i.getFloatExtra(EXTRA_VERTICAL_PX,verticalTrimPx),-8f,8f);
        swapEyes=i.getBooleanExtra(EXTRA_SWAP_EYES,swapEyes);
        aiDepthPercent=clamp(i.getFloatExtra(EXTRA_AI_DEPTH_PERCENT,aiDepthPercent),0f,100f);
        convergencePercent=clamp(i.getFloatExtra(EXTRA_CONVERGENCE_PERCENT,convergencePercent),5f,95f);
    }

    private void applyStereo(){SbsGLView v=glView;if(v!=null)v.setStereoSettings(separationPx,zoomPercent,verticalTrimPx,swapEyes,aiDepthPercent,convergencePercent);}

    private void startMirror(int resultCode,Intent resultData){
        cleanup(false);
        try{
            WindowManager defaultWm=(WindowManager)getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm=new DisplayMetrics();defaultWm.getDefaultDisplay().getRealMetrics(dm);
            captureWidth=Math.max(640,dm.widthPixels);captureHeight=Math.max(360,dm.heightPixels);density=Math.max(1,dm.densityDpi);

            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection=mpm.getMediaProjection(resultCode,resultData);
            if(projection==null)throw new IllegalStateException("MediaProjection unavailable");
            projectionCallback=new MediaProjection.Callback(){@Override public void onStop(){mainHandler.post(()->stopMirror("Screen capture ended"));}};
            projection.registerCallback(projectionCallback,mainHandler);

            DisplayManager displayManager=(DisplayManager)getSystemService(DISPLAY_SERVICE);
            Display external=findBestExternalDisplay(displayManager);
            if(external!=null){
                usingExternalDisplay=true;Display.Mode mode=external.getMode();outputWidth=mode.getPhysicalWidth();outputHeight=mode.getPhysicalHeight();outputName=external.getName();nativeWideMode=isWide(outputWidth,outputHeight);
                presentation=new Presentation(this,external);
                glView=new SbsGLView(presentation.getContext(),captureWidth,captureHeight,surface->mainHandler.post(()->createVirtualDisplay(surface)));
                applyStereo();presentation.setContentView(glView);
                if(presentation.getWindow()!=null){presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);presentation.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);}
                presentation.show();glView.onResume();
                broadcast((nativeWideMode?"NATIVE SBS WIDE MODE DETECTED":"EXTERNAL DISPLAY DETECTED • switch glasses to SBS")+" • "+outputName+" • "+outputWidth+"×"+outputHeight+" • "+stereoSummary("ready"));
            }else{
                usingExternalDisplay=false;Display defaultDisplay=defaultWm.getDefaultDisplay();Display.Mode defaultMode=defaultDisplay.getMode();outputWidth=defaultMode.getPhysicalWidth();outputHeight=defaultMode.getPhysicalHeight();outputName=defaultDisplay.getName();nativeWideMode=isWide(outputWidth,outputHeight);
                if(!Settings.canDrawOverlays(this))throw new IllegalStateException("No separate RayNeo display detected; overlay fallback permission missing");
                windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
                glView=new SbsGLView(this,captureWidth,captureHeight,surface->mainHandler.post(()->createVirtualDisplay(surface)));applyStereo();
                int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
                int overlayFlags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS|WindowManager.LayoutParams.FLAG_SECURE;
                WindowManager.LayoutParams lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,type,overlayFlags,PixelFormat.OPAQUE);lp.gravity=Gravity.TOP|Gravity.START;
                windowManager.addView(glView,lp);glView.onResume();
                broadcast((nativeWideMode?"WIDE DEFAULT DISPLAY DETECTED":"NO SEPARATE RAYNEO DISPLAY • FALLBACK")+" • "+outputWidth+"×"+outputHeight+" • "+stereoSummary("ready"));
            }
        }catch(Throwable t){broadcast("AI DEPTH SBS ERROR • "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));cleanup(true);stopSelf();}
    }

    private Display findBestExternalDisplay(DisplayManager dm){
        Display best=null;double bestScore=-1;if(dm==null)return null;
        for(Display d:dm.getDisplays()){
            if(d==null||d.getDisplayId()==Display.DEFAULT_DISPLAY)continue;
            try{Display.Mode m=d.getMode();int w=m.getPhysicalWidth(),h=m.getPhysicalHeight();double ratio=Math.max(w,h)/(double)Math.max(1,Math.min(w,h));double score=(isWide(w,h)?1_000_000_000d:0d)+(w*(double)h)+(ratio*1000d);if(score>bestScore){bestScore=score;best=d;}}catch(Throwable ignored){}
        }
        return best;
    }

    private static boolean isWide(int w,int h){int longSide=Math.max(w,h),shortSide=Math.max(1,Math.min(w,h));return longSide/(double)shortSide>=WIDE_SBS_RATIO&&longSide>=2500;}

    private void createVirtualDisplay(Surface surface){
        if(projection==null||virtualDisplay!=null||surface==null)return;
        try{
            virtualDisplay=projection.createVirtualDisplay("RayNeo-v0.7-AI-Depth-SBS",captureWidth,captureHeight,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,mainHandler);
            String mode=nativeWideMode?"NATIVE WIDE SBS":"SBS LAYOUT",route=usingExternalDisplay?"EXTERNAL DISPLAY":"SECURE OVERLAY FALLBACK";
            String msg=mode+" ACTIVE • "+route+" • output "+outputWidth+"×"+outputHeight+" • "+stereoSummary("AI DEPTH");broadcast(msg);
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm!=null)nm.notify(NOTIFICATION_ID,notification(msg));
        }catch(Throwable t){broadcast("AI DEPTH DISPLAY ERROR • "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));stopMirror("AI depth display failed");}
    }

    private String stereoSummary(String prefix){
        String base=separationPx<=0.01f?"BASE 0px":String.format(java.util.Locale.UK,"BASE %.0fpx",separationPx);
        String ai=aiDepthPercent<=0.1f?"AI DEPTH OFF":String.format(java.util.Locale.UK,"AI DEPTH %.0f%% • convergence %.0f%%",aiDepthPercent,convergencePercent);
        return prefix+" • "+base+" • "+ai+" • zoom "+Math.round(zoomPercent)+"%"+(swapEyes?" • EYES SWAPPED":"");
    }

    private Notification notification(String text){
        Intent stop=new Intent(this,SbsMirrorService.class).setAction(ACTION_STOP);
        PendingIntent pi=PendingIntent.getService(this,72,stop,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setContentTitle("RayNeo v0.7 AI Depth").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"STOP SBS",pi).build()).build();
    }

    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm!=null)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"RayNeo AI Depth SBS",NotificationManager.IMPORTANCE_LOW));}}
    private void broadcast(String text){sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",text));}
    private static String safe(String s){return s==null?"":s;}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private void stopMirror(String msg){broadcast(msg);cleanup(true);stopForeground(true);stopSelf();}

    private void cleanup(boolean stopProjection){
        try{if(virtualDisplay!=null)virtualDisplay.release();}catch(Throwable ignored){}virtualDisplay=null;
        try{if(glView!=null)glView.releaseMirror();}catch(Throwable ignored){}
        try{if(presentation!=null)presentation.dismiss();}catch(Throwable ignored){}presentation=null;
        try{if(glView!=null&&windowManager!=null)windowManager.removeViewImmediate(glView);}catch(Throwable ignored){}glView=null;windowManager=null;
        usingExternalDisplay=false;nativeWideMode=false;
        if(projection!=null){try{if(projectionCallback!=null)projection.unregisterCallback(projectionCallback);}catch(Throwable ignored){}if(stopProjection)try{projection.stop();}catch(Throwable ignored){}}
        projectionCallback=null;projection=null;
    }

    @Override public void onDestroy(){cleanup(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
