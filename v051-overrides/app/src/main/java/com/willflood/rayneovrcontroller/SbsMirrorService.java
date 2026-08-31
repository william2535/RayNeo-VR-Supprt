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

/**
 * v0.5.1 visual layer designed around the RayNeo Air 4 Pro's own native SBS mode.
 *
 * The glasses are switched to SBS by the user (Menu + Volume Up). This service
 * does not attempt to control that hardware mode. It captures the Android game
 * output and lays it out as LEFT EYE | RIGHT EYE for whatever display Android
 * exposes after the glasses enter SBS mode.
 */
public class SbsMirrorService extends Service {
    public static final String ACTION_START="com.willflood.rayneovrcontroller.SBS_START";
    public static final String ACTION_STOP="com.willflood.rayneovrcontroller.SBS_STOP";
    public static final String ACTION_STATUS="com.willflood.rayneovrcontroller.SBS_STATUS";
    public static final String EXTRA_RESULT_CODE="resultCode";
    public static final String EXTRA_RESULT_DATA="resultData";

    private static final String CHANNEL="rayneo_sbs_v051";
    private static final int NOTIFICATION_ID=51;
    private static final double WIDE_SBS_RATIO=2.60;

    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private WindowManager windowManager;
    private Presentation presentation;
    private boolean usingExternalDisplay=false;
    private boolean nativeWideMode=false;
    private SbsGLView glView;
    private int captureWidth,captureHeight,density;
    private int outputWidth,outputHeight;
    private String outputName="unknown";

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(ACTION_STOP.equals(intent.getAction())){
            stopMirror("NATIVE SBS MIRROR: stopped");
            return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(intent.getAction()))return START_NOT_STICKY;

