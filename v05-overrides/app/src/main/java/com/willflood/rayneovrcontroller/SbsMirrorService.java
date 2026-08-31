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
 * Experimental v0.5 visual layer.
 * Captures the handheld/default display with MediaProjection and prefers to
 * render the duplicated SBS output on a separate RayNeo/presentation display.
 */
public class SbsMirrorService extends Service {
    public static final String ACTION_START="com.willflood.rayneovrcontroller.SBS_START";
    public static final String ACTION_STOP="com.willflood.rayneovrcontroller.SBS_STOP";
    public static final String ACTION_STATUS="com.willflood.rayneovrcontroller.SBS_STATUS";
    public static final String EXTRA_RESULT_CODE="resultCode";
    public static final String EXTRA_RESULT_DATA="resultData";

    private static final String CHANNEL="rayneo_sbs_v05";
    private static final int NOTIFICATION_ID=51;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private WindowManager windowManager;
    private Presentation presentation;
    private boolean usingExternalDisplay=false;
    private SbsGLView glView;
    private int width,height,density;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(ACTION_STOP.equals(intent.getAction())){stopMirror("SBS mirror stopped");return START_NOT_STICKY;}
        if(!ACTION_START.equals(intent.getAction()))return START_NOT_STICKY;

        startForeground(NOTIFICATION_ID,notification("Starting SBS mirror…"));
        int resultCode=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED);
        Intent resultData=intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if(resultCode!=Activity.RESULT_OK||resultData==null){broadcast("SBS ERROR • screen capture permission missing");stopSelf();return START_NOT_STICKY;}
        startMirror(resultCode,resultData);
        return START_NOT_STICKY;
    }

    private void startMirror(int resultCode,Intent resultData){
        cleanup(false);
        try{
            DisplayMetrics dm=new DisplayMetrics();
            WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            width=Math.max(640,dm.widthPixels);height=Math.max(360,dm.heightPixels);density=dm.densityDpi;

            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection=mpm.getMediaProjection(resultCode,resultData);
            if(projection==null)throw new IllegalStateException("MediaProjection unavailable");
            projectionCallback=new MediaProjection.Callback(){@Override public void onStop(){mainHandler.post(()->stopMirror("Screen capture ended"));}};
            projection.registerCallback(projectionCallback,mainHandler);

            DisplayManager displayManager=(DisplayManager)getSystemService(DISPLAY_SERVICE);
            Display external=null;
            for(Display d:displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)){
                if(d!=null && d.getDisplayId()!=Display.DEFAULT_DISPLAY){external=d;break;}
            }
            if(external!=null){
                usingExternalDisplay=true;
                presentation=new Presentation(this,external);
                glView=new SbsGLView(presentation.getContext(),width,height,surface->mainHandler.post(()->createVirtualDisplay(surface)));
                presentation.setContentView(glView);
                presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                presentation.show();
                glView.onResume();
                broadcast("SBS STARTING • external display "+external.getName()+" • waiting for frame");
            }else{
                usingExternalDisplay=false;
                if(!Settings.canDrawOverlays(this))throw new IllegalStateException("No external presentation display; overlay fallback permission missing");
                windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
                glView=new SbsGLView(this,width,height,surface->mainHandler.post(()->createVirtualDisplay(surface)));
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
                broadcast("SBS STARTING • overlay fallback • waiting for frame");
            }
        }catch(Throwable t){
            broadcast("SBS ERROR • "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
            cleanup(true);stopSelf();
        }
    }

    private void createVirtualDisplay(android.view.Surface surface){
        if(projection==null||virtualDisplay!=null||surface==null)return;
        try{
            virtualDisplay=projection.createVirtualDisplay(
                    "RayNeo-v0.5-SBS",width,height,density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,null,mainHandler);
            String msg="SBS MIRROR ACTIVE • MONO left/right • "+(usingExternalDisplay?"EXTERNAL DISPLAY":"OVERLAY FALLBACK")+" • "+width+"×"+height;
            broadcast(msg);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification(msg));
        }catch(Throwable t){broadcast("SBS DISPLAY ERROR • "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));stopMirror("SBS display failed");}
    }

    private Notification notification(String text){
        Intent stop=new Intent(this,SbsMirrorService.class).setAction(ACTION_STOP);
        PendingIntent pi=PendingIntent.getService(this,52,stop,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setContentTitle("RayNeo v0.5 SBS Mirror").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"STOP SBS",pi).build()).build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel(CHANNEL,"RayNeo SBS Mirror",NotificationManager.IMPORTANCE_LOW));}
    }

    private void broadcast(String text){Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text",text);sendBroadcast(i);}
    private static String safe(String s){return s==null?"":s;}

    private void stopMirror(String msg){broadcast(msg);cleanup(true);stopForeground(true);stopSelf();}

    private void cleanup(boolean stopProjection){
        try{if(virtualDisplay!=null)virtualDisplay.release();}catch(Throwable ignored){}virtualDisplay=null;
        try{if(glView!=null)glView.releaseMirror();}catch(Throwable ignored){}
        try{if(presentation!=null)presentation.dismiss();}catch(Throwable ignored){}presentation=null;
        try{if(glView!=null&&windowManager!=null)windowManager.removeViewImmediate(glView);}catch(Throwable ignored){}
        glView=null;windowManager=null;usingExternalDisplay=false;
        if(projection!=null){
            try{if(projectionCallback!=null)projection.unregisterCallback(projectionCallback);}catch(Throwable ignored){}
            if(stopProjection)try{projection.stop();}catch(Throwable ignored){}
        }
        projectionCallback=null;projection=null;
    }

    @Override public void onDestroy(){cleanup(true);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
