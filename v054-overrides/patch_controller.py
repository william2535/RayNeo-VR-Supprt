from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

repls = [
(
'private volatile double maxRate=90.0, fullAngle=35.0, vrGain=1.0, hybridRateWeight=0.70, responseCurve=1.30;',
'private volatile double maxRate=90.0, fullAngle=35.0, vrGain=1.0, hybridRateWeight=0.0, responseCurve=1.30;'
),
(
'private volatile double turnThreshold=40.0, turnStrength=0.55;',
'private volatile double turnThreshold=75.0, turnStrength=0.55;'
),
(
'        turnThreshold=i.getDoubleExtra("turnThreshold",turnThreshold);',
'        // v0.5.4: 360 assist is an EDGE action, not a normal-look action.\n        // Old profiles saved with a low value are clamped to the final 6% of yaw travel.\n        turnThreshold=Math.max(i.getDoubleExtra("turnThreshold",turnThreshold),yawLimit*0.94);'
),
(
'        if(mode==MODE_RATE){x=rateX;y=rateY;}\n        else if(mode==MODE_ANGLE){x=angleX;y=angleY;}\n        else {x=hybridRateWeight*rateX+(1.0-hybridRateWeight)*angleX;y=hybridRateWeight*rateY+(1.0-hybridRateWeight)*angleY;}',
'        if(mode==MODE_RATE){x=rateX;y=rateY;}\n        else {\n            // v0.5.4 COMFORT LOOK: no velocity boost. Fast and slow head turns\n            // reaching the same angle request the same stick output.\n            x=angleX;y=angleY;\n        }'
),
(
'        if((turnMode==TURN_CONTINUOUS || turnMode==TURN_MOTION_ONLY) && Math.abs(yawAngle)>turnThreshold){',
'        double edgeThreshold=Math.max(turnThreshold,yawLimit*0.94);\n        if((turnMode==TURN_CONTINUOUS || turnMode==TURN_MOTION_ONLY) && Math.abs(yawAngle)>edgeThreshold){'
),
(
'                double over=(Math.abs(yawAngle)-turnThreshold)/Math.max(15.0,turnThreshold);',
'                double over=(Math.abs(yawAngle)-edgeThreshold)/Math.max(8.0,yawLimit-edgeThreshold);'
),
(
'            if(Math.abs(yawAngle)>turnThreshold && !snapLatch){',
'            if(Math.abs(yawAngle)>edgeThreshold && !snapLatch){'
),
(
'            if(Math.abs(yawAngle)<turnThreshold*0.48)snapLatch=false;',
'            if(Math.abs(yawAngle)<edgeThreshold*0.72)snapLatch=false;'
),
(
'maxRate=prefs.getInt(pk("rate"),prefs.getInt("rate",defRate));fullAngle=prefs.getInt(pk("angle"),prefs.getInt("angle",35));hybridRateWeight=prefs.getInt(pk("hybrid"),70)/100.0;',
'maxRate=prefs.getInt(pk("rate"),prefs.getInt("rate",defRate));fullAngle=prefs.getInt(pk("angle"),prefs.getInt("angle",35));hybridRateWeight=0.0;'
),
(
'turnMode=prefs.getInt(pk("turnMode"),TURN_CONTINUOUS);turnThreshold=prefs.getInt(pk("turnThreshold"),40);turnStrength=prefs.getInt(pk("turnStrength"),55)/100.0;snapMs=prefs.getInt(pk("snapMs"),130);overlayEnabled=prefs.getBoolean(pk("overlay"),false);',
'turnMode=prefs.getInt(pk("turnMode"),TURN_CONTINUOUS);turnThreshold=Math.max(prefs.getInt(pk("turnThreshold"),75),yawLimit*0.94);turnStrength=prefs.getInt(pk("turnStrength"),55)/100.0;snapMs=prefs.getInt(pk("snapMs"),130);overlayEnabled=prefs.getBoolean(pk("overlay"),false);'
),
]

for old, new in repls:
    if old not in s:
        raise SystemExit('v0.5.4 controller patch target not found: ' + old[:100])
    s = s.replace(old, new, 1)

p.write_text(s)
print('v0.5.4 ControllerService transform OK')
