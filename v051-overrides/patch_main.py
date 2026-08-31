from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_main.py <MainActivity.java>")

p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"v0.5.1 transform failed: {label}: expected 1 match, got {count}")
    s = s.replace(old, new, 1)


replace_once(
    "import android.content.pm.PackageManager;\nimport android.hardware.input.InputManager;",
    "import android.content.pm.PackageManager;\nimport android.hardware.display.DisplayManager;\nimport android.hardware.input.InputManager;",
    "DisplayManager import",
)

replace_once(
    'title.setText("RAYNEO VR v0.5 • SBS LAB")',
    'title.setText("RAYNEO VR v0.5.1 • NATIVE SBS")',
    "title",
)

replace_once(
    'sub.setText("v0.4.1 VR Look + experimental live side-by-side visual mirror for RayNeo 3D/SBS testing.")',
    'sub.setText("Air 4 Pro native SBS layout + v0.4.1 VR Look. The glasses control SBS mode; Android only supplies LEFT EYE | RIGHT EYE frames.")',
    "subtitle",
)

old_visual = '''        heading(root,"5 • v0.5 SBS VISUAL LAB (EXPERIMENTAL)");
        sbsStatus=box(root,"SBS MIRROR: stopped",0xffe7edf3,Color.BLACK);
        box(root,"LIVE SBS MIRROR captures the game screen and duplicates it into LEFT + RIGHT eye halves. It prefers the RayNeo as a separate external display (best/no recursion). This first build is MONO SBS: it proves the visual pipeline before real stereo/depth work.",0xfff3efe3,Color.BLACK);
        LinearLayout sbsRow=row(root);Button startSbs=button("START LIVE SBS MIRROR",0xff375d84),stopSbs=button("STOP SBS",0xff9e3d36);sbsRow.addView(startSbs,weight());sbsRow.addView(stopSbs,weight());
'''

new_visual = '''        heading(root,"5 • AIR 4 PRO NATIVE SBS");
        sbsStatus=box(root,"NATIVE SBS: 1) hold MENU + VOL UP on the glasses  2) CHECK DISPLAY MODE  3) START NATIVE SBS MIRROR",0xffe7edf3,Color.BLACK);
        box(root,"v0.5.1 does NOT switch the glasses into 3D/SBS. The Air 4 Pro already does that in hardware with MENU + VOL UP. This app only captures the game and lays it out as LEFT EYE | RIGHT EYE. The first visual stage is still MONO SBS, so both eyes get the same image while we prove the native pipeline.",0xfff3efe3,Color.BLACK);
        Button checkSbs=button("CHECK AIR 4 PRO DISPLAY MODE",0xff526b48);root.addView(checkSbs);checkSbs.setOnClickListener(v->probeNativeSbs());
        LinearLayout sbsRow=row(root);Button startSbs=button("START NATIVE SBS MIRROR",0xff375d84),stopSbs=button("STOP SBS",0xff9e3d36);sbsRow.addView(startSbs,weight());sbsRow.addView(stopSbs,weight());
'''
replace_once(old_visual, new_visual, "native SBS UI block")

probe_method = '''    private void probeNativeSbs(){
        if(sbsStatus==null)return;
        try{
            DisplayManager dm=(DisplayManager)getSystemService(DISPLAY_SERVICE);
            Display[] displays=dm!=null?dm.getDisplays():new Display[0];
            StringBuilder sb=new StringBuilder();
            boolean wide=false,external=false;
            for(Display d:displays){
                if(d==null)continue;
                Display.Mode m=d.getMode();
                int w=m.getPhysicalWidth(),h=m.getPhysicalHeight();
                int longSide=Math.max(w,h),shortSide=Math.max(1,Math.min(w,h));
                double ratio=longSide/(double)shortSide;
                boolean isWide=ratio>=2.60 && longSide>=2500;
                if(d.getDisplayId()!=Display.DEFAULT_DISPLAY)external=true;
                if(isWide)wide=true;
                if(sb.length()>0)sb.append("\\n");
                sb.append(d.getDisplayId()==Display.DEFAULT_DISPLAY?"HANDHELD/DEFAULT":"EXTERNAL")
                        .append(" • ").append(d.getName())
                        .append(" • ").append(w).append("×").append(h)
                        .append(isWide?" • WIDE SBS-LIKE MODE":"");
            }
            if(sb.length()==0)sb.append("No Android displays reported");
            String head=wide?"NATIVE SBS WIDE MODE DETECTED ✅":(external?"External RayNeo-style display detected • wide mode not reported yet":"No separate external display reported by Android");
            sbsStatus.setText(head+"\\n"+sb+"\\n\\nIf you already used MENU + VOL UP and Android still reports normal width, the glasses may be handling SBS internally — you can still test START NATIVE SBS MIRROR.");
        }catch(Throwable t){
            sbsStatus.setText("DISPLAY CHECK ERROR • "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));
        }
    }

'''
replace_once(
    "    private void startSbsMirrorFlow(){\n",
    probe_method + "    private void startSbsMirrorFlow(){\n",
    "display probe insertion",
)

replace_once(
    "        try{\n            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);\n",
    "        try{\n            probeNativeSbs();\n            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);\n",
    "probe before capture",
)

replace_once(
    '            if(sbsStatus!=null)sbsStatus.setText("Choose what Android may capture for the SBS mirror…");',
    '            if(sbsStatus!=null)sbsStatus.append("\\n\\nChoose the game/app Android may capture for native SBS…");',
    "capture prompt",
)

p.write_text(s, encoding="utf-8")
print("v0.5.1 MainActivity transform OK")
