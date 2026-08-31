package com.willflood.rayneovrcontroller;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
import java.util.*;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity implements InputManager.InputDeviceListener {
    private static final int VID=0x1BBB,PID=0xAF50, SHIZUKU_REQ=3002;
    private static final String USB_PERMISSION="com.willflood.rayneovrcontroller.USB_PERMISSION";
    private static final String PREFS="rayneo_daily_driver";

    private TextView status,live,androidInput,deviceInfo,shizukuStatus,bridgeStatus;
    private TextView rateLabel,angleLabel,deadLabel,smoothLabel;
    private SeekBar maxRate,fullAngle,dead,smooth;
    private Spinner mode,axisMode,physicalSpinner,profileSpinner;
    private Switch invX,invY,rollSteer,passthrough,headMix,dailyDriver;
    private InputManager inputManager;
    private SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean restoringSettings=false,bridgeBinding=false,panicHold=false;
    private final ArrayList<String> physicalPaths=new ArrayList<>(),physicalNames=new ArrayList<>();

    private final BroadcastReceiver usbReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(USB_PERMISSION.equals(i.getAction())){UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&d!=null)startController();else status.setText("USB permission denied");}}};
    private final BroadcastReceiver statusReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String t=i.getStringExtra("text");if(t!=null)status.setText(t);
        double p=i.getDoubleExtra("pitch",0),y=i.getDoubleExtra("yaw",0),r=i.getDoubleExtra("roll",0),temp=i.getDoubleExtra("temp",0);long f=i.getLongExtra("frames",0);
        int rx=i.getIntExtra("rx",0),ry=i.getIntExtra("ry",0),prx=i.getIntExtra("prx",0),pry=i.getIntExtra("pry",0),hrx=i.getIntExtra("hrx",0),hry=i.getIntExtra("hry",0);
        live.setText(String.format(Locale.UK,"IMU PITCH %+7.2f°   YAW %+7.2f°\nROLL      %+7.2f°   TEMP %4.1f°C\nHEAD R     %+6d  %+6d\nPHYS R     %+6d  %+6d\nFINAL R    %+6d  %+6d\nFRAMES %d",p,y,r,temp,hrx,hry,prx,pry,rx,ry,f));
        refreshDeviceInfo();
    }};

    private final Shizuku.OnBinderReceivedListener binderListener=()->runOnUiThread(()->{updateShizukuState();dailyDriverKick();});
    private final Shizuku.OnBinderDeadListener binderDeadListener=()->runOnUiThread(()->{bridgeBinding=false;PhysicalBridgeManager.bridge=null;PhysicalBridgeManager.clearGrabState();if(shizukuStatus!=null)shizukuStatus.setText("SHIZUKU: service stopped • auto will reconnect");handler.postDelayed(this::dailyDriverKick,1200);});
    private final Shizuku.OnRequestPermissionResultListener permissionListener=(code,result)->{if(code==SHIZUKU_REQ)runOnUiThread(()->{if(result==PackageManager.PERMISSION_GRANTED)bindInputBridge();else shizukuStatus.setText("SHIZUKU: permission denied");});};

    private final ServiceConnection bridgeConnection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){bridgeBinding=false;PhysicalBridgeManager.bridge=IInputBridge.Stub.asInterface(service);runOnUiThread(()->{try{shizukuStatus.setText("SHIZUKU BRIDGE READY • uid "+PhysicalBridgeManager.bridge.serviceUid());}catch(Exception e){shizukuStatus.setText("SHIZUKU BRIDGE READY");}findPhysicalControllers();});}
        @Override public void onServiceDisconnected(ComponentName name){bridgeBinding=false;PhysicalBridgeManager.bridge=null;PhysicalBridgeManager.clearGrabState();runOnUiThread(()->{shizukuStatus.setText("SHIZUKU BRIDGE DISCONNECTED • auto retrying");handler.postDelayed(MainActivity.this::dailyDriverKick,1200);});}
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        registerReceiver(usbReceiver,new IntentFilter(USB_PERMISSION));registerReceiver(statusReceiver,new IntentFilter(ControllerService.ACTION_STATUS));
        inputManager=(InputManager)getSystemService(INPUT_SERVICE);inputManager.registerInputDeviceListener(this,null);
        Shizuku.addBinderReceivedListener(binderListener);Shizuku.addBinderDeadListener(binderDeadListener);Shizuku.addRequestPermissionResultListener(permissionListener);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        buildUi();loadSettings();updateUsbState();refreshDeviceInfo();updateShizukuState();
        handler.postDelayed(this::dailyDriverKick,450);
    }

    private void buildUi(){
        ScrollView sv=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,24,28,48);sv.addView(root);
        TextView title=new TextView(this);title.setText("RAYNEO DAILY DRIVER v0.3");title.setTextSize(25);title.setTextColor(Color.BLACK);title.setTypeface(null,1);root.addView(title);
        TextView sub=new TextView(this);sub.setText("Physical controller + Air 4 Pro head aim → ONE virtual gamepad\nDaily Driver remembers your setup and reconnects it automatically.");sub.setTextSize(14);sub.setPadding(0,5,0,10);root.addView(sub);
        dailyDriver=switcher(root,"DAILY DRIVER AUTO • reconnect + grab + passthrough + head mix");
        status=box(root,"Checking RayNeo…",0xffe4e7e0,Color.BLACK);
        deviceInfo=box(root,"ANDROID GAMEPAD: not detected yet",0xffeeeeee,Color.BLACK);
        live=box(root,"Waiting for IMU…",0xff16241c,0xff77e49e);live.setTypeface(android.graphics.Typeface.MONOSPACE);
        Button quickRec=button("★ QUICK RECENTRE",0xff2f7d4a);root.addView(quickRec,new LinearLayout.LayoutParams(-1,-2));

        heading(root,"1 • HEAD AIM");
        mode=spinner(root,new String[]{"GYRO RATE → RIGHT STICK (recommended)","HEAD ANGLE → RIGHT STICK"});
        axisMode=spinner(root,new String[]{"Virtual right stick: Z / RZ","Virtual right stick: RX / RY"});
        invX=switcher(root,"Invert horizontal");invY=switcher(root,"Invert vertical");rollSteer=switcher(root,"Legacy head roll → left stick (head-only mode)");
        rateLabel=label(root,"Full right-stick at 90°/s");maxRate=bar(root,20,240,90);
        angleLabel=label(root,"Full right-stick at 35° head angle");fullAngle=bar(root,10,90,35);
        deadLabel=label(root,"Deadzone: 2.0°/s");dead=bar(root,0,50,20);
        smoothLabel=label(root,"Smoothing: 0.35");smooth=bar(root,0,90,35);
        LinearLayout startRow=row(root);Button start=button("START GAMEPAD",0xff2f7d4a),stop=button("STOP",0xff9e3d36);startRow.addView(start,weight());startRow.addView(stop,weight());
        LinearLayout recRow=row(root);Button rec=button("RECENTRE",0xff59645b),cal=button("RECALIBRATE",0xff59645b);recRow.addView(rec,weight());recRow.addView(cal,weight());

        heading(root,"2 • CONTROLLER BRIDGE (SHIZUKU)");
        shizukuStatus=box(root,"SHIZUKU: checking…",0xffe8e2d1,Color.BLACK);
        LinearLayout sr=row(root);Button connect=button("CONNECT SHIZUKU",0xff6650a4),find=button("FIND CONTROLLERS",0xff4c6378);sr.addView(connect,weight());sr.addView(find,weight());
        physicalSpinner=spinner(root,new String[]{"No physical controllers scanned yet"});
        profileSpinner=spinner(root,new String[]{"AUTO physical axis mapping (recommended)","Xbox/Linux raw: RX/RY right stick","Android raw: Z/RZ right stick"});
        LinearLayout gr=row(root);Button grab=button("GRAB CONTROLLER",0xffad6f19),release=button("PANIC RELEASE",0xffb3261e);gr.addView(grab,weight());gr.addView(release,weight());
        passthrough=switcher(root,"PASSTHROUGH: physical pad → RayNeo virtual pad");
        headMix=switcher(root,"HEAD MIX: add RayNeo aim to physical right stick");
        bridgeStatus=box(root,"BRIDGE: not grabbed",0xfff0f0f0,Color.BLACK);

        heading(root,"3 • LIVE ANDROID INPUT CHECK");
        androidInput=box(root,"ANDROID INPUT EVENT\nWaiting for joystick events…",0xffeeeeee,Color.BLACK);androidInput.setTypeface(android.graphics.Typeface.MONOSPACE);

        TextView warning=new TextView(this);warning.setText("DAILY DRIVER: after the first successful controller selection it remembers that device. Next launch it can reconnect Shizuku, grab the same pad, and enable PASSTHROUGH + HEAD MIX automatically. PANIC RELEASE immediately returns the physical controller to Android and pauses auto-grab until you manually GRAB again or re-enable Daily Driver.");warning.setTextSize(13);warning.setPadding(0,18,0,8);root.addView(warning);
        setContentView(sv);

        quickRec.setOnClickListener(v->sendAction(ControllerService.ACTION_RECENTER));
        start.setOnClickListener(v->ensurePermissionAndStart());stop.setOnClickListener(v->sendAction(ControllerService.ACTION_STOP));rec.setOnClickListener(v->sendAction(ControllerService.ACTION_RECENTER));cal.setOnClickListener(v->sendAction(ControllerService.ACTION_RECALIBRATE));
        connect.setOnClickListener(v->connectShizuku());find.setOnClickListener(v->findPhysicalControllers());grab.setOnClickListener(v->{panicHold=false;grabSelectedController();});release.setOnClickListener(v->panicRelease());
        dailyDriver.setOnCheckedChangeListener((b1,on)->{if(restoringSettings)return;prefs.edit().putBoolean("daily",on).apply();if(on){panicHold=false;if(PhysicalBridgeManager.grabbed)enableDailyMix();dailyDriverKick();}else bridgeStatus.setText("DAILY DRIVER AUTO OFF • manual controls remain available");});
        passthrough.setOnCheckedChangeListener((b1,on)->{if(restoringSettings)return;if(on&&!PhysicalBridgeManager.grabbed){setBridgeSwitches(false,false);bridgeStatus.setText("Grab a physical controller first");return;}PhysicalBridgeManager.passthrough=on;bridgeStatus.setText(on?"PASSTHROUGH ON":"PASSTHROUGH OFF");});
        headMix.setOnCheckedChangeListener((b1,on)->{if(restoringSettings)return;if(on&&!PhysicalBridgeManager.grabbed){setBridgeSwitches(PhysicalBridgeManager.passthrough,false);bridgeStatus.setText("Grab a physical controller first");return;}PhysicalBridgeManager.headMix=on;bridgeStatus.setText(on?"HEAD MIX ON • physical + head":"HEAD MIX OFF");});
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){PhysicalBridgeManager.profile=pos;if(!restoringSettings)prefs.edit().putInt("profile",pos).apply();}public void onNothingSelected(AdapterView<?> p){}});
        AdapterView.OnItemSelectedListener spin=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!restoringSettings){saveAimSettings();sendConfig();}}public void onNothingSelected(AdapterView<?> p){}};mode.setOnItemSelectedListener(spin);axisMode.setOnItemSelectedListener(spin);
        android.widget.CompoundButton.OnCheckedChangeListener sw=(b1,c)->{if(!restoringSettings){saveAimSettings();sendConfig();}};invX.setOnCheckedChangeListener(sw);invY.setOnCheckedChangeListener(sw);rollSteer.setOnCheckedChangeListener(sw);
        SeekBar.OnSeekBarChangeListener sl=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean f){updateLabels();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){if(!restoringSettings){saveAimSettings();sendConfig();}}};maxRate.setOnSeekBarChangeListener(sl);fullAngle.setOnSeekBarChangeListener(sl);dead.setOnSeekBarChangeListener(sl);smooth.setOnSeekBarChangeListener(sl);
    }

    private void updateShizukuState(){
        try{if(!Shizuku.pingBinder()){shizukuStatus.setText("SHIZUKU: not running");return;}if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)shizukuStatus.setText(PhysicalBridgeManager.bridge!=null?"SHIZUKU BRIDGE READY":"SHIZUKU: running • auto connecting");else shizukuStatus.setText("SHIZUKU: running • press CONNECT once for permission");}catch(Throwable t){shizukuStatus.setText("SHIZUKU unavailable: "+t.getClass().getSimpleName());}
    }
    private void connectShizuku(){try{if(!Shizuku.pingBinder()){shizukuStatus.setText("Start Shizuku first");return;}if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){Shizuku.requestPermission(SHIZUKU_REQ);shizukuStatus.setText("Waiting for Shizuku permission…");return;}bindInputBridge();}catch(Throwable t){shizukuStatus.setText("Shizuku connect failed: "+t);}}
    private void bindInputBridge(){if(bridgeBinding||PhysicalBridgeManager.bridge!=null)return;try{bridgeBinding=true;Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(new ComponentName(getPackageName(),InputBridgeService.class.getName())).processNameSuffix("input_bridge").daemon(false).version(3).debuggable(true);Shizuku.bindUserService(args,bridgeConnection);shizukuStatus.setText("Starting privileged input bridge…");}catch(Throwable t){bridgeBinding=false;shizukuStatus.setText("Bridge start failed: "+t);}}

    private void findPhysicalControllers(){
        IInputBridge b=PhysicalBridgeManager.bridge;if(b==null){bridgeStatus.setText("Connect Shizuku first");return;}
        try{String raw=b.listControllers();physicalPaths.clear();physicalNames.clear();if(raw!=null)for(String line:raw.split("\\n")){String[] q=line.split("\\|",2);if(q.length==2&&q[0].startsWith("/dev/input/event")){physicalPaths.add(q[0]);physicalNames.add(q[1]+"   ["+q[0]+"]");}}
            if(physicalNames.isEmpty()){physicalNames.add(raw==null?"No controllers found":raw.trim());}physicalSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,physicalNames));bridgeStatus.setText(physicalPaths.isEmpty()?"No physical gamepad-shaped evdev device found":"Found "+physicalPaths.size()+" physical controller(s)");
            if(dailyDriver!=null&&dailyDriver.isChecked()&&!panicHold)handler.postDelayed(this::autoGrabRememberedController,250);
        }catch(Exception e){bridgeStatus.setText("Controller scan failed: "+e);}
    }

    private void grabSelectedController(){
        int pos=physicalSpinner.getSelectedItemPosition();grabControllerAt(pos,false);
    }

    private void grabControllerAt(int pos,boolean automatic){
        IInputBridge b=PhysicalBridgeManager.bridge;if(b==null){bridgeStatus.setText("Connect Shizuku first");return;}if(pos<0||pos>=physicalPaths.size()){bridgeStatus.setText("Find/select a controller first");return;}
        try{releaseDeviceOnly();String path=physicalPaths.get(pos);int fd=b.openDevice(path,true);if(fd<0){bridgeStatus.setText("GRAB FAILED "+fd+" • shell may not have /dev/input permission");return;}PhysicalBridgeManager.devicePath=path;PhysicalBridgeManager.deviceName=b.currentDeviceName();PhysicalBridgeManager.grabbed=true;prefs.edit().putString("last_path",path).putString("last_name",PhysicalBridgeManager.deviceName).apply();
            if(dailyDriver!=null&&dailyDriver.isChecked()){enableDailyMix();bridgeStatus.setText((automatic?"AUTO ":"")+"DAILY DRIVER READY • "+PhysicalBridgeManager.deviceName+"\nPhysical controls + RayNeo head aim → ONE gamepad");}
            else {setBridgeSwitches(false,false);bridgeStatus.setText("GRABBED • "+PhysicalBridgeManager.deviceName+" • fd "+fd+" • uid "+b.serviceUid()+"\nEnable PASSTHROUGH and HEAD MIX when wanted.");}
        }catch(Exception e){bridgeStatus.setText("GRAB ERROR: "+e);}
    }

    private void autoGrabRememberedController(){
        if(panicHold||dailyDriver==null||!dailyDriver.isChecked()||PhysicalBridgeManager.bridge==null||PhysicalBridgeManager.grabbed||physicalPaths.isEmpty())return;
        String lastPath=prefs.getString("last_path",""),lastName=prefs.getString("last_name","");
        int chosen=-1;
        if(!lastPath.isEmpty())chosen=physicalPaths.indexOf(lastPath);
        if(chosen<0&&!lastName.isEmpty())for(int i=0;i<physicalNames.size();i++)if(physicalNames.get(i).contains(lastName)){chosen=i;break;}
        if(chosen<0&&physicalPaths.size()==1)chosen=0;
        if(chosen<0){bridgeStatus.setText("DAILY DRIVER: multiple controllers found • select yours and press GRAB once");return;}
        physicalSpinner.setSelection(chosen);grabControllerAt(chosen,true);
    }

    private void enableDailyMix(){PhysicalBridgeManager.passthrough=true;PhysicalBridgeManager.headMix=true;setBridgeSwitches(true,true);}
    private void setBridgeSwitches(boolean pass,boolean head){boolean old=restoringSettings;restoringSettings=true;if(passthrough!=null)passthrough.setChecked(pass);if(headMix!=null)headMix.setChecked(head);restoringSettings=old;}
    private void releaseDeviceOnly(){IInputBridge b=PhysicalBridgeManager.bridge;try{if(b!=null)b.releaseDevice();}catch(Exception ignored){}PhysicalBridgeManager.clearGrabState();setBridgeSwitches(false,false);}
    private void panicRelease(){panicHold=true;releaseDeviceOnly();if(bridgeStatus!=null)bridgeStatus.setText("PANIC RELEASED • controller returned to Android\nDaily Driver auto-grab paused until you press GRAB or re-enable AUTO.");}

    private void dailyDriverKick(){
        if(dailyDriver==null||!dailyDriver.isChecked()||panicHold)return;
        maybeAutoStartGamepad();
        try{
            if(!Shizuku.pingBinder()){updateShizukuState();return;}
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){updateShizukuState();return;}
            if(PhysicalBridgeManager.bridge==null)bindInputBridge();
            else if(!PhysicalBridgeManager.grabbed)findPhysicalControllers();
        }catch(Throwable ignored){}
    }

    private void maybeAutoStartGamepad(){
        UsbManager um=(UsbManager)getSystemService(USB_SERVICE);UsbDevice d=findDevice();
        if(d!=null&&um.hasPermission(d))startController();
    }

    private void loadSettings(){
        restoringSettings=true;
        dailyDriver.setChecked(prefs.getBoolean("daily",true));
        mode.setSelection(prefs.getInt("mode",0));
        axisMode.setSelection(prefs.getInt("axis",0));
        invX.setChecked(prefs.getBoolean("inv_x",false));
        invY.setChecked(prefs.getBoolean("inv_y",false));
        rollSteer.setChecked(prefs.getBoolean("roll",false));
        maxRate.setProgress(prefs.getInt("rate",90));
        fullAngle.setProgress(prefs.getInt("angle",35));
        dead.setProgress(prefs.getInt("dead",20));
        smooth.setProgress(prefs.getInt("smooth",35));
        int profile=prefs.getInt("profile",0);profileSpinner.setSelection(profile);PhysicalBridgeManager.profile=profile;
        restoringSettings=false;updateLabels();
    }

    private void saveAimSettings(){
        prefs.edit().putInt("mode",mode.getSelectedItemPosition()).putInt("axis",axisMode.getSelectedItemPosition())
                .putBoolean("inv_x",invX.isChecked()).putBoolean("inv_y",invY.isChecked()).putBoolean("roll",rollSteer.isChecked())
                .putInt("rate",maxRate.getProgress()).putInt("angle",fullAngle.getProgress()).putInt("dead",dead.getProgress()).putInt("smooth",smooth.getProgress()).apply();
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){if((e.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK&&e.getAction()==MotionEvent.ACTION_MOVE){androidInput.setText(String.format(Locale.UK,"ANDROID INPUT EVENT\nLEFT X/Y %+5.2f %+5.2f\nZ/RZ     %+5.2f %+5.2f\nRX/RY    %+5.2f %+5.2f",e.getAxisValue(MotionEvent.AXIS_X),e.getAxisValue(MotionEvent.AXIS_Y),e.getAxisValue(MotionEvent.AXIS_Z),e.getAxisValue(MotionEvent.AXIS_RZ),e.getAxisValue(MotionEvent.AXIS_RX),e.getAxisValue(MotionEvent.AXIS_RY)));return true;}return super.dispatchGenericMotionEvent(e);}
    @Override public boolean dispatchKeyEvent(KeyEvent e){if((e.getSource()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD){androidInput.setText("ANDROID GAMEPAD KEY\nkeyCode="+e.getKeyCode()+"  "+(e.getAction()==KeyEvent.ACTION_DOWN?"DOWN":"UP"));return true;}return super.dispatchKeyEvent(e);}

    private TextView box(LinearLayout r,String t,int bg,int fg){TextView v=new TextView(this);v.setText(t);v.setTextSize(14);v.setTextColor(fg);v.setPadding(14,12,14,12);v.setBackgroundColor(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,7,0,4);r.addView(v,p);return v;}
    private void heading(LinearLayout r,String t){TextView v=new TextView(this);v.setText(t);v.setTypeface(null,1);v.setTextSize(16);v.setPadding(0,18,0,5);r.addView(v);}
    private Spinner spinner(LinearLayout r,String[] a){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,a));r.addView(s);return s;}
    private Switch switcher(LinearLayout r,String t){Switch s=new Switch(this);s.setText(t);r.addView(s);return s;}
    private TextView label(LinearLayout r,String t){TextView v=new TextView(this);v.setText(t);v.setTextSize(14);v.setPadding(0,10,0,0);r.addView(v);return v;}
    private SeekBar bar(LinearLayout r,int min,int max,int value){SeekBar b=new SeekBar(this);if(Build.VERSION.SDK_INT>=26)b.setMin(min);b.setMax(max);b.setProgress(value);r.addView(b);return b;}
    private LinearLayout row(LinearLayout root){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);root.addView(r);return r;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    private Button button(String t,int c){Button b=new Button(this);b.setText(t);b.setTextColor(Color.WHITE);b.setBackgroundColor(c);return b;}
    private void updateLabels(){rateLabel.setText("Full right-stick at "+maxRate.getProgress()+"°/s");angleLabel.setText("Full right-stick at "+fullAngle.getProgress()+"° head angle");deadLabel.setText(String.format(Locale.UK,"Deadzone: %.1f°/s",dead.getProgress()/10.0));smoothLabel.setText(String.format(Locale.UK,"Smoothing: %.2f",smooth.getProgress()/100.0));}

    private UsbDevice findDevice(){UsbManager um=(UsbManager)getSystemService(USB_SERVICE);for(UsbDevice d:um.getDeviceList().values())if(d.getVendorId()==VID&&d.getProductId()==PID)return d;return null;}
    private void updateUsbState(){UsbDevice d=findDevice();if(d==null)status.setText("RayNeo not connected");else{UsbManager um=(UsbManager)getSystemService(USB_SERVICE);status.setText(um.hasPermission(d)&&dailyDriver!=null&&dailyDriver.isChecked()?"RayNeo Air 4 Pro detected • Daily Driver auto-start enabled":"RayNeo Air 4 Pro detected • press START GAMEPAD");}}
    private void ensurePermissionAndStart(){UsbManager um=(UsbManager)getSystemService(USB_SERVICE);UsbDevice d=findDevice();if(d==null){status.setText("Plug in the RayNeo glasses first");return;}if(um.hasPermission(d)){startController();return;}PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent(USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));um.requestPermission(d,pi);status.setText("Waiting for USB permission…");}
    private Intent configured(String action){return new Intent(this,ControllerService.class).setAction(action).putExtra("mode",mode.getSelectedItemPosition()).putExtra("axisMode",axisMode.getSelectedItemPosition()).putExtra("maxRate",(double)maxRate.getProgress()).putExtra("fullAngle",(double)fullAngle.getProgress()).putExtra("deadzone",dead.getProgress()/10.0).putExtra("smooth",smooth.getProgress()/100.0).putExtra("invertX",invX.isChecked()).putExtra("invertY",invY.isChecked()).putExtra("rollSteer",rollSteer.isChecked()).putExtra("rollFullAngle",30.0);}
    private void startController(){Intent i=configured(ControllerService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);status.setText("Starting… keep glasses still");}
    private void sendConfig(){if(mode==null||axisMode==null)return;try{startService(configured(ControllerService.ACTION_CONFIG));}catch(Exception ignored){}}
    private void sendAction(String action){try{startService(new Intent(this,ControllerService.class).setAction(action));}catch(Exception ignored){}}

    private void refreshDeviceInfo(){if(inputManager==null||deviceInfo==null)return;String found=null;for(int id:InputDevice.getDeviceIds()){InputDevice d=InputDevice.getDevice(id);if(d!=null&&d.getName()!=null&&d.getName().contains("RayNeo VR Gamepad")){found="ANDROID VIRTUAL GAMEPAD DETECTED • id "+id+"\nsources 0x"+Integer.toHexString(d.getSources());break;}}deviceInfo.setText(found!=null?found:"ANDROID VIRTUAL GAMEPAD: not detected yet");}
    @Override public void onInputDeviceAdded(int id){refreshDeviceInfo();}@Override public void onInputDeviceRemoved(int id){refreshDeviceInfo();}@Override public void onInputDeviceChanged(int id){refreshDeviceInfo();}
    @Override protected void onResume(){super.onResume();updateUsbState();updateShizukuState();handler.postDelayed(this::dailyDriverKick,250);}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);updateUsbState();handler.postDelayed(this::dailyDriverKick,250);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);try{if(inputManager!=null)inputManager.unregisterInputDeviceListener(this);}catch(Exception ignored){}try{unregisterReceiver(usbReceiver);}catch(Exception ignored){}try{unregisterReceiver(statusReceiver);}catch(Exception ignored){}try{Shizuku.removeBinderReceivedListener(binderListener);Shizuku.removeBinderDeadListener(binderDeadListener);Shizuku.removeRequestPermissionResultListener(permissionListener);}catch(Throwable ignored){}super.onDestroy();}
}
