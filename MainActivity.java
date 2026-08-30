package com.willflood.rayneovrcontroller;

import android.app.*;
import android.content.*;
import android.hardware.input.InputManager;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
import java.util.Locale;

public class MainActivity extends Activity implements InputManager.InputDeviceListener {
    private static final int VID=0x1BBB,PID=0xAF50;
    private static final String USB_PERMISSION="com.willflood.rayneovrcontroller.USB_PERMISSION";
    private TextView status,live,androidInput,deviceInfo,rateLabel,angleLabel,deadLabel,smoothLabel;
    private SeekBar maxRate,fullAngle,dead,smooth;
    private Spinner mode,axisMode;
    private Switch invX,invY,rollSteer;
    private InputManager inputManager;

    private final BroadcastReceiver usbReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(USB_PERMISSION.equals(i.getAction())){UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&d!=null)startController();else status.setText("USB permission denied");}
    }};
    private final BroadcastReceiver statusReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String t=i.getStringExtra("text");if(t!=null)status.setText(t);
        double p=i.getDoubleExtra("pitch",0),y=i.getDoubleExtra("yaw",0),r=i.getDoubleExtra("roll",0),temp=i.getDoubleExtra("temp",0);long f=i.getLongExtra("frames",0);int rx=i.getIntExtra("rx",0),ry=i.getIntExtra("ry",0);
        live.setText(String.format(Locale.UK,"IMU PITCH %+7.2f°   YAW %+7.2f°\nROLL      %+7.2f°   TEMP %4.1f°C\nOUTPUT RIGHT X %+6d\nOUTPUT RIGHT Y %+6d\nFRAMES %d",p,y,r,temp,rx,ry,f));
        refreshDeviceInfo();
    }};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        registerReceiver(usbReceiver,new IntentFilter(USB_PERMISSION));registerReceiver(statusReceiver,new IntentFilter(ControllerService.ACTION_STATUS));
        inputManager=(InputManager)getSystemService(INPUT_SERVICE);inputManager.registerInputDeviceListener(this,null);
        buildUi();updateUsbState();refreshDeviceInfo();
    }

    private void buildUi(){
        ScrollView sv=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,24,28,40);sv.addView(root);
        TextView title=new TextView(this);title.setText("RAYNEO VR CONTROLLER LAB");title.setTextSize(27);title.setTextColor(Color.BLACK);title.setTypeface(null,1);root.addView(title);
        TextView sub=new TextView(this);sub.setText("Air 4 Pro IMU → real Android virtual gamepad\nSeparate project: this does not modify RayNeo Spatial");sub.setTextSize(14);sub.setPadding(0,5,0,16);root.addView(sub);
        status=new TextView(this);status.setText("Checking RayNeo…");status.setTextSize(16);status.setPadding(14,14,14,14);status.setBackgroundColor(0xffe4e7e0);root.addView(status,new LinearLayout.LayoutParams(-1,-2));

        deviceInfo=new TextView(this);deviceInfo.setText("ANDROID GAMEPAD: not detected yet");deviceInfo.setTextSize(14);deviceInfo.setPadding(12,10,12,10);LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.setMargins(0,10,0,0);root.addView(deviceInfo,dp);
        live=new TextView(this);live.setText("IMU PITCH 0.00°   YAW 0.00°\nROLL 0.00°\nOUTPUT RIGHT X 0\nOUTPUT RIGHT Y 0\nFRAMES 0");live.setTextSize(16);live.setTypeface(android.graphics.Typeface.MONOSPACE);live.setTextColor(0xff77e49e);live.setBackgroundColor(0xff16241c);live.setPadding(16,16,16,16);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,12,0,8);root.addView(live,lp);
        androidInput=new TextView(this);androidInput.setText("ANDROID INPUT EVENT\nWaiting for joystick events…");androidInput.setTextSize(15);androidInput.setTypeface(android.graphics.Typeface.MONOSPACE);androidInput.setPadding(12,12,12,12);androidInput.setBackgroundColor(0xffeeeeee);root.addView(androidInput,new LinearLayout.LayoutParams(-1,-2));

        TextView ml=new TextView(this);ml.setText("HEAD OUTPUT MODE");ml.setTypeface(null,1);ml.setPadding(0,14,0,2);root.addView(ml);
        mode=new Spinner(this);String[] modes={"GYRO RATE → RIGHT STICK (recommended)","HEAD ANGLE → RIGHT STICK"};mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes));root.addView(mode);

        TextView al=new TextView(this);al.setText("RIGHT-STICK COMPATIBILITY");al.setTypeface(null,1);al.setPadding(0,10,0,2);root.addView(al);
        axisMode=new Spinner(this);String[] axes={"Android common: Z / RZ","Xbox/Linux style: RX / RY"};axisMode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,axes));root.addView(axisMode);

        invX=new Switch(this);invX.setText("Invert horizontal");root.addView(invX);
        invY=new Switch(this);invY.setText("Invert vertical");root.addView(invY);
        rollSteer=new Switch(this);rollSteer.setText("Experimental: head roll → LEFT stick steering");root.addView(rollSteer);

        rateLabel=label(root,"Full right-stick at 90°/s");maxRate=bar(root,20,240,90);
        angleLabel=label(root,"Full right-stick at 35° head angle");fullAngle=bar(root,10,90,35);
        deadLabel=label(root,"Deadzone: 2.0°/s");dead=bar(root,0,50,20);
        smoothLabel=label(root,"Smoothing: 0.35");smooth=bar(root,0,90,35);

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);Button start=button("START GAMEPAD",0xff2f7d4a);Button stop=button("STOP",0xffa43f35);actions.addView(start,new LinearLayout.LayoutParams(0,-2,1));actions.addView(stop,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);
        LinearLayout actions2=new LinearLayout(this);actions2.setOrientation(LinearLayout.HORIZONTAL);Button rec=button("RECENTRE",0xff59645b);Button cal=button("RECALIBRATE",0xff59645b);actions2.addView(rec,new LinearLayout.LayoutParams(0,-2,1));actions2.addView(cal,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions2);

        TextView bt=new TextView(this);bt.setText("BUTTON OUTPUT TEST");bt.setTypeface(null,1);bt.setPadding(0,18,0,6);root.addView(bt);
        LinearLayout row1=new LinearLayout(this);row1.setOrientation(LinearLayout.HORIZONTAL);addPadButton(row1,"A",ControllerService.BTN_A);addPadButton(row1,"B",ControllerService.BTN_B);addPadButton(row1,"X",ControllerService.BTN_X);addPadButton(row1,"Y",ControllerService.BTN_Y);root.addView(row1);
        LinearLayout row2=new LinearLayout(this);row2.setOrientation(LinearLayout.HORIZONTAL);addPadButton(row2,"L1",ControllerService.BTN_L1);addPadButton(row2,"R1",ControllerService.BTN_R1);addPadButton(row2,"SELECT",ControllerService.BTN_SELECT);addPadButton(row2,"START",ControllerService.BTN_START);root.addView(row2);

        TextView note=new TextView(this);note.setText("FIRST GOAL: when GAMEPAD READY appears, the box above should change to ANDROID GAMEPAD DETECTED and this screen should receive joystick events while it is focused. Then open a controller-compatible game/streaming client; Android will send the same virtual controller to that app. This is conventional controller emulation, not yet true OpenXR headset-pose injection.");note.setTextSize(13);note.setPadding(0,18,0,0);root.addView(note);
        setContentView(sv);

        start.setOnClickListener(v->ensurePermissionAndStart());stop.setOnClickListener(v->sendAction(ControllerService.ACTION_STOP));rec.setOnClickListener(v->sendAction(ControllerService.ACTION_RECENTER));cal.setOnClickListener(v->sendAction(ControllerService.ACTION_RECALIBRATE));
        android.widget.AdapterView.OnItemSelectedListener spin=new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){sendConfig();}public void onNothingSelected(android.widget.AdapterView<?> p){}};mode.setOnItemSelectedListener(spin);axisMode.setOnItemSelectedListener(spin);
        android.widget.CompoundButton.OnCheckedChangeListener sw=(b1,c)->sendConfig();invX.setOnCheckedChangeListener(sw);invY.setOnCheckedChangeListener(sw);rollSteer.setOnCheckedChangeListener(sw);
        SeekBar.OnSeekBarChangeListener sl=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean f){updateLabels();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){sendConfig();}};maxRate.setOnSeekBarChangeListener(sl);fullAngle.setOnSeekBarChangeListener(sl);dead.setOnSeekBarChangeListener(sl);smooth.setOnSeekBarChangeListener(sl);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){
        if((e.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK && e.getAction()==MotionEvent.ACTION_MOVE){
            float x=e.getAxisValue(MotionEvent.AXIS_X),y=e.getAxisValue(MotionEvent.AXIS_Y);
            float z=e.getAxisValue(MotionEvent.AXIS_Z),rz=e.getAxisValue(MotionEvent.AXIS_RZ);
            float rx=e.getAxisValue(MotionEvent.AXIS_RX),ry=e.getAxisValue(MotionEvent.AXIS_RY);
            androidInput.setText(String.format(Locale.UK,"ANDROID INPUT EVENT\nLEFT X/Y  %+5.2f  %+5.2f\nZ/RZ      %+5.2f  %+5.2f\nRX/RY     %+5.2f  %+5.2f",x,y,z,rz,rx,ry));
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        if((e.getSource()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD){androidInput.setText("ANDROID GAMEPAD KEY\nkeyCode="+e.getKeyCode()+"  "+(e.getAction()==KeyEvent.ACTION_DOWN?"DOWN":"UP"));return true;}
        return super.dispatchKeyEvent(e);
    }

    private void addPadButton(LinearLayout row,String text,int code){Button b=button(text,0xff355f8a);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN){sendButton(code,true);return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){sendButton(code,false);return true;}return true;});}
    private TextView label(LinearLayout r,String t){TextView v=new TextView(this);v.setText(t);v.setTextSize(14);v.setPadding(0,12,0,0);r.addView(v);return v;}
    private SeekBar bar(LinearLayout r,int min,int max,int value){SeekBar b=new SeekBar(this);if(Build.VERSION.SDK_INT>=26)b.setMin(min);b.setMax(max);b.setProgress(value);r.addView(b);return b;}
    private Button button(String t,int c){Button b=new Button(this);b.setText(t);b.setTextColor(Color.WHITE);b.setBackgroundColor(c);return b;}
    private void updateLabels(){rateLabel.setText("Full right-stick at "+maxRate.getProgress()+"°/s");angleLabel.setText("Full right-stick at "+fullAngle.getProgress()+"° head angle");deadLabel.setText(String.format(Locale.UK,"Deadzone: %.1f°/s",dead.getProgress()/10.0));smoothLabel.setText(String.format(Locale.UK,"Smoothing: %.2f",smooth.getProgress()/100.0));}

    private UsbDevice findDevice(){UsbManager um=(UsbManager)getSystemService(USB_SERVICE);for(UsbDevice d:um.getDeviceList().values())if(d.getVendorId()==VID&&d.getProductId()==PID)return d;return null;}
    private void updateUsbState(){status.setText(findDevice()==null?"RayNeo not connected":"RayNeo Air 4 Pro detected • press START GAMEPAD");}
    private void ensurePermissionAndStart(){UsbManager um=(UsbManager)getSystemService(USB_SERVICE);UsbDevice d=findDevice();if(d==null){status.setText("Plug in the RayNeo glasses first");return;}if(um.hasPermission(d)){startController();return;}PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent(USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));um.requestPermission(d,pi);status.setText("Waiting for USB permission…");}
    private Intent configured(String action){return new Intent(this,ControllerService.class).setAction(action).putExtra("mode",mode.getSelectedItemPosition()).putExtra("axisMode",axisMode.getSelectedItemPosition()).putExtra("maxRate",(double)maxRate.getProgress()).putExtra("fullAngle",(double)fullAngle.getProgress()).putExtra("deadzone",dead.getProgress()/10.0).putExtra("smooth",smooth.getProgress()/100.0).putExtra("invertX",invX.isChecked()).putExtra("invertY",invY.isChecked()).putExtra("rollSteer",rollSteer.isChecked()).putExtra("rollFullAngle",30.0);}
    private void startController(){Intent i=configured(ControllerService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);status.setText("Starting… keep the glasses still");}
    private void sendConfig(){if(mode==null||axisMode==null)return;try{startService(configured(ControllerService.ACTION_CONFIG));}catch(Exception ignored){}}
    private void sendAction(String action){try{startService(new Intent(this,ControllerService.class).setAction(action));}catch(Exception ignored){}}
    private void sendButton(int code,boolean down){try{startService(new Intent(this,ControllerService.class).setAction(ControllerService.ACTION_BUTTON).putExtra("code",code).putExtra("down",down));}catch(Exception ignored){}}

    private void refreshDeviceInfo(){
        if(inputManager==null||deviceInfo==null)return;String found=null;
        for(int id:InputDevice.getDeviceIds()){InputDevice d=InputDevice.getDevice(id);if(d!=null&&d.getName()!=null&&d.getName().contains("RayNeo VR Gamepad")){found="ANDROID GAMEPAD DETECTED • id "+id+"\nsources 0x"+Integer.toHexString(d.getSources());break;}}
        deviceInfo.setText(found!=null?found:"ANDROID GAMEPAD: not detected yet");
    }
    @Override public void onInputDeviceAdded(int id){refreshDeviceInfo();}
    @Override public void onInputDeviceRemoved(int id){refreshDeviceInfo();}
    @Override public void onInputDeviceChanged(int id){refreshDeviceInfo();}

    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);updateUsbState();}
    @Override protected void onDestroy(){try{if(inputManager!=null)inputManager.unregisterInputDeviceListener(this);}catch(Exception ignored){}try{unregisterReceiver(usbReceiver);}catch(Exception ignored){}try{unregisterReceiver(statusReceiver);}catch(Exception ignored){}super.onDestroy();}
}
