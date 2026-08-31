from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.7 MainActivity target {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'    private SeekBar sbsDepth,sbsZoom,sbsVertical;\n    private TextView sbsDepthLabel,sbsZoomLabel,sbsVerticalLabel;\n',
'    private SeekBar sbsDepth,sbsZoom,sbsVertical,sbsAiDepth,sbsConvergence;\n    private TextView sbsDepthLabel,sbsZoomLabel,sbsVerticalLabel,sbsAiDepthLabel,sbsConvergenceLabel;\n',
'AI depth fields')

one(
'        TextView sub=new TextView(this);sub.setText("v0.6 Stereo Depth Lab • proven v0.5.5 comfort head-look/controller stack + tunable native Air 4 Pro LEFT EYE | RIGHT EYE output.");sub.setTextSize(14);sub.setPadding(0,4,0,10);root.addView(sub);',
'        TextView sub=new TextView(this);sub.setText("v0.7 AI Depth • proven v0.5.5 comfort head-look/controller stack + MiDaS per-pixel depth reconstruction for native Air 4 Pro SBS.");sub.setTextSize(14);sub.setPadding(0,4,0,10);root.addView(sub);',
'subtitle')

one('        heading(root,"5 • AIR 4 PRO STEREO DEPTH LAB v0.6");','        heading(root,"5 • AIR 4 PRO AI DEPTH v0.7");','heading')

one(
'        box(root,"This stage adds controlled LEFT/RIGHT eye disparity to the already-working native SBS mirror. It moves the whole captured image plane in stereo space; it is NOT yet per-object/game-engine depth. Start at 0 px, then try 4 px and 8 px. If it feels crossed/backwards, use SWAP EYES. Stop immediately if the images are uncomfortable to fuse.",0xfff3efe3,Color.BLACK);',
'        box(root,"v0.7 runs a 256×256 MiDaS depth estimate from the live Minecraft frame and uses that map to shift NEAR and FAR pixels differently between the two eyes. Keep BASE separation at 0 px first, start AI DEPTH gently, and use SWAP EYES if foreground/background depth feels reversed. Depth is relative and experimental, not game-engine geometry.",0xfff3efe3,Color.BLACK);',
'info box')

one(
'        sbsSwap=switcher(root,"SWAP EYES / reverse stereo direction");sbsSwap.setChecked(prefs.getBoolean("sbs_swap",false));\n        LinearLayout stereoPresets=row(root);Button monoSbs=button("MONO 0px",0xff59645b),gentleSbs=button("GENTLE 4px",0xff526b48),mediumSbs=button("MEDIUM 8px",0xff375d84);stereoPresets.addView(monoSbs,weight());stereoPresets.addView(gentleSbs,weight());stereoPresets.addView(mediumSbs,weight());',
'        sbsSwap=switcher(root,"SWAP EYES / reverse stereo direction");sbsSwap.setChecked(prefs.getBoolean("sbs_swap",false));\n        sbsAiDepthLabel=label(root,"AI depth strength: 0% • OFF / proven mono baseline");sbsAiDepth=bar(root,0,100,prefs.getInt("sbs_ai_depth",0));\n        sbsConvergenceLabel=label(root,"Depth convergence plane: 50%");sbsConvergence=bar(root,5,95,prefs.getInt("sbs_convergence",50));\n        LinearLayout aiPresets=row(root);Button aiOff=button("AI OFF",0xff59645b),aiGentle=button("AI GENTLE",0xff526b48),aiMedium=button("AI MEDIUM",0xff375d84);aiPresets.addView(aiOff,weight());aiPresets.addView(aiGentle,weight());aiPresets.addView(aiMedium,weight());\n        LinearLayout stereoPresets=row(root);Button monoSbs=button("BASE 0px",0xff59645b),gentleSbs=button("BASE 4px",0xff526b48),mediumSbs=button("BASE 8px",0xff375d84);stereoPresets.addView(monoSbs,weight());stereoPresets.addView(gentleSbs,weight());stereoPresets.addView(mediumSbs,weight());',
'AI controls')

one(
'        monoSbs.setOnClickListener(v->applySbsPreset(0,100));gentleSbs.setOnClickListener(v->applySbsPreset(4,102));mediumSbs.setOnClickListener(v->applySbsPreset(8,104));\n',
'        monoSbs.setOnClickListener(v->applySbsPreset(0,100));gentleSbs.setOnClickListener(v->applySbsPreset(4,102));mediumSbs.setOnClickListener(v->applySbsPreset(8,104));\n        aiOff.setOnClickListener(v->applyAiPreset(0,50));aiGentle.setOnClickListener(v->applyAiPreset(22,50));aiMedium.setOnClickListener(v->applyAiPreset(38,50));\n',
'AI preset listeners')

one(
'        sbsDepth.setOnSeekBarChangeListener(sbsTune);sbsZoom.setOnSeekBarChangeListener(sbsTune);sbsVertical.setOnSeekBarChangeListener(sbsTune);',
'        sbsDepth.setOnSeekBarChangeListener(sbsTune);sbsZoom.setOnSeekBarChangeListener(sbsTune);sbsVertical.setOnSeekBarChangeListener(sbsTune);sbsAiDepth.setOnSeekBarChangeListener(sbsTune);sbsConvergence.setOnSeekBarChangeListener(sbsTune);',
'AI seek listeners')