        startForeground(NOTIFICATION_ID,notification("Starting native-SBS mirror…"));
        int resultCode=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED);
        Intent resultData=intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if(resultCode!=Activity.RESULT_OK||resultData==null){
            broadcast("NATIVE SBS ERROR • screen capture permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }
        startMirror(resultCode,resultData);
        return START_NOT_STICKY;
    }

    private void startMirror(int resultCode,Intent resultData){
        cleanup(false);
        try{
            WindowManager defaultWm=(WindowManager)getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm=new DisplayMetrics();
            defaultWm.getDefaultDisplay().getRealMetrics(dm);
            captureWidth=Math.max(640,dm.widthPixels);
            captureHeight=Math.max(360,dm.heightPixels);
            density=Math.max(1,dm.densityDpi);

            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection=mpm.getMediaProjection(resultCode,resultData);
            if(projection==null)throw new IllegalStateException("MediaProjection unavailable");
            projectionCallback=new MediaProjection.Callback(){
                @Override public void onStop(){mainHandler.post(()->stopMirror("Screen capture ended"));}
            };
            projection.registerCallback(projectionCallback,mainHandler);

            DisplayManager displayManager=(DisplayManager)getSystemService(DISPLAY_SERVICE);
            Display external=findBestExternalDisplay(displayManager);
            if(external!=null){
                usingExternalDisplay=true;
                Display.Mode mode=external.getMode();
                outputWidth=mode.getPhysicalWidth();
                outputHeight=mode.getPhysicalHeight();
                outputName=external.getName();
                nativeWideMode=isWide(outputWidth,outputHeight);

                presentation=new Presentation(this,external);
                glView=new SbsGLView(presentation.getContext(),captureWidth,captureHeight,
                        surface->mainHandler.post(()->createVirtualDisplay(surface)));
                presentation.setContentView(glView);
                if(presentation.getWindow()!=null){
                    presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    presentation.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);
                }
                presentation.show();
                glView.onResume();
                broadcast((nativeWideMode?"NATIVE SBS WIDE MODE DETECTED":"EXTERNAL DISPLAY DETECTED • switch glasses to SBS")+
                        " • "+outputName+" • "+outputWidth+"×"+outputHeight+" • waiting for frame");
            }else{
                usingExternalDisplay=false;
                Display defaultDisplay=defaultWm.getDefaultDisplay();
                Display.Mode defaultMode=defaultDisplay.getMode();
                outputWidth=defaultMode.getPhysicalWidth();
                outputHeight=defaultMode.getPhysicalHeight();
                outputName=defaultDisplay.getName();
                nativeWideMode=isWide(outputWidth,outputHeight);

                if(!Settings.canDrawOverlays(this)){
                    throw new IllegalStateException("No separate RayNeo display detected; overlay fallback permission missing");
                }
                windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
                glView=new SbsGLView(this,captureWidth,captureHeight,
                        surface->mainHandler.post(()->createVirtualDisplay(surface)));
                int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
                int overlayFlags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        |WindowManager.LayoutParams.FLAG_SECURE;
                WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
                        type,overlayFlags,PixelFormat.OPAQUE);
                lp.gravity=Gravity.TOP|Gravity.START;
                windowManager.addView(glView,lp);
                glView.onResume();
                broadcast((nativeWideMode?"WIDE DEFAULT DISPLAY DETECTED":"NO SEPARATE RAYNEO DISPLAY • FALLBACK")+
                        " • "+outputWidth+"×"+outputHeight+" • waiting for frame");
            }
        }catch(Throwable t){
            broadcast("NATIVE SBS ERROR • "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
            cleanup(true);
            stopSelf();
        }
    }

    private Display findBestExternalDisplay(DisplayManager dm){
        Display best=null;
        double bestScore=-1;
        if(dm==null)return null;
        for(Display d:dm.getDisplays()){
            if(d==null||d.getDisplayId()==Display.DEFAULT_DISPLAY)continue;
            try{
                Display.Mode m=d.getMode();
                int w=m.getPhysicalWidth(),h=m.getPhysicalHeight();
                double ratio=Math.max(w,h)/(double)Math.max(1,Math.min(w,h));
                double score=(isWide(w,h)?1_000_000_000d:0d)+(w*(double)h)+(ratio*1000d);
                if(score>bestScore){bestScore=score;best=d;}
            }catch(Throwable ignored){}
        }
        return best;
    }

    private static boolean isWide(int w,int h){
        int longSide=Math.max(w,h),shortSide=Math.max(1,Math.min(w,h));
        return longSide/(double)shortSide>=WIDE_SBS_RATIO && longSide>=2500;
    }

    private void createVirtualDisplay(Surface surface){
        if(projection==null||virtualDisplay!=null||surface==null)return;
        try{
            virtualDisplay=projection.createVirtualDisplay(
                    "RayNeo-v0.5.1-Native-SBS",captureWidth,captureHeight,density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,null,mainHandler);
            String mode=nativeWideMode?"NATIVE WIDE SBS":"SBS LAYOUT";
            String route=usingExternalDisplay?"EXTERNAL DISPLAY":"SECURE OVERLAY FALLBACK";
            String msg=mode+" ACTIVE • MONO L|R • "+route+" • output "+outputWidth+"×"+outputHeight;
            broadcast(msg);
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null)nm.notify(NOTIFICATION_ID,notification(msg));
        }catch(Throwable t){
            broadcast("NATIVE SBS DISPLAY ERROR • "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
            stopMirror("Native SBS display failed");
        }
    }

    private Notification notification(String text){
        Intent stop=new Intent(this,SbsMirrorService.class).setAction(ACTION_STOP);
        PendingIntent pi=PendingIntent.getService(this,52,stop,
                PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder b=Build.VERSION.SDK_INT>=26?
                new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setContentTitle("RayNeo v0.5.1 Native SBS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"STOP SBS",pi).build())
                .build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"RayNeo Native SBS",NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void broadcast(String text){
        Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",text);
        sendBroadcast(i);
    }

    private static String safe(String s){return s==null?"":s;}

    private void stopMirror(String msg){
        broadcast(msg);
        cleanup(true);
        stopForeground(true);
        stopSelf();
    }

    private void cleanup(boolean stopProjection){
        try{if(virtualDisplay!=null)virtualDisplay.release();}catch(Throwable ignored){}
        virtualDisplay=null;
        try{if(glView!=null)glView.releaseMirror();}catch(Throwable ignored){}
        try{if(presentation!=null)presentation.dismiss();}catch(Throwable ignored){}
        presentation=null;
        try{if(glView!=null&&windowManager!=null)windowManager.removeViewImmediate(glView);}catch(Throwable ignored){}
        glView=null;
        windowManager=null;
        usingExternalDisplay=false;
        nativeWideMode=false;
        if(projection!=null){
            try{if(projectionCallback!=null)projection.unregisterCallback(projectionCallback);}catch(Throwable ignored){}
            if(stopProjection)try{projection.stop();}catch(Throwable ignored){}
        }
        projectionCallback=null;
        projection=null;
    }

    @Override public void onDestroy(){cleanup(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
