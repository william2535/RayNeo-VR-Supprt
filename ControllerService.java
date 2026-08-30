package com.willflood.rayneovrcontroller;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import java.nio.*;
import java.util.*;

public class ControllerService extends Service {
    public static final String ACTION_START="com.willflood.rayneovrcontroller.START";
    public static final String ACTION_STOP="com.willflood.rayneovrcontroller.STOP";
    public static final String ACTION_RECENTER="com.willflood.rayneovrcontroller.RECENTER";
    public static final String ACTION_RECALIBRATE="com.willflood.rayneovrcontroller.RECALIBRATE";
    public static final String ACTION_CONFIG="com.willflood.rayneovrcontroller.CONFIG";
    public static final String ACTION_BUTTON="com.willflood.rayneovrcontroller.BUTTON";
    public static final String ACTION_STATUS="com.willflood.rayneovrcontroller.STATUS";

    public static final int MODE_RATE=0;
    public static final int MODE_ANGLE=1;

    // Linux input-event button codes used by Android gamepads.
    public static final int BTN_A=0x130; // BTN_SOUTH
    public static final int BTN_B=0x131; // BTN_EAST
    public static final int BTN_X=0x133; // BTN_NORTH
    public static final int BTN_Y=0x134; // BTN_WEST
    public static final int BTN_L1=0x136;
    public static final int BTN_R1=0x137;
    public static final int BTN_SELECT=0x13a;
    public static final int BTN_START=0x13b;

    private static final int VID=0x1BBB, PID=0xAF50;
    private static final byte[] ENABLE=new byte[]{0x66,0x01};

    private volatile boolean running=false, recalibrate=false;
    private Thread worker;
    private UsbDeviceConnection conn;
    private UsbRequest request;
    private UsbInterface intf;
    private UsbEndpoint inEp;
    private int padFd=-1;
    private final Tracker tracker=new Tracker();
    private PowerManager.WakeLock wakeLock;

