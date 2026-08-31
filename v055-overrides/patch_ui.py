from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()
repls=[
(
'mode=spinner(root,new String[]{"HYBRID VR LOOK (recommended)","GYRO RATE → right stick","HEAD ANGLE → right stick"});',
'mode=spinner(root,new String[]{"COMFORT MOTION (recommended) — stops when head stops","RAW GYRO RATE — speed-sensitive","HEAD ANGLE HOLD — keeps turning while off-centre"});'
),
(
'hybridLabel=label(root,"Hybrid gyro weight: 70%");hybrid=bar(root,0,100,70);',
'hybridLabel=label(root,"Comfort mode quick-flick protection is automatic");hybrid=bar(root,0,100,0);hybridLabel.setVisibility(View.GONE);hybrid.setVisibility(View.GONE);'
),
(
'box(root,"360° is optional and now EDGE-ONLY: it will not engage during normal looking around. It waits until you are almost at the full left/right head limit. v0.5.4 COMFORT LOOK also removes the fast-head-move speed boost.",0xfff3efe3,Color.BLACK);',
'box(root,"360° is optional and EDGE-ONLY: normal head-look now stops the camera as soon as your head stops. Head angle is used only to detect the far-left/right 360° edge. Quick flicks are speed-capped to reduce cloud-streaming disorientation.",0xfff3efe3,Color.BLACK);'
),
(
'private void updateLabels(){gainLabel.setText("VR camera gain: "+gain.getProgress()+"%");hybridLabel.setText("Hybrid gyro weight: "+hybrid.getProgress()+"%");curveLabel.setText(String.format(Locale.UK,"Precision curve: %.2f",curve.getProgress()/100.0));rateLabel.setText("Full right-stick at "+maxRate.getProgress()+"°/s");angleLabel.setText("Angle contribution full-stick at "+fullAngle.getProgress()+"°");',
'private void updateLabels(){gainLabel.setText("VR camera gain: "+gain.getProgress()+"%");hybridLabel.setText("Comfort motion quick-flick protection: ON");curveLabel.setText(String.format(Locale.UK,"Precision curve: %.2f",curve.getProgress()/100.0));rateLabel.setText("RAW rate mode full-stick at "+maxRate.getProgress()+"°/s");angleLabel.setText("ANGLE HOLD full-stick at "+fullAngle.getProgress()+"°");'
),
]
for old,new in repls:
    if old not in s:
        raise SystemExit('v0.5.5 UI target not found: '+old[:100])
    s=s.replace(old,new,1)
p.write_text(s)
print('v0.5.5 MainActivity transform OK')