one(
'''            Intent i=new Intent(this,SbsMirrorService.class).setAction(SbsMirrorService.ACTION_START)
                    .putExtra(SbsMirrorService.EXTRA_RESULT_CODE,resultCode)
                    .putExtra(SbsMirrorService.EXTRA_RESULT_DATA,data)
                    .putExtra(SbsMirrorService.EXTRA_SEPARATION_PX,(float)sbsDepth.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_ZOOM_PERCENT,(float)sbsZoom.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_VERTICAL_PX,(float)sbsVertical.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_SWAP_EYES,sbsSwap.isChecked());''',
'''            Intent i=new Intent(this,SbsMirrorService.class).setAction(SbsMirrorService.ACTION_START)
                    .putExtra(SbsMirrorService.EXTRA_RESULT_CODE,resultCode)
                    .putExtra(SbsMirrorService.EXTRA_RESULT_DATA,data)
                    .putExtra(SbsMirrorService.EXTRA_SEPARATION_PX,(float)sbsDepth.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_ZOOM_PERCENT,(float)sbsZoom.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_VERTICAL_PX,(float)sbsVertical.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_SWAP_EYES,sbsSwap.isChecked())
                    .putExtra(SbsMirrorService.EXTRA_AI_DEPTH_PERCENT,(float)sbsAiDepth.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_CONVERGENCE_PERCENT,(float)sbsConvergence.getProgress());''',
'start AI extras')

one(
'        sbsVerticalLabel.setText("Vertical eye trim: "+(v>=0?"+":"")+v+" px"+(sbsSwap!=null&&sbsSwap.isChecked()?" • EYES SWAPPED":""));\n',
'        sbsVerticalLabel.setText("Vertical eye trim: "+(v>=0?"+":"")+v+" px"+(sbsSwap!=null&&sbsSwap.isChecked()?" • EYES SWAPPED":""));\n        int ai=sbsAiDepth.getProgress(),cv=sbsConvergence.getProgress();\n        sbsAiDepthLabel.setText(ai==0?"AI depth strength: 0% • OFF / proven mono baseline":"AI depth strength: "+ai+"% • per-pixel disparity ON");\n        sbsConvergenceLabel.setText("Depth convergence plane: "+cv+"%");\n',
'AI labels')

one(
'        prefs.edit().putInt("sbs_depth_px",sbsDepth.getProgress()).putInt("sbs_zoom",sbsZoom.getProgress()).putInt("sbs_vertical",sbsVertical.getProgress()).putBoolean("sbs_swap",sbsSwap.isChecked()).apply();',
'        prefs.edit().putInt("sbs_depth_px",sbsDepth.getProgress()).putInt("sbs_zoom",sbsZoom.getProgress()).putInt("sbs_vertical",sbsVertical.getProgress()).putBoolean("sbs_swap",sbsSwap.isChecked()).putInt("sbs_ai_depth",sbsAiDepth.getProgress()).putInt("sbs_convergence",sbsConvergence.getProgress()).apply();',
'save AI settings')

one(
'''    private void sendSbsConfig(){
        if(sbsDepth==null)return;
        try{
            Intent i=new Intent(this,SbsMirrorService.class).setAction(SbsMirrorService.ACTION_CONFIG)
                    .putExtra(SbsMirrorService.EXTRA_SEPARATION_PX,(float)sbsDepth.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_ZOOM_PERCENT,(float)sbsZoom.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_VERTICAL_PX,(float)sbsVertical.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_SWAP_EYES,sbsSwap.isChecked());''',
'''    private void sendSbsConfig(){
        if(sbsDepth==null)return;
        try{
            Intent i=new Intent(this,SbsMirrorService.class).setAction(SbsMirrorService.ACTION_CONFIG)
                    .putExtra(SbsMirrorService.EXTRA_SEPARATION_PX,(float)sbsDepth.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_ZOOM_PERCENT,(float)sbsZoom.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_VERTICAL_PX,(float)sbsVertical.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_SWAP_EYES,sbsSwap.isChecked())
                    .putExtra(SbsMirrorService.EXTRA_AI_DEPTH_PERCENT,(float)sbsAiDepth.getProgress())
                    .putExtra(SbsMirrorService.EXTRA_CONVERGENCE_PERCENT,(float)sbsConvergence.getProgress());''',
'live AI extras')

one(
'    private void probeNativeSbs(){\n',
'    private void applyAiPreset(int strength,int convergence){\n        sbsDepth.setProgress(0);sbsAiDepth.setProgress(strength);sbsConvergence.setProgress(convergence);sbsZoom.setProgress(strength==0?100:102);saveSbsSettings();updateSbsLabels();sendSbsConfig();\n    }\n\n    private void probeNativeSbs(){\n',
'AI preset helper')

p.write_text(s)
print('v0.7 MainActivity AI depth controls transform OK')