    private volatile int mode=MODE_RATE;
    private volatile int axisMode=0; // 0 Android Z/RZ, 1 Linux/Xbox RX/RY
    private volatile double maxRate=90.0;
    private volatile double fullAngle=35.0;
    private volatile boolean invertX=false, invertY=false;
    private volatile boolean rollSteer=false;
    private volatile double rollFullAngle=30.0;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        startForeground(17,makeNotification("Waiting for RayNeo"));
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RayNeoVR:Controller");
        wakeLock.acquire();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null) return START_STICKY;
        String a=intent.getAction();
        if(ACTION_STOP.equals(a)){stopController();return START_NOT_STICKY;}
        if(ACTION_RECENTER.equals(a)){tracker.recentre();neutral();sendStatus("Recentred",0,0,0,0,0,0,0);return START_STICKY;}
        if(ACTION_RECALIBRATE.equals(a)){recalibrate=true;return START_STICKY;}
        if(ACTION_BUTTON.equals(a)){
            int code=intent.getIntExtra("code",0);
            boolean down=intent.getBooleanExtra("down",false);
            if(padFd>=0 && code!=0) NativeUInput.gamepadButton(padFd,code,down);
            return START_STICKY;
        }
        if(ACTION_CONFIG.equals(a)||ACTION_START.equals(a)) applyConfig(intent);
        if(ACTION_START.equals(a)&&!running) startController();
        return START_STICKY;
    }

    private void applyConfig(Intent i){
        tracker.deadzone=i.getDoubleExtra("deadzone",tracker.deadzone);
        tracker.smooth=i.getDoubleExtra("smooth",tracker.smooth);
        mode=i.getIntExtra("mode",mode);
        axisMode=i.getIntExtra("axisMode",axisMode);
        maxRate=i.getDoubleExtra("maxRate",maxRate);
        fullAngle=i.getDoubleExtra("fullAngle",fullAngle);
        invertX=i.getBooleanExtra("invertX",invertX);
        invertY=i.getBooleanExtra("invertY",invertY);
        rollSteer=i.getBooleanExtra("rollSteer",rollSteer);
        rollFullAngle=i.getDoubleExtra("rollFullAngle",rollFullAngle);
    }

    private void startController(){running=true;worker=new Thread(this::runLoop,"RayNeo-VR-IMU");worker.start();}

    private UsbDevice findDevice(UsbManager um){
        for(UsbDevice d:um.getDeviceList().values()) if(d.getVendorId()==VID&&d.getProductId()==PID)return d;
        return null;
    }

    private void runLoop(){
        UsbManager um=(UsbManager)getSystemService(USB_SERVICE);
        UsbDevice dev=findDevice(um);
        if(dev==null){fail("RayNeo not connected");return;}
        if(!um.hasPermission(dev)){fail("USB permission missing - reopen app and press START");return;}
        conn=um.openDevice(dev);
        if(conn==null){fail("Could not open RayNeo USB device");return;}

        for(int i=0;i<dev.getInterfaceCount();i++){
            UsbInterface ui=dev.getInterface(i);
            for(int e=0;e<ui.getEndpointCount();e++){
                UsbEndpoint ep=ui.getEndpoint(e);
                if(ep.getAddress()==0x81){intf=ui;inEp=ep;break;}
            }
            if(inEp!=null)break;
        }
        if(intf==null||inEp==null){fail("Could not find HID endpoint 0x81");cleanupUsb();return;}
        if(!conn.claimInterface(intf,true)){fail("Could not claim RayNeo interface");cleanupUsb();return;}
        request=new UsbRequest();
        if(!request.initialize(conn,inEp)){fail("Could not initialise interrupt endpoint");cleanupUsb();return;}

        try{padFd=NativeUInput.openGamepad();}catch(Throwable t){padFd=-99999;}
        if(padFd<0){fail("IMU ready but /dev/uinput gamepad failed: "+padFd);cleanupUsb();return;}

        sendStatus("Calibrating - keep glasses still",0,0,0,0,0,0,0);
        double spread=calibrateFor(2.0);
        if(!running)return;
        tracker.recentre();
        updateNotification("RayNeo VR gamepad active");
        sendStatus(String.format(Locale.UK,"GAMEPAD READY • calibration %.2f",spread),0,0,0,0,0,0,0);

        long last=System.nanoTime(),lastUi=0,frames=0;
        while(running){
            if(recalibrate){
                recalibrate=false;neutral();
                sendStatus("Recalibrating - keep glasses still",0,0,0,0,frames,0,0);
                spread=calibrateFor(2.0);tracker.recentre();last=System.nanoTime();continue;
            }
            ByteBuffer frame=pollFrame(); if(frame==null)continue;
            long now=System.nanoTime(); double dt=(now-last)/1_000_000_000.0; last=now;
            if(dt<=0||dt>0.5)continue;
            double[] raw=decodeRates(frame);
            double[][] ar=tracker.update(raw,dt);
            double[] angle=ar[0],rate=ar[1]; frames++;

            double x,y;
            if(mode==MODE_ANGLE){
                x=angle[1]/Math.max(5.0,fullAngle);
                y=angle[0]/Math.max(5.0,fullAngle);
            } else {
                x=rate[1]/Math.max(10.0,maxRate);
                y=rate[0]/Math.max(10.0,maxRate);
            }
            x=clamp(x,-1,1); y=clamp(y,-1,1);
            if(!invertX)x=-x;
            if(!invertY)y=-y;
            int rx=(int)Math.round(x*32767.0);
            int ry=(int)Math.round(y*32767.0);

            int lx=0;
            if(rollSteer){
                double s=clamp(angle[2]/Math.max(5.0,rollFullAngle),-1,1);
                lx=(int)Math.round(s*32767.0);
            }
            if(padFd>=0) NativeUInput.setGamepadState(padFd,lx,0,rx,ry,0,0,0,0,axisMode);

            if(now-lastUi>100_000_000L){
                lastUi=now;
                sendStatus("GAMEPAD READY • head → right stick",angle[0],angle[1],angle[2],frame.order(ByteOrder.LITTLE_ENDIAN).getFloat(28),frames,rx,ry);
            }
        }
        cleanupAll();
    }

    private double calibrateFor(double seconds){
        ArrayList<double[]> samples=new ArrayList<>();
        long end=System.nanoTime()+(long)(seconds*1_000_000_000L);
        while(running&&System.nanoTime()<end){ByteBuffer f=pollFrame();if(f!=null)samples.add(decodeRates(f));}
        return tracker.calibrate(samples);
    }

    private ByteBuffer pollFrame(){
        if(conn==null||request==null)return null;
        int rc=conn.controlTransfer(0x21,0x09,0x0301,0,ENABLE,2,600);if(rc<0)return null;
        ByteBuffer b=ByteBuffer.allocateDirect(64);b.order(ByteOrder.LITTLE_ENDIAN);
        if(!request.queue(b))return null;
        UsbRequest done;try{done=conn.requestWait(100);}catch(Exception e){return null;}
        if(done!=request)return null;int n=b.position();if(n<32)return null;
        if((b.get(0)&0xff)!=0x99||(b.get(1)&0xff)!=0x65)return null;return b;
    }

    private double[] decodeRates(ByteBuffer f){
        f.order(ByteOrder.LITTLE_ENDIAN);
        double gx=f.getFloat(16),gy=f.getFloat(20),gz=f.getFloat(24);
        return new double[]{gx,gy+gz,gy-gz};
    }

    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private void neutral(){if(padFd>=0)NativeUInput.setGamepadState(padFd,0,0,0,0,0,0,0,0,axisMode);}

    private void sendStatus(String text,double pitch,double yaw,double roll,double temp,long frames,int rx,int ry){
        Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra("text",text);i.putExtra("pitch",pitch);i.putExtra("yaw",yaw);i.putExtra("roll",roll);i.putExtra("temp",temp);i.putExtra("frames",frames);i.putExtra("rx",rx);i.putExtra("ry",ry);sendBroadcast(i);
    }
    private void fail(String s){sendStatus(s,0,0,0,0,0,0,0);updateNotification(s);running=false;neutral();}
    private void stopController(){running=false;neutral();cleanupAll();stopForeground(true);stopSelf();}

    private synchronized void cleanupUsb(){
        try{if(request!=null)request.close();}catch(Exception ignored){} request=null;
        try{if(conn!=null&&intf!=null)conn.releaseInterface(intf);}catch(Exception ignored){}
        try{if(conn!=null)conn.close();}catch(Exception ignored){} conn=null;intf=null;inEp=null;
    }
    private synchronized void cleanupAll(){
        neutral();
        if(padFd>=0){try{NativeUInput.closeGamepad(padFd);}catch(Throwable ignored){}} padFd=-1;
        cleanupUsb();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel("rayneo_vr","RayNeo VR Controller",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}
    }
    private Notification makeNotification(String s){return new Notification.Builder(this,Build.VERSION.SDK_INT>=26?"rayneo_vr":"").setContentTitle("RayNeo VR Controller").setContentText(s).setSmallIcon(android.R.drawable.ic_media_play).build();}
    private void updateNotification(String s){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(17,makeNotification(s));}

    @Override public void onDestroy(){running=false;cleanupAll();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent i){return null;}
}
