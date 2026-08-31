from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.7.4 main patch {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'        sbsAiDepthLabel=label(root,"AI depth strength: 0% • OFF / proven mono baseline");sbsAiDepth=bar(root,0,100,prefs.getInt("sbs_ai_depth",0));',
'        sbsAiDepthLabel=label(root,"AI depth strength: 0% • OFF / CPU-SAFE baseline");sbsAiDepth=bar(root,0,100,0);',
'force AI off on app launch')

one(
'        box(root,"v0.7 runs a 256×256 MiDaS depth estimate from the live Minecraft frame and uses that map to shift NEAR and FAR pixels differently between the two eyes. Keep BASE separation at 0 px first, start AI DEPTH gently, and use SWAP EYES if foreground/background depth feels reversed. Depth is relative and experimental, not game-engine geometry.",0xfff3efe3,Color.BLACK);',
'        box(root,"v0.7.4 CPU-SAFE depth keeps the proven SBS/head/controller stack but removes TensorFlow GPU inference from the RayNeo display path. AI starts OFF every app launch. First prove Minecraft is stable with AI OFF, then try AI GENTLE; MiDaS runs on one CPU thread at about 2 depth maps/sec and reuses the latest map between frames.",0xfff3efe3,Color.BLACK);',
'CPU safe info')

one(
'        aiOff.setOnClickListener(v->applyAiPreset(0,50));aiGentle.setOnClickListener(v->applyAiPreset(22,50));aiMedium.setOnClickListener(v->applyAiPreset(38,50));',
'        aiOff.setText("AI OFF");aiGentle.setText("AI GENTLE CPU");aiMedium.setText("AI MEDIUM CPU");\n        aiOff.setOnClickListener(v->applyAiPreset(0,50));aiGentle.setOnClickListener(v->applyAiPreset(22,50));aiMedium.setOnClickListener(v->applyAiPreset(38,50));',
'CPU preset labels')

p.write_text(s)
print('v0.7.4 CPU-safe UI transform OK')
