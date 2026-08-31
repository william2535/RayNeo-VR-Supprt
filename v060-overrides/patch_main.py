from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.6 MainActivity target {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'    private SeekBar gain,maxRate,fullAngle,hybrid,curve,pitchLimit,yawLimit,dead,smooth,turnThreshold,turnStrength,snapMs;\n',
'    private SeekBar gain,maxRate,fullAngle,hybrid,curve,pitchLimit,yawLimit,dead,smooth,turnThreshold,turnStrength,snapMs;\n    private SeekBar sbsDepth,sbsZoom,sbsVertical;\n    private TextView sbsDepthLabel,sbsZoomLabel,sbsVerticalLabel;\n    private Switch sbsSwap;\n',
'fields')

one(
'        TextView sub=new TextView(this);sub.setText("Air 4 Pro native SBS layout + v0.4.1 VR Look. The glasses control SBS mode; Android only supplies LEFT EYE | RIGHT EYE frames.");sub.setTextSize(14);sub.setPadding(0,4,0,10);root.addView(sub);',
'        TextView sub=new TextView(this);sub.setText("v0.6 Stereo Depth Lab • proven v0.5.5 comfort head-look/controller stack + tunable native Air 4 Pro LEFT EYE | RIGHT EYE output.");sub.setTextSize(14);sub.setPadding(0,4,0,10);root.addView(sub);',
'subtitle')

old='''        heading(root,"5 • AIR 4 PRO NATIVE SBS");
        sbsStatus=box(root,"NATIVE SBS: 1) hold MENU + VOL UP on the glasses  2) CHECK DISPLAY MODE  3) START NATIVE SBS MIRROR",0xffe7edf3,Color.BLACK);
        box(root,"v0.5.1 does NOT switch the glasses into 3D/SBS. The Air 4 Pro already does that in hardware with MENU + VOL UP. This app only captures the game and lays it out as LEFT EYE | RIGHT EYE. The first visual stage is still MONO SBS, so both eyes get the same image while we prove the native pipeline.",0xfff3efe3,Color.BLACK);
        Button checkSbs=button("CHECK AIR 4 PRO DISPLAY MODE",0xff526b48);root.addView(checkSbs);checkSbs.setOnClickListener(v->probeNativeSbs());
        LinearLayout sbsRow=row(root);Button startSbs=button("START NATIVE SBS MIRROR",0xff375d84),stopSbs=button("STOP SBS",0xff9e3d36);sbsRow.addView(startSbs,weight());sbsRow.addView(stopSbs,weight());
        startSbs.setOnClickListener(v->startSbsMirrorFlow());stopSbs.setOnClickListener(v->stopSbsMirror());
'''
new='''        heading(root,"5 • AIR 4 PRO STEREO DEPTH LAB v0.6");
        sbsStatus=box(root,"STEREO SBS: start at 0 px (MONO), switch Air 4 Pro to SBS with MENU + VOL UP, then increase gently while viewing the game.",0xffe7edf3,Color.BLACK);
        box(root,"This stage adds controlled LEFT/RIGHT eye disparity to the already-working native SBS mirror. It moves the whole captured image plane in stereo space; it is NOT yet per-object/game-engine depth. Start at 0 px, then try 4 px and 8 px. If it feels crossed/backwards, use SWAP EYES. Stop immediately if the images are uncomfortable to fuse.",0xfff3efe3,Color.BLACK);
        sbsDepthLabel=label(root,"Eye separation: 0 px • MONO baseline");sbsDepth=bar(root,0,24,prefs.getInt("sbs_depth_px",0));
        sbsZoomLabel=label(root,"Stereo crop/zoom: 100%");sbsZoom=bar(root,100,115,prefs.getInt("sbs_zoom",100));
        sbsVerticalLabel=label(root,"Vertical eye trim: 0 px");sbsVertical=bar(root,-8,8,prefs.getInt("sbs_vertical",0));
        sbsSwap=switcher(root,"SWAP EYES / reverse stereo direction");sbsSwap.setChecked(prefs.getBoolean("sbs_swap",false));
        LinearLayout stereoPresets=row(root);Button monoSbs=button("MONO 0px",0xff59645b),gentleSbs=button("GENTLE 4px",0xff526b48),mediumSbs=button("MEDIUM 8px",0xff375d84);stereoPresets.addView(monoSbs,weight());stereoPresets.addView(gentleSbs,weight());stereoPresets.addView(mediumSbs,weight());
        Button checkSbs=button("CHECK AIR 4 PRO DISPLAY MODE",0xff526b48);root.addView(checkSbs);checkSbs.setOnClickListener(v->probeNativeSbs());
        LinearLayout sbsRow=row(root);Button startSbs=button("START v0.6 STEREO SBS",0xff375d84),stopSbs=button("STOP SBS",0xff9e3d36);sbsRow.addView(startSbs,weight());sbsRow.addView(stopSbs,weight());
        startSbs.setOnClickListener(v->startSbsMirrorFlow());stopSbs.setOnClickListener(v->stopSbsMirror());
        monoSbs.setOnClickListener(v->applySbsPreset(0,100));gentleSbs.setOnClickListener(v->applySbsPreset(4,102));mediumSbs.setOnClickListener(v->applySbsPreset(8,104));
        SeekBar.OnSeekBarChangeListener sbsTune=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int progress,boolean fromUser){updateSbsLabels();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){saveSbsSettings();sendSbsConfig();}};
        sbsDepth.setOnSeekBarChangeListener(sbsTune);sbsZoom.setOnSeekBarChangeListener(sbsTune);sbsVertical.setOnSeekBarChangeListener(sbsTune);
        sbsSwap.setOnCheckedChangeListener((b1,on)->{saveSbsSettings();sendSbsConfig();updateSbsLabels();});
        updateSbsLabels();
'''
one(old,new,'stereo UI block')

one(
'    private void startSbsMirrorFlow(){\n        try{\n            probeNativeSbs();',
'    private void startSbsMirrorFlow(){\n        try{\n            saveSbsSettings();\n            probeNativeSbs();',
'save before capture')

old_start='''            Intent i=new Intent(this,SbsMirrorService.class).setAction(SbsMirrorService.ACTION_START)
                    .putExtra(SbsMirrorService.EXTRA_RESULT_CODE,resultCode)
                    .putExtra(SbsMirrorService.EXTRA_RESULT_DATA,data);'''
new_start='''            Intent i=new Intent(this,SbsMirrorService.class).setAction(SbsMirrorService.ACTION_START)
                    .putExtra(SbsMirrorService.EXTRA_RESULT_CODE,resultCode)
                    .putExtra(SbsMirrorService.EXTRA_RESULT_DATA,data)
                    .putExtra(SbsMirrorService.EXTRA_SEPARATION_PX,(float)sbsDepth.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_ZOOM_PERCENT,(float)sbsZoom.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_VERTICAL_PX,(float)sbsVertical.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_SWAP_EYES,sbsSwap.isChecked());'''
one(old_start,new_start,'start extras')

insert='''    private void updateSbsLabels(){
        if(sbsDepthLabel==null)return;
        int d=sbsDepth.getProgress(),z=sbsZoom.getProgress(),v=sbsVertical.getProgress();
        sbsDepthLabel.setText(d==0?"Eye separation: 0 px • MONO baseline":"Eye separation: "+d+" px • stereo plane");
        sbsZoomLabel.setText("Stereo crop/zoom: "+z+"%");
        sbsVerticalLabel.setText("Vertical eye trim: "+(v>=0?"+":"")+v+" px"+(sbsSwap!=null&&sbsSwap.isChecked()?" • EYES SWAPPED":""));
    }

    private void saveSbsSettings(){
        if(prefs==null||sbsDepth==null)return;
        prefs.edit().putInt("sbs_depth_px",sbsDepth.getProgress()).putInt("sbs_zoom",sbsZoom.getProgress()).putInt("sbs_vertical",sbsVertical.getProgress()).putBoolean("sbs_swap",sbsSwap.isChecked()).apply();
    }

    private void applySbsPreset(int depth,int zoom){
        sbsDepth.setProgress(depth);sbsZoom.setProgress(zoom);sbsVertical.setProgress(0);saveSbsSettings();updateSbsLabels();sendSbsConfig();
    }

    private void sendSbsConfig(){
        if(sbsDepth==null)return;
        try{
            Intent i=new Intent(this,SbsMirrorService.class).setAction(SbsMirrorService.ACTION_CONFIG)
                    .putExtra(SbsMirrorService.EXTRA_SEPARATION_PX,(float)sbsDepth.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_ZOOM_PERCENT,(float)sbsZoom.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_VERTICAL_PX,(float)sbsVertical.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_SWAP_EYES,sbsSwap.isChecked());
            startService(i);
        }catch(Throwable ignored){}
    }

'''
one(
'    private void probeNativeSbs(){\n',
insert+'    private void probeNativeSbs(){\n',
'stereo helper methods')

p.write_text(s)
print('v0.6 MainActivity stereo controls transform OK')
